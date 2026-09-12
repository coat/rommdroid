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
 * Points the app at a RomM server and authenticates it, for first-run setup and
 * for editing the connection in Settings alike.
 *
 * The interceptors read credentials from [CredentialRepository], so they have to
 * be written before the verification requests. Every path restores the previous
 * ones on failure, leaving a mistyped password still connected.
 */
@Singleton
class ServerConnector @Inject constructor(
    private val credentials: CredentialRepository,
    private val api: RomMApi,
) {

    /** Signs in as [username], exchanging the password for a client API token.
     *  The only way back in once a token is revoked or a password changed. */
    suspend fun signIn(serverUrl: String, username: String, password: String): Result<Unit> =
        attempt(serverUrl) { url ->
            credentials.serverUrl = url
            // Drop the previous sign-in's token so the exchange below goes out
            // as Basic auth for what was just entered.
            credentials.apiToken = null
            credentials.setBasicCredentials(username, password)

            api.heartbeat()

            val token = api.createClientToken(CreateTokenRequest(name = TOKEN_NAME)).rawToken
            if (token != null) {
                credentials.apiToken = token
                // Basic auth was only for the exchange.
                credentials.clearPassword()
            }
        }

    /** Moves the existing sign-in to [serverUrl]. Fails when the stored token is
     *  no good there, at which point the caller falls back to [signIn]. */
    suspend fun moveTo(serverUrl: String): Result<Unit> =
        attempt(serverUrl) { url ->
            credentials.serverUrl = url
            api.heartbeat()
            // heartbeat is unauthenticated; /users/me proves the token works.
            api.getMe()
        }

    /** Records [serverUrl] unchecked, for a server out of reach right now. Only
     *  the address moves, so a wrong one costs one more edit. */
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

/** A bare [HttpException] says only "HTTP 422 Unprocessable Content"; RomM's
 *  reason is in a JSON "detail" field. */
private fun Exception.describe(): String {
    val fallback = message ?: "Connection failed"
    if (this !is HttpException) return fallback
    val body = response()?.errorBody()?.string().orEmpty()
    val detail = runCatching {
        Json.parseToJsonElement(body)
            .jsonObject["detail"]
            ?.let { if (it is JsonPrimitive) it.content else it.toString() }
    }.getOrNull()
    return if (detail.isNullOrBlank()) fallback else "$fallback: $detail"
}
