package app.rommdroid.data.repository

import app.rommdroid.data.api.RomMApi
import app.rommdroid.data.api.model.CreateTokenRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** A connection attempt that failed, carrying a message fit to show the user. */
class ConnectionException(message: String) : Exception(message)

/**
 * Points the app at a RomM server and gets it authenticated.
 *
 * First-run setup and editing the connection in Settings do the same work, so
 * both go through here.  Credentials are written before the verification
 * requests — the interceptors read them from [CredentialRepository] — so every
 * path restores the previous ones when the attempt fails, leaving a user who
 * mistyped a password still connected to the server they were using.
 */
@Singleton
class ServerConnector @Inject constructor(
    private val credentials: CredentialRepository,
    private val api: RomMApi,
) {

    /**
     * Signs in as [username], exchanging the password for a client API token.
     * Needed whenever the account changes, and the only way back in once a
     * token has been revoked or the account's password has changed.
     */
    suspend fun signIn(serverUrl: String, username: String, password: String): Result<Unit> =
        attempt(serverUrl) { url ->
            credentials.serverUrl = url
            // Any stored token belongs to the previous sign-in; drop it so the
            // exchange below goes out as Basic auth for what was just entered.
            credentials.apiToken = null
            credentials.setBasicCredentials(username, password)

            api.heartbeat()

            val token = api.createClientToken(CreateTokenRequest(name = TOKEN_NAME)).rawToken
            if (token != null) {
                credentials.apiToken = token
                // Basic auth was only for the exchange — don't keep the password.
                credentials.clearPassword()
            }
        }

    /**
     * Moves the existing sign-in to [serverUrl] — the same account reached at a
     * new address.  Fails when the stored token is no good there, which is the
     * point at which the caller has to ask for a password and use [signIn].
     */
    suspend fun moveTo(serverUrl: String): Result<Unit> =
        attempt(serverUrl) { url ->
            credentials.serverUrl = url
            api.heartbeat()
            // heartbeat is unauthenticated, so it only proves the server is
            // there.  /users/me is what proves the token still opens it.
            api.getMe()
        }

    /**
     * Records [serverUrl] without checking it, for a server that isn't reachable
     * from wherever the device is at the moment.  Only the address moves — the
     * token and the account stay as they are — so a wrong one costs nothing but
     * another edit once the app next fails to reach it.
     */
    fun setServerUrl(serverUrl: String): Result<Unit> {
        val url = normalize(serverUrl) ?: return Result.failure(ConnectionException(BAD_URL))
        credentials.serverUrl = url
        return Result.success(Unit)
    }

    private suspend fun attempt(
        serverUrl: String,
        block: suspend (url: String) -> Unit,
    ): Result<Unit> {
        val url = normalize(serverUrl) ?: return Result.failure(ConnectionException(BAD_URL))
        val previous = credentials.snapshot()
        return try {
            block(url)
            Result.success(Unit)
        } catch (e: Exception) {
            credentials.restore(previous)
            Result.failure(ConnectionException(e.describe()))
        }
    }

    /** Trims to the form the interceptors want, or null if it isn't a URL. */
    private fun normalize(serverUrl: String): String? =
        serverUrl.trim().trimEnd('/').takeIf { it.toHttpUrlOrNull() != null }

    private companion object {
        const val TOKEN_NAME = "RomMDroid"
        const val BAD_URL = "Enter a full server URL, e.g. http://romm.local"
    }
}

/**
 * A bare Retrofit [HttpException] only says "HTTP 422 Unprocessable Content",
 * which hides the reason the server gave.  RomM puts that in a JSON "detail"
 * field, so pull it out when it's there.
 */
private fun Exception.describe(): String {
    val fallback = message ?: "Connection failed"
    if (this !is HttpException) return fallback
    val body = response()?.errorBody()?.string().orEmpty()
    val detail = runCatching {
        Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
            .jsonObject["detail"]
            ?.let { if (it is JsonPrimitive) it.content else it.toString() }
    }.getOrNull()
    return if (detail.isNullOrBlank()) fallback else "$fallback: $detail"
}
