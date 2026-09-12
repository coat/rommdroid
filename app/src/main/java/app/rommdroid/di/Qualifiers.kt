package app.rommdroid.di

import javax.inject.Qualifier

/** The [okhttp3.OkHttpClient] for ROM transfers: same pool and interceptors as
 *  the API client, but without a read timeout, since these run to gigabytes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadClient
