package app.rommdroid.data.api

import okhttp3.Interceptor
import okhttp3.Response
import app.rommdroid.data.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The stored client API token on every request, falling back to HTTP Basic
 *  while setup still has only a username and password. */
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
