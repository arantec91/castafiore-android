package com.arantec.castafiore.data.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit

object NavidromeClient {
    private var retrofit: Retrofit? = null
    private var apiService: NavidromeApiService? = null

    fun initialize(serverUrl: String) {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit?.create(NavidromeApiService::class.java)
    }

    fun getApiService(): NavidromeApiService {
        return apiService ?: throw IllegalStateException("NavidromeClient not initialized")
    }

    fun generateAuthParams(username: String, password: String): Triple<String, String, String> {
        val salt = UUID.randomUUID().toString().replace("-", "").substring(0, 6)
        val token = md5("$password$salt")
        return Triple(username, token, salt)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }
}
