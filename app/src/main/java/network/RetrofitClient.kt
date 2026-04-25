package com.example.mediconnect_ai.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    // This is the special IP address for the Android Emulator to connect to the computer it's running on.
    // It must end with a "/"
    private const val BASE_URL = "http://10.61.73.153:5000/"

    val instance: MediConnectApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(MediConnectApiService::class.java)
    }
}