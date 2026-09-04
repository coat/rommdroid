package app.rommdroid.data.api

import okhttp3.Interceptor
import okhttp3.Response
import app.rommdroid.data.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the stored Client API Token to every outgoing request.
 * Falls back to HTTP Basic if only username+password are stored (initial setup).
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val credentials: CredentialRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = credentials.apiToken
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            // Basic auth fallback during initial token exchange
            val basic = credentials.basicAuthHeader
            if (basic != null) {
                chain.request().newBuilder()
                    .header("Authorization", basic)
                    .build()
            } else {
                chain.request()
            }
        }
        return chain.proceed(request)
    }
}
