package app.rommdroid.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import app.rommdroid.data.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites the base URL of every request to the user-configured server URL.
 *
 * Retrofit requires the base URL to be known at construction time, but the user
 * sets it during first-run setup.  This interceptor makes it work by keeping a
 * placeholder base URL in the Retrofit instance and replacing the host/scheme at
 * request time from [CredentialRepository.serverUrl].
 */
@Singleton
class BaseUrlInterceptor @Inject constructor(
    private val credentials: CredentialRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val serverUrl = credentials.serverUrl
            ?: return chain.proceed(chain.request()) // no server configured yet

        val originalRequest = chain.request()
        val originalUrl = originalRequest.url
        val newBase = serverUrl.trimEnd('/').toHttpUrl()

        val newUrl = originalUrl.newBuilder()
            .scheme(newBase.scheme)
            .host(newBase.host)
            .port(newBase.port)
            .build()

        return chain.proceed(
            originalRequest.newBuilder().url(newUrl).build()
        )
    }
}
