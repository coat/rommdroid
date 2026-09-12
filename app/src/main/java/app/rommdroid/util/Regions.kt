package app.rommdroid.util

import java.util.Locale

// The same region reaches us under whichever convention the ROM set uses:
// No-Intro ("USA"), GoodTools ("U"), or the bare ISO code. All three have to
// collapse onto one value or a game shows up as two regions.

/** Pseudo-regions with no ISO code but an obvious glyph. */
private const val WORLD = "WORLD"
private const val ASIA  = "ASIA"

/** Lower-cased alias -> canonical code. Canonical codes are ISO 3166 alpha-2
 *  where one exists, so [flagOf] can derive the emoji and the device locale's
 *  country works directly as a region preference. */
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

/** Tokens that appear in the same `(...)` slot as a region but are not one. */
private val NON_REGION_TAGS = setOf(
    "proto", "prototype", "beta", "demo", "sample", "kiosk", "unl", "unlicensed",
    "pirate", "aftermarket", "virtual console", "gamecube", "switch online",
    "en", "fr", "de", "es", "it", "ja", "nl", "pt", "sv", "no", "da", "fi", "zh", "ko",
)

/** Canonical code for [raw], else the trimmed upper-cased input. Never blank for
 *  non-blank input, so unknown regions display as themselves. */
fun normalizeRegion(raw: String): String {
    val key = raw.trim().lowercase()
    if (key.isEmpty()) return ""
    return ALIASES[key] ?: raw.trim().uppercase()
}

/** Emoji for a canonical region code, or null when there is no sensible glyph. */
fun regionFlag(code: String): String? = when (val c = normalizeRegion(code)) {
    ""      -> null
    WORLD   -> "🌍"
    ASIA    -> "🌏"
    else    -> flagOf(c)
}

/** The flag when one exists, else a short text code so unmapped regions stay
 *  distinguishable. */
fun regionLabel(code: String): String {
    val c = normalizeRegion(code)
    return regionFlag(c) ?: c.take(3)
}

/** The region spelled out, for somewhere a flag alone would be a guessing
 *  game: "Europe" beside the flag on a filter chip. The device's language,
 *  since that is who is reading it, except for the two every ROM set names
 *  its own way: the locale calls them "United States" and "European Union". */
fun regionName(code: String): String = when (val c = normalizeRegion(code)) {
    WORLD -> "World"
    ASIA  -> "Asia"
    "US"  -> "USA"
    "EU"  -> "Europe"
    else  -> if (flagOf(c) != null) {
        Locale("", c).getDisplayCountry(Locale.getDefault()).ifBlank { c }
    } else {
        c
    }
}

/** Regional-indicator pair, which Android's emoji font renders as a flag. */
private fun flagOf(code: String): String? {
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + code[0].code)) +
           String(Character.toChars(base + code[1].code))
}

/**
 * Best-effort regions from a ROM filename, for when the server left `regions`
 * empty. Only `(...)` groups count; `[...]` is the dump-status slot in both the
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

/** One-line region summary for a list row, trimmed to [max] entries with a "+N"
 *  tail. Blank when nothing is known. */
fun regionSummary(regions: List<String>, max: Int = 4): String {
    val labels = regions.map(::regionLabel).filter { it.isNotEmpty() }
    if (labels.isEmpty()) return ""
    val shown = labels.take(max).joinToString(" ")
    val hidden = labels.size - max
    return if (hidden > 0) "$shown +$hidden" else shown
}
