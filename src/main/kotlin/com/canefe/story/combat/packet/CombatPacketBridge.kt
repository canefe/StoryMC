package com.canefe.story.combat.packet

import com.canefe.story.Story
import com.canefe.story.combat.CombatState
import com.canefe.story.combat.Combatant
import com.canefe.story.combat.DirectionalCombatService
import com.canefe.story.combat.SwingDir
import com.canefe.story.combat.adapter.PlayerCombatant
import com.canefe.story.combat.resolution.HitOutcome
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Plugin↔client wire for directional combat. Mirrors the StoryClient
 * `combat/` package payloads.
 *
 * Channels:
 *   - `story:swing_intent`           c2s   1 byte: SwingDir.ordinal
 *   - `story:block_intent`           c2s   1 byte SwingDir.ordinal, 1 byte pressed (0/1)
 *   - `story:direction_switch`       c2s   1 byte SwingDir.ordinal
 *   - `story:feint`                  c2s   (empty)
 *   - `story:combat_state_push`      s2c   see [encodeStatePush]
 *   - `story:combat_hit_outcome`     s2c   see [pushHitOutcome]
 *   - `story:combat_intent_rejected` s2c   UTF reason
 */
class CombatPacketBridge(
    private val plugin: Story,
    private val service: DirectionalCombatService,
) : PacketListener {
    fun register() {
        PacketEvents.getAPI().eventManager.registerListener(this, PacketListenerPriority.NORMAL)
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType !== PacketType.Play.Client.PLUGIN_MESSAGE) return
        val wrapper = WrapperPlayClientPluginMessage(event)
        val player = Bukkit.getPlayer(event.user.uuid) ?: return
        val data = wrapper.data
        // Log EVERY combat-channel plugin message so we can prove they arrive.
        val ch = wrapper.channelName
        if (ch.startsWith("story:combat_") || ch.startsWith("story:")) {
            plugin.logger.warning("[combat-pkt] ${player.name} channel=$ch bytes=${data.size}")
        }
        when (ch) {
            CHANNEL_SWING_INTENT -> handleSwing(player, data)
            CHANNEL_BLOCK_INTENT -> handleBlock(player, data)
            CHANNEL_DIRECTION_SWITCH -> handleDirSwitch(player, data)
            CHANNEL_FEINT -> handleFeint(player)
        }
    }

    private fun handleSwing(player: Player, data: ByteArray) {
        if (data.isEmpty()) {
            plugin.logger.info("[combat] swing packet from ${player.name} had empty payload")
            return
        }
        val dir = SwingDir.entries.getOrNull(data[0].toInt())
        if (dir == null) {
            plugin.logger.info("[combat] swing packet from ${player.name} had bad dir byte=${data[0].toInt()}")
            return
        }
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!plugin.configService.combatEnabled) {
                plugin.logger.info("[combat] swing from ${player.name} dropped — combat.enabled=false")
                return@Runnable
            }
            val combatant = service.registerPlayer(player)
            val state = combatant.currentState()
            val stam = combatant.stamina()
            val accepted = service.tryQueueSwing(combatant, dir)
            plugin.logger.info(
                "[combat] swing ${player.name} dir=$dir state=${state::class.simpleName} stamina=$stam accepted=$accepted",
            )
            if (!accepted) {
                pushIntentRejected(player, "swing rejected (state=${state::class.simpleName}, stamina=$stam)")
            }
        })
    }

    private fun handleBlock(player: Player, data: ByteArray) {
        if (data.size < 2) return
        val dir = SwingDir.entries.getOrNull(data[0].toInt()) ?: return
        val pressed = data[1].toInt() != 0
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!plugin.configService.combatEnabled) return@Runnable
            val combatant = service.registerPlayer(player)
            plugin.logger.info("[combat] block ${player.name} dir=$dir pressed=$pressed state=${combatant.currentState()::class.simpleName}")
            service.queueBlock(combatant, dir, pressed)
        })
    }

    private fun handleDirSwitch(player: Player, data: ByteArray) {
        if (data.isEmpty()) return
        val dir = SwingDir.entries.getOrNull(data[0].toInt()) ?: return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!plugin.configService.combatEnabled) return@Runnable
            val combatant = service.registerPlayer(player)
            val state = combatant.currentState()
            val accepted = service.tryQueueDirectionSwitch(combatant, dir)
            plugin.logger.info("[combat] dirSwitch ${player.name} dir=$dir state=${state::class.simpleName} accepted=$accepted")
            if (!accepted) {
                pushIntentRejected(player, "direction switch rejected (state=${state::class.simpleName})")
            }
        })
    }

    private fun handleFeint(player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!plugin.configService.combatEnabled) return@Runnable
            val combatant = service.registerPlayer(player)
            val state = combatant.currentState()
            val accepted = service.tryQueueFeint(combatant)
            plugin.logger.info("[combat] feint ${player.name} state=${state::class.simpleName} accepted=$accepted")
            if (!accepted) {
                pushIntentRejected(player, "feint rejected (state=${state::class.simpleName})")
            }
        })
    }

    /**
     * Push a [CombatState] update for [combatant] to [audience].
     */
    fun pushState(
        combatant: Combatant,
        state: CombatState,
        audience: Collection<Player>,
    ) {
        if (audience.isEmpty()) return
        val bytes = encodeStatePush(combatant, state)
        sendToAll(audience, CHANNEL_STATE_PUSH, bytes)
    }

    fun pushHitOutcome(
        attacker: Combatant,
        defender: Combatant,
        dir: SwingDir,
        outcome: HitOutcome,
        audience: Collection<Player>,
    ) {
        if (audience.isEmpty()) return
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(attacker.entityId)
            out.writeInt(defender.entityId)
            out.writeByte(dir.ordinal)
            out.writeByte(outcome.ordinal)
        }
        sendToAll(audience, CHANNEL_HIT_OUTCOME, baos.toByteArray())
    }

    fun pushIntentRejected(player: Player, reason: String) {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { it.writeUTF(reason) }
        sendToAll(listOf(player), CHANNEL_INTENT_REJECTED, baos.toByteArray())
    }

    private fun sendToAll(audience: Collection<Player>, channel: String, bytes: ByteArray) {
        for (player in audience) {
            try {
                PacketEvents.getAPI()
                    .playerManager
                    .getUser(player)
                    .sendPacket(WrapperPlayServerPluginMessage(channel, bytes))
            } catch (e: Exception) {
                plugin.logger.warning("[CombatPacketBridge] $channel send failed for ${player.name}: ${e.message}")
            }
        }
    }

    private fun encodeStatePush(combatant: Combatant, state: CombatState): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(combatant.entityId)
            out.writeByte(stateOrdinal(state))
            out.writeByte(dirOrdinal(state))
            out.writeShort(ticksLeft(state))
            val (cur, max) =
                if (combatant is PlayerCombatant) {
                    combatant.stamina().toInt() to combatant.maxStamina().toInt()
                } else {
                    -1 to 0
                }
            out.writeShort(cur)
            out.writeShort(max)
        }
        return baos.toByteArray()
    }

    private fun stateOrdinal(state: CombatState): Int =
        when (state) {
            CombatState.Idle -> 0
            is CombatState.Windup -> 1
            is CombatState.Active -> 2
            is CombatState.Recovery -> 3
            is CombatState.Blocking -> 4
            is CombatState.Staggered -> 5
        }

    private fun dirOrdinal(state: CombatState): Int =
        when (state) {
            is CombatState.Windup -> state.dir.ordinal
            is CombatState.Active -> state.dir.ordinal
            is CombatState.Blocking -> state.dir.ordinal
            else -> -1
        }

    private fun ticksLeft(state: CombatState): Int =
        when (state) {
            is CombatState.Windup -> state.ticksLeft
            is CombatState.Active -> state.ticksLeft
            is CombatState.Recovery -> state.ticksLeft
            is CombatState.Blocking -> state.parryWindowTicksLeft
            is CombatState.Staggered -> state.ticksLeft
            CombatState.Idle -> 0
        }

    companion object {
        const val CHANNEL_SWING_INTENT = "story:swing_intent"
        const val CHANNEL_BLOCK_INTENT = "story:block_intent"
        const val CHANNEL_DIRECTION_SWITCH = "story:direction_switch"
        const val CHANNEL_FEINT = "story:feint"
        const val CHANNEL_STATE_PUSH = "story:combat_state_push"
        const val CHANNEL_HIT_OUTCOME = "story:combat_hit_outcome"
        const val CHANNEL_INTENT_REJECTED = "story:combat_intent_rejected"
    }
}
