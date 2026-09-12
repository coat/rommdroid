package app.rommdroid.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import app.rommdroid.BuildConfig
import app.rommdroid.data.api.AuthInterceptor
import app.rommdroid.data.api.BaseUrlInterceptor
import app.rommdroid.data.api.RomMApi
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Lenient, so a newer server's unknown keys and unexpected nulls do not
     *  crash the app. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues  = true
        isLenient          = true
        // Request bodies rely on Kotlin defaults (CreateTokenRequest.scopes).
        // Without this they are dropped and the server answers 422.
        encodeDefaults     = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        baseUrlInterceptor: BaseUrlInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor)   // rewrite host first
        .addInterceptor(authInterceptor)      // then add auth headers
        .apply {
            if (BuildConfig.DEBUG) {
                addNetworkInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    }
                )
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Shares the API client's connection pool and auth, but not the base URL
     * rewrite: a queued download stores an absolute URL built for the server it
     * was queued from, prefix and all, so it goes out as stored.
     */
    @Provides
    @Singleton
    @DownloadClient
    fun provideDownloadClient(
        apiClient: OkHttpClient,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient = apiClient.newBuilder()
        .apply { interceptors().clear() }
        .addInterceptor(authInterceptor)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        // Placeholder; BaseUrlInterceptor rewrites it per request.
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideRomMApi(retrofit: Retrofit): RomMApi =
        retrofit.create(RomMApi::class.java)
}
