package app.rommdroid.util

/**
 * Region tags as they appear on ROMs, normalised for display and comparison.
 *
 * RomM populates `regions` by parsing the filename tags, so the same region
 * arrives under whichever convention the set uses: No-Intro spells it out
 * ("USA", "Europe"), GoodTools abbreviates to a single letter ("U", "E", "J"),
 * and some sets use the ISO code directly.  All three have to collapse onto one
 * value or the same game shows up as two different "regions".
 */

/** Pseudo-regions that have no ISO code but do have an obvious glyph. */
private const val WORLD = "WORLD"
private const val ASIA  = "ASIA"

/**
 * Lower-cased alias → canonical code.  Canonical codes are ISO 3166 alpha-2
 * wherever one exists, which is what lets [flagOf] derive the emoji instead of
 * this table carrying one per entry — and what lets the device locale's country
 * be used directly as a region preference.
 */
private val ALIASES: Map<String, String> = buildMap {
    fun alias(code: String, vararg names: String) {
        put(code.lowercase(), code)
        names.forEach { put(it.lowercase(), code) }
    }
    alias("US", "u", "usa", "america", "united states", "ntsc-u", "ntsc-us")
    alias("EU", "e", "eur", "europe", "european", "pal")
    alias("JP", "j", "jpn", "japan", "japanese", "ntsc-j")
    alias(WORLD, "w", "world", "global")
    alias(ASIA, "as", "asia")
    alias("AU", "a", "aus", "australia")
    alias("BR", "b", "bra", "brazil")
    alias("CA", "can", "canada")
    alias("CN", "c", "chn", "china", "chinese")
    alias("KR", "k", "kor", "korea", "korean")
    alias("FR", "f", "fra", "france", "french")
    alias("DE", "g", "ger", "germany", "german")
    alias("IT", "i", "ita", "italy", "italian")
    alias("ES", "s", "spa", "spain", "spanish")
    alias("NL", "n", "nld", "netherlands", "holland", "dutch")
    alias("SE", "sw", "swe", "sweden", "swedish")
    alias("NO", "nor", "norway")
    alias("DK", "dk", "den", "denmark")
    alias("FI", "fin", "finland")
    alias("RU", "rus", "russia", "russian")
    alias("PL", "pol", "poland")
    alias("PT", "por", "portugal")
    alias("GR", "gre", "greece")
    alias("MX", "mex", "mexico")
    alias("TW", "twn", "taiwan")
    alias("HK", "hkg", "hong kong")
    alias("GB", "uk", "england", "united kingdom")
    alias("IN", "ind", "india")
    alias("IL", "isr", "israel")
    alias("TR", "tur", "turkey")
}

/** Tokens that appear in the same `(…)` slot as a region but are not one. */
private val NON_REGION_TAGS = setOf(
    "proto", "prototype", "beta", "demo", "sample", "kiosk", "unl", "unlicensed",
    "pirate", "aftermarket", "virtual console", "gamecube", "switch online",
    "en", "fr", "de", "es", "it", "ja", "nl", "pt", "sv", "no", "da", "fi", "zh", "ko",
)

/**
 * Canonical code for [raw], or the trimmed upper-cased input when the tag is
 * not a region we know.  Never returns blank for non-blank input, so unknown
 * regions still group and display as themselves rather than vanishing.
 */
fun normalizeRegion(raw: String): String {
    val key = raw.trim().lowercase()
    if (key.isEmpty()) return ""
    return ALIASES[key] ?: raw.trim().uppercase()
}

/** Emoji for a canonical region code, or null when there is no sensible glyph. */
fun regionFlag(code: String): String? = when (val c = normalizeRegion(code)) {
    ""      -> null
    WORLD   -> "🌍"   // 🌍
    ASIA    -> "🌏"   // 🌏
    else    -> flagOf(c)
}

/**
 * What to render for a region: the flag when one exists, otherwise a short
 * text code so unmapped regions are still distinguishable at a glance.
 */
fun regionLabel(code: String): String {
    val c = normalizeRegion(code)
    return regionFlag(c) ?: c.take(3)
}

/**
 * Regional-indicator pair for a two-letter code.  Android's emoji font renders
 * these as flags; anything that is not two A–Z letters has no flag.
 */
private fun flagOf(code: String): String? {
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + code[0].code)) +
           String(Character.toChars(base + code[1].code))
}

/**
 * Best-effort regions for a ROM filename, used when the server left `regions`
 * empty — common for ROMs it could not identify, which are exactly the ones the
 * user most needs help telling apart.
 *
 * Only `(…)` groups are considered; `[…]` is the dump-status slot in both the
 * GoodTools and No-Intro conventions and never holds a region.
 */
fun parseRegionsFromFileName(fsName: String): List<String> {
    val out = LinkedHashSet<String>()
    Regex("\\(([^()]*)\\)").findAll(fsName).forEach { match ->
        // "(USA, Europe)" is one tag holding two regions.
        match.groupValues[1].split(',').forEach { part ->
            val token = part.trim()
            if (token.isEmpty()) return@forEach
            val key = token.lowercase()
            if (key in NON_REGION_TAGS) return@forEach
            ALIASES[key]?.let { out += it }
        }
    }
    return out.toList()
}

/**
 * One-line region summary for a list row: "🇺🇸 🇪🇺 🇯🇵", trimmed to [max] entries
 * with a "+N" tail so a game released in a dozen territories does not push the
 * rest of the row off screen.  Blank when nothing is known.
 */
fun regionSummary(regions: List<String>, max: Int = 4): String {
    val labels = regions.map(::regionLabel).filter { it.isNotEmpty() }
    if (labels.isEmpty()) return ""
    val shown = labels.take(max).joinToString(" ")
    val hidden = labels.size - max
    return if (hidden > 0) "$shown +$hidden" else shown
}
