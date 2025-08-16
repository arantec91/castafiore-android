package com.arantec.castafiore.data.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import java.util.concurrent.TimeUnit

/**
 * Lightweight Retrofit client for LRCLIB public API
 * Docs: https://lrclib.net/
 */
interface LrcLibApiService {
    @GET("api/search")
    suspend fun search(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String? = null,
        @Query("duration") duration: Int? = null
    ): Response<List<LrcLibItem>>
}

object LrcLibClient {
    val service: LrcLibApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LrcLibApiService::class.java)
    }
}

// Minimal fields we care about
data class LrcLibItem(
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Int?,
    val syncedLyrics: String?,
    val plainLyrics: String?
)

