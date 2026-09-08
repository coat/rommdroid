package app.rommdroid.data.repository

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Credentials in Keystore-backed EncryptedSharedPreferences. Writes are
 *  synchronous; the values are tiny. */
@Singleton
class CredentialRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "rommdroid_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_TOKEN  = "api_token"
        private const val KEY_USERNAME   = "username"
        private const val KEY_PASSWORD   = "password"   // only kept during token setup
    }

    // Server URL

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_SERVER_URL).apply()
            else prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }

    // Client API token, preferred over Basic auth

    var apiToken: String?
        get() = prefs.getString(KEY_API_TOKEN, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_API_TOKEN).apply()
            else prefs.edit().putString(KEY_API_TOKEN, value).apply()
        }

    // Basic auth, used only for the initial token exchange

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_USERNAME).apply()
            else prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    private var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_PASSWORD).apply()
            else prefs.edit().putString(KEY_PASSWORD, value).apply()
        }

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
        prefs.edit().remove(KEY_PASSWORD).apply()
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
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_API_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    /** True once there is enough stored to make API calls. */
    val isConfigured: Boolean
        get() = !serverUrl.isNullOrBlank() && (apiToken != null || basicAuthHeader != null)
}
