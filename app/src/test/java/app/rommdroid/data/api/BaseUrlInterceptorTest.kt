package app.rommdroid.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** Retrofit builds every request against a placeholder host; these pin down
 *  what the rewrite onto the configured server keeps and what it replaces. */
class BaseUrlInterceptorTest {

    private fun rebased(base: String, request: String = "http://localhost/api/roms?limit=5") =
        request.toHttpUrl().rebase(base.toHttpUrl()).toString()

    @Test
    fun `a bare host replaces scheme, host and port`() {
        assertEquals("https://romm.example/api/roms?limit=5", rebased("https://romm.example"))
        assertEquals("http://romm.local:8080/api/roms?limit=5", rebased("http://romm.local:8080"))
    }

    @Test
    fun `a server under a sub-path keeps that path as a prefix`() {
        assertEquals("https://host/romm/api/roms?limit=5", rebased("https://host/romm"))
    }

    @Test
    fun `a trailing slash on the server url does not double up`() {
        assertEquals("https://host/romm/api/roms?limit=5", rebased("https://host/romm/"))
        assertEquals("https://host/api/roms?limit=5", rebased("https://host/"))
    }

    @Test
    fun `encoded request paths survive the rewrite`() {
        assertEquals(
            "https://host/romm/api/roms/7/content/Sonic%20%26%20Tails.zip",
            rebased("https://host/romm", "http://localhost/api/roms/7/content/Sonic%20%26%20Tails.zip"),
        )
    }
}
