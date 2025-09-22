package net.citizensnpcs.api.npc

import net.citizensnpcs.api.ai.Navigator
import org.bukkit.entity.Entity
import java.util.*

// navigator should have a method npc.navigator.cancelNavigation() just make it do no op

// Minimal test stub of the Citizens NPC interface so tests can compile without the real dependency
interface NPC {
    val name: String
    val entity: Entity?
    val uniqueId: UUID?
    val id: Int
    val isSpawned: Boolean
    val navigator: Navigator
}
