package app.rommdroid.data.repository

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import app.rommdroid.data.security.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credentials in a private preferences file, each value sealed by
 * [KeystoreCipher]. Writes are synchronous; the values are tiny. Reads are
 * cached: the interceptors ask for the URL and token on every request, and a
 * Keystore round trip per request is not free.
 */
@Singleton
class CredentialRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)
    private val cache = ConcurrentHashMap<String, String>()

    // Server URL

    var serverUrl: String?
        get() = get(KEY_SERVER_URL)
        set(value) = put(KEY_SERVER_URL, value)

    // Client API token, preferred over Basic auth

    var apiToken: String?
        get() = get(KEY_API_TOKEN)
        set(value) = put(KEY_API_TOKEN, value)

    // Basic auth, used only for the initial token exchange

    var username: String?
        get() = get(KEY_USERNAME)
        set(value) = put(KEY_USERNAME, value)

    private var password: String?
        get() = get(KEY_PASSWORD)
        set(value) = put(KEY_PASSWORD, value)

    fun setBasicCredentials(user: String, pass: String) {
        username = user
        password = pass
    }

    /** The "Basic <base64>" header value, or null when unset. */
    val basicAuthHeader: String?
        get() {
            val u = username ?: return null
            val p = password ?: return null
            val encoded = Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
            return "Basic $encoded"
        }

    /** Called once the token exchange succeeds; the password is not persisted. */
    fun clearPassword() {
        password = null
    }

    // Snapshot / restore

    /** Everything stored here, as one value. */
    data class Snapshot(
        val serverUrl: String?,
        val apiToken: String?,
        val username: String?,
        val password: String?,
    )

    /** A copy a failed re-connect can put back. Signing in writes as it goes,
     *  URL first, so the interceptors reach the server being verified. */
    fun snapshot(): Snapshot = Snapshot(serverUrl, apiToken, username, password)

    fun restore(snapshot: Snapshot) {
        serverUrl = snapshot.serverUrl
        apiToken  = snapshot.apiToken
        username  = snapshot.username
        password  = snapshot.password
    }

    /** Wipes every stored credential, for "Disconnect / Change server".
     *  Downloaded files and folder mappings are unaffected. */
    fun clearAll() {
        cache.clear()
        prefs.edit { clear() }
    }

    /** True once there is enough stored to make API calls. */
    val isConfigured: Boolean
        get() = !serverUrl.isNullOrBlank() && (apiToken != null || basicAuthHeader != null)

    // Storage

    private fun get(key: String): String? {
        cache[key]?.let { return it }
        val value = prefs.getString(key, null)?.let(cipher::decrypt) ?: return null
        cache[key] = value
        return value
    }

    /** Null removes the key, so an unset value never lingers as an empty string. */
    private fun put(key: String, value: String?) {
        if (value == null) cache.remove(key) else cache[key] = value
        prefs.edit {
            if (value == null) remove(key) else putString(key, cipher.encrypt(value))
        }
    }

    private companion object {
        const val PREFS_FILE     = "rommdroid_secrets"
        const val KEY_ALIAS      = "rommdroid_credentials"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_TOKEN  = "api_token"
        const val KEY_USERNAME   = "username"
        const val KEY_PASSWORD   = "password"   // only kept during token setup
    }
}
