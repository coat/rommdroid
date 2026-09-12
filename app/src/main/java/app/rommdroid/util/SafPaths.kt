package app.rommdroid.util

import android.net.Uri
import java.net.URLDecoder

/**
 * Human-readable path from a SAF tree URI. The decoded last segment looks like
 * "primary:Roms/SNES"; the volume prefix is stripped. Non-standard providers
 * (MTP, cloud) fall back to the full URI string.
 */
fun safDisplayPath(uri: Uri): String {
    return try {
        val encoded = uri.lastPathSegment ?: return uri.toString()
        val decoded = URLDecoder.decode(encoded, "UTF-8")
        // "primary:Roms/SNES" -> "Roms/SNES"
        // "0000-1111:Roms/SNES" -> "Roms/SNES" (SD card)
        if (decoded.contains(':')) decoded.substringAfter(':') else decoded
    } catch (_: Exception) {
        uri.toString()
    }
}
