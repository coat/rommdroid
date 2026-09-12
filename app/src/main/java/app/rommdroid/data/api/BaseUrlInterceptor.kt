package app.rommdroid.data.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import app.rommdroid.data.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites every request onto [CredentialRepository.serverUrl]. Retrofit needs
 * a base URL at construction time and the user does not supply one until
 * first-run setup, so it is built with a placeholder.
 */
@Singleton
class BaseUrlInterceptor @Inject constructor(
    private val credentials: CredentialRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val serverUrl = credentials.serverUrl
            ?: return chain.proceed(chain.request()) // no server configured yet

        val request = chain.request()
        val rebased = request.url.rebase(serverUrl.toHttpUrl())
        return chain.proceed(request.newBuilder().url(rebased).build())
    }
}

/**
 * This URL served from under [base]. The base's path is kept as a prefix, so a
 * server behind a reverse proxy at `https://host/romm` gets `/romm/api/...`
 * rather than `/api/...`; the download and artwork URLs already do the same.
 */
internal fun HttpUrl.rebase(base: HttpUrl): HttpUrl {
    val prefix = base.encodedPath.trimEnd('/')
    return newBuilder()
        .scheme(base.scheme)
        .host(base.host)
        .port(base.port)
        .encodedPath(prefix + encodedPath)
        .build()
}
