package com.canefe.story.api.character

import com.canefe.story.intelligence.AppearanceTemplatesDTO
import com.canefe.story.intelligence.BridgeIntelligence
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * In-memory cache of pronoun + slot templates owned by story-recognition.
 *
 * Refreshed at plugin startup (after the bridge advertises capabilities) and
 * on `/story reload`. The renderer in [toProse] reads from this cache so
 * prose generation stays synchronous on hot paths (NPC reactions, prompt
 * builders) without a per-render bridge call.
 *
 * When the bridge is unreachable or the templates haven't loaded yet, the
 * cache returns empty maps — callers render to an empty string. We don't
 * fall back to local templates since bridge-disabled mode is unsupported.
 */
class AppearanceTemplateCache(private val logger: Logger) {
    private val current = AtomicReference(AppearanceTemplatesDTO())

    fun pronouns(): Map<String, Map<String, String>> = current.get().pronouns

    fun slots(): Map<String, String> = current.get().slots

    /** Pronoun set for [gender]; falls back to a they/them set when missing. */
    fun pronounFor(gender: Gender): Pronoun {
        val key = gender.name.lowercase()
        val raw = pronouns()[key] ?: pronouns()["unknown"] ?: emptyMap()
        return Pronoun(
            subject = raw["subject"] ?: "They",
            possessive = raw["possessive"] ?: "their",
            has = raw["has"] ?: "have",
            isVerb = raw["is"] ?: "are",
        )
    }

    /** Pull templates from the bridge and swap them in. Logs and returns on failure. */
    fun refresh(bridge: BridgeIntelligence) {
        bridge.getAppearanceTemplates()
            .thenAccept { tpl ->
                current.set(tpl)
                logger.info(
                    "[AppearanceTemplateCache] Loaded ${tpl.slots.size} slots, ${tpl.pronouns.size} pronoun sets from recognition.",
                )
            }
            .exceptionally { e ->
                logger.warning(
                    "[AppearanceTemplateCache] Failed to load templates from recognition: ${e.message}",
                )
                null
            }
    }
}
