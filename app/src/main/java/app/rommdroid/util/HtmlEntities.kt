package app.rommdroid.util

/** The named references the metadata providers actually emit; everything else
 *  arrives as a numeric reference and is resolved generically. */
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

/** Longest reference considered: "&#x0001F600;" and friends. */
private const val MAX_REFERENCE_LENGTH = 12

/**
 * Resolves HTML character references in scraped metadata text.
 *
 * Not `Html.fromHtml`: provider text is plain apart from the references, and
 * fromHtml collapses the blank lines that separate its paragraphs. Anything
 * unrecognised is left as it came, so a bare "&" survives.
 *
 * Do NOT apply this to filenames. `fs_name` and `file_name` are real names on
 * disk where a literal "&amp;" has to survive verbatim.
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

        // No ";" nearby means the ampersand is literal text.
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
