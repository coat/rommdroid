package app.rommdroid.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves the first usable artwork reference into an absolute, loadable URL.
 *
 * RomM hands back two kinds of reference for the same image:
 *  - server-relative paths (`/assets/romm/resources/roms/2/20/cover/small.png?ts=…`)
 *    for the copy RomM scraped and now serves itself, and
 *  - absolute `http(s)` URLs pointing back at the metadata provider it scraped from.
 *
 * Pass the local paths first: the provider URLs are scrape-time source links, and
 * the ScreenScraper ones carry RomM's own developer credentials, so they answer
 * `200` with a login-error string instead of an image. The local copy is what the
 * RomM web UI renders.
 *
 * Blank and unparseable candidates are skipped; returns null if none resolve.
 */
fun artworkUrl(serverUrl: String?, vararg candidates: String?): String? {
    // Trailing slash so relative candidates resolve under the server root, not
    // alongside the last path segment.
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
        // resolve() also percent-encodes anything the API left raw, such as the
        // space in the "?ts=2026-03-05 02:46:14" cache buster.
        if (resolved != null) return resolved.toString()
    }
    return null
}
