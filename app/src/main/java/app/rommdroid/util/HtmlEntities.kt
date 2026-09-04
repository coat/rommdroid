package app.rommdroid.util

/**
 * Named references RomM's metadata providers (IGDB, ScreenScraper, MobyGames)
 * actually emit in titles and summaries.  Deliberately not the full HTML5 set —
 * that is some 2,200 names, and anything outside this list arrives as a numeric
 * reference, which [decodeHtmlEntities] resolves generically.
 */
private val NAMED_ENTITIES = mapOf(
    "quot" to "\"",     "amp" to "&",       "lt" to "<",        "gt" to ">",
    "apos" to "'",      "nbsp" to "\u00A0", "hellip" to "…",
    "mdash" to "—", "ndash" to "–",
    "lsquo" to "‘", "rsquo" to "’",
    "ldquo" to "“", "rdquo" to "”",
    "trade" to "™", "reg" to "®", "copy" to "©", "deg" to "°",
    "bull" to "•", "middot" to "·",
    "eacute" to "é", "egrave" to "è", "agrave" to "à",
    "ccedil" to "ç", "ntilde" to "ñ", "szlig" to "ß",
    "auml" to "ä", "ouml" to "ö", "uuml" to "ü",
)

/** Longest reference we will consider: "&#x0001F600;" and friends. */
private const val MAX_REFERENCE_LENGTH = 12

/**
 * Resolves HTML character references in scraped metadata text.
 *
 * RomM passes provider text through verbatim, so a summary arrives as
 * `As Mario, the player must control a &quot;podship&quot;`.  The text is
 * otherwise plain — no markup, and real paragraph breaks as newlines — which
 * rules out `Html.fromHtml`: it decodes the references but collapses those
 * blank lines along the way.
 *
 * Anything unrecognised is left exactly as it came, so text that merely
 * contains a bare "&" survives untouched.
 *
 * Do NOT apply this to filenames.  `fs_name` and `file_name` are real names on
 * disk, where a literal "&amp;" has to survive verbatim to address the right
 * file.
 */
fun String.decodeHtmlEntities(): String {
    if ('&' !in this) return this   // overwhelmingly the common case

    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }

        // A missing ";" — or one too far off to belong to this "&" — means the
        // ampersand is literal text.
        val end = indexOf(';', i + 1)
        if (end == -1 || end - i > MAX_REFERENCE_LENGTH) {
            out.append(c)
            i++
            continue
        }

        val body = substring(i + 1, end)
        val replacement = when {
            body.startsWith("#x", ignoreCase = true) ->
                body.drop(2).toIntOrNull(16)?.asCodePoint()
            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.asCodePoint()
            else -> NAMED_ENTITIES[body]
        }

        if (replacement != null) {
            out.append(replacement)
            i = end + 1
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

private fun Int.asCodePoint(): String? =
    if (Character.isValidCodePoint(this)) String(Character.toChars(this)) else null
