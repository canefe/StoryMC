package com.canefe.story.bridge

import com.canefe.storyproto.v1.SimEvent

/**
 * Bridges the sim→go→plugin [SimEvent] protobuf oneof to the existing
 * kotlinx-serialization data classes that listeners already subscribe to
 * (e.g. [CombatAttackResolvedEvent], [NpcStateIntent]).
 *
 * Wire shape: the WebSocket payload is the proto-canonical JSON of a bare
 * [SimEvent] — there is no `{type, source, timestamp, data}` envelope around
 * it. The transport tries to parse incoming bytes as [SimEvent] first; only
 * non-sim flows (intelligence.response, permission.ask, etc.) fall back to
 * the legacy [BridgeMessage] envelope.
 *
 * Returns `null` for sim events the plugin does not consume today
 * (sim_init, entity_died, recognition_reinforce, nearby_query_response,
 * location_template_get_response). These are still "recognized" — the
 * transport never logs them as unknown — but no listener is fired.
 *
 * Field-name mismatches between the proto and the existing Kotlin classes
 * are bridged here so the rest of the plugin (PerceptionBroadcaster,
 * SimAuthoritativeDamageListener, etc.) does not have to change.
 */
internal fun adaptSimEvent(sim: SimEvent): StoryEvent? = when (sim.eventCase) {
    SimEvent.EventCase.SIM_STATUS -> SimStatusEvent(
        running = sim.simStatus.running,
    )

    SimEvent.EventCase.SIM_AFFORDANCE_REGISTRY -> SimAffordanceRegistryEvent(
        types = sim.simAffordanceRegistry.typesList.map {
            AffordanceTypeDef(
                id = it.id,
                name = it.name,
                tags = it.tagsList.toList(),
                use_range = it.useRange.toDouble(),
                use_duration = it.useDuration.toDouble(),
                capacity = it.capacity.toInt(),
            )
        },
    )

    SimEvent.EventCase.NPC_STATE -> {
        val s = sim.npcState
        NpcStateIntent(
            characterId = s.characterId,
            name = s.name,
            x = s.x.toDouble(),
            y = s.y.toDouble(),
            z = s.z.toDouble(),
            world = s.world,
            health = s.health.toDouble(),
            actionId = s.actionId.ifEmpty { null },
            behaviorId = s.behaviorId.ifEmpty { null },
            actionLabel = if (s.hasActionLabel()) s.actionLabel else null,
        )
    }

    SimEvent.EventCase.ENTITY_SPAWNED -> {
        val e = sim.entitySpawned
        NpcSpawnIntent(
            characterId = e.characterId,
            name = e.name,
            race = e.race.ifEmpty { "human" },
            mobTemplate = e.mobTemplate.ifEmpty { "Character" },
            x = e.x.toDouble(),
            y = e.y.toDouble(),
            z = e.z.toDouble(),
            world = e.world,
        )
    }

    SimEvent.EventCase.NPC_ITEM_TRANSFER -> {
        val t = sim.npcItemTransfer
        NpcItemTransferIntent(
            fromCharacterId = t.fromCharacterId,
            toCharacterId = t.toCharacterId,
            item = t.item,
            qty = t.qty.toInt(),
            reason = t.reason.ifEmpty { "give" },
        )
    }

    SimEvent.EventCase.GO_TO -> {
        val g = sim.goTo
        GoToExecIntent(
            characterId = g.characterId,
            intentId = g.intentId,
            x = g.x,
            y = g.y,
            z = g.z,
            world = g.world,
            arrivalRange = g.arrivalRange,
            stallTimeout = g.stallTimeout,
            maxDuration = g.maxDuration,
        )
    }

    SimEvent.EventCase.COMBAT_ATTACK_RESOLVED -> {
        val c = sim.combatAttackResolved
        CombatAttackResolvedEvent(
            attackerId = c.attackerId.ifEmpty { null },
            attackerName = c.attackerName,
            defenderId = c.defenderId.ifEmpty { null },
            defenderName = c.defenderName,
            weaponSource = c.weaponSource,
            outcome = c.outcome.ifEmpty { "miss" },
            damageDealt = c.damageDealt,
            damageType = c.damageType.ifEmpty { "generic" },
            targetPart = if (c.hasTargetPart()) c.targetPart else null,
            cascadedParts = c.cascadedPartsList.toList(),
            killed = c.killed,
            tick = c.tick.toLong(),
        )
    }

    // Recognized but not consumed by the plugin today: swallow silently so
    // the transport's unknown-type fallback never fires for these.
    SimEvent.EventCase.SIM_INIT,
    SimEvent.EventCase.ENTITY_DIED,
    SimEvent.EventCase.RECOGNITION_REINFORCE,
    SimEvent.EventCase.NEARBY_QUERY_RESPONSE,
    SimEvent.EventCase.LOCATION_TEMPLATE_GET_RESPONSE,
    SimEvent.EventCase.EVENT_NOT_SET,
    null -> null
}
