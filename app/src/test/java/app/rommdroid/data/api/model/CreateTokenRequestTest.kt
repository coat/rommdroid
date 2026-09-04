package app.rommdroid.data.api.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RomM's ClientTokenCreatePayload requires `scopes` (min 1 item) and rejects the
 * body with 422 when it is absent.  Because `scopes` has a Kotlin default, it is
 * only put on the wire when the Json instance has encodeDefaults enabled — so
 * pin both the setting and the scope names here.
 */
class CreateTokenRequestTest {

    // Must mirror NetworkModule.provideJson()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `scopes are serialized even when left at their default`() {
        val encoded = json.encodeToString(CreateTokenRequest.serializer(), CreateTokenRequest(name = "RomMDroid"))
        assertTrue("scopes missing from $encoded", encoded.contains("\"scopes\""))
        assertEquals(
            """{"name":"RomMDroid","scopes":["roms.read","platforms.read",""" +
                """"collections.read","firmware.read","me.read"],"expires_in":null}""",
            encoded,
        )
    }

    @Test
    fun `every requested scope exists in RomM's scope enum`() {
        // handler/auth/constants.py — READ_SCOPES_MAP; note there is no "tasks.read".
        val readScopes = setOf(
            "me.read", "roms.read", "platforms.read", "assets.read", "devices.read",
            "firmware.read", "roms.user.read", "collections.read", "playlists.read",
        )
        val requested = CreateTokenRequest(name = "x").scopes
        assertTrue(requested.isNotEmpty())
        assertEquals(emptySet<String>(), requested.toSet() - readScopes)
    }
}
