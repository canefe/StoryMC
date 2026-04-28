package com.canefe.story.api.character

/**
 * Render an `appearance` trait map to canonical English prose.
 *
 * Templates and pronoun sets live in story-recognition (config.PRONOUNS /
 * SLOT_TEMPLATES) and are cached locally by [AppearanceTemplateCache] —
 * fetched at startup and on `/story reload`. The renderer is synchronous
 * because the cache is in-memory; no per-render bridge call.
 *
 * Slots without a template are silently skipped — the recognition config is
 * the gate, not the data. Empty/none values are also skipped.
 *
 * This call site is the unbiased / no-perceiver render. The per-perceiver
 * seam lives at `bridge.describe(perceiverId, targetId)` and returns prose
 * shaped by recognition state (color blindness, illusion, disguises) — that
 * path is for render-time consumers like StoryClient nametags.
 */
fun Map<String, String>.toProse(gender: Gender, cache: AppearanceTemplateCache): String {
    if (isEmpty()) return ""
    val templates = cache.slots()
    if (templates.isEmpty()) return ""
    val pronoun = cache.pronounFor(gender)
    val lines = mutableListOf<String>()
    for ((slot, value) in this) {
        if (value.isBlank() || value.equals("none", ignoreCase = true)) continue
        val tpl = templates[slot] ?: continue
        lines += render(tpl, pronoun, value)
    }
    return lines.joinToString(" ")
}

/** Pronoun set for one [Gender]. */
data class Pronoun(
    val subject: String,    // He / She / They
    val possessive: String, // his / her / their
    val has: String,        // has / has / have
    val isVerb: String,     // is / is / are
)

private fun render(template: String, p: Pronoun, value: String): String {
    return template
        .replace("{Subject}", p.subject)
        .replace("{subject}", p.subject.lowercase())
        .replace("{Possessive}", p.possessive.replaceFirstChar { it.uppercaseChar() })
        .replace("{possessive}", p.possessive)
        .replace("{has}", p.has)
        .replace("{is}", p.isVerb)
        .replace("{value}", value)
}
