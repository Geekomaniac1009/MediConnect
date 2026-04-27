package com.example.mediconnect_ai.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // This is the special IP address for the Android Emulator to connect to the computer it's running on.
    // It must end with a "/"
    private const val BASE_URL = "http://10.146.133.73:5000/"

    val instance: MediConnectApiService by lazy {
        // LLM + TTS calls can exceed OkHttp's default 10s read timeout.
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(130, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(MediConnectApiService::class.java)
    }
}