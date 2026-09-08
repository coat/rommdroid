package app.rommdroid.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves the first usable artwork reference into an absolute URL, skipping
 * blank and unparseable candidates.
 *
 * Pass RomM's server-relative paths before its absolute provider URLs: the
 * latter are scrape-time source links, and the ScreenScraper ones carry RomM's
 * own credentials, so they answer 200 with a login error instead of an image.
 */
fun artworkUrl(serverUrl: String?, vararg candidates: String?): String? {
    // Trailing slash so relative candidates resolve under the server root.
    val base = serverUrl?.trim()?.trimEnd('/')?.plus("/")?.toHttpUrlOrNull()
    for (candidate in candidates) {
        val ref = candidate?.trim().orEmpty()
        if (ref.isEmpty()) continue
        val resolved =
            if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) {
                ref.toHttpUrlOrNull()
            } else {
                base?.resolve(ref)
            }
        // resolve() percent-encodes what the API left raw, e.g. the space in a
        // "?ts=2026-03-05 02:46:14" cache buster.
        if (resolved != null) return resolved.toString()
    }
    return null
}
