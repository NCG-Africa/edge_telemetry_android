package com.mktowett.housingproject.di

import com.androidtel.telemetry_library.core.TelemetryManager
import com.mktowett.housingproject.BuildConfig
import com.mktowett.housingproject.data.remote.ApiService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

val NetworkModule =
    module {
        single {
            val interceptor = HttpLoggingInterceptor()
            interceptor.level = HttpLoggingInterceptor.Level.BODY

            // Create a custom interceptor to add the Content-Type header
            val contentTypeInterceptor =
                Interceptor { chain ->
                    val originalRequest = chain.request()
                    val requestWithHeaders =
                        originalRequest.newBuilder()
                            .header("Content-Type", "application/json")
                            .build()
                    chain.proceed(requestWithHeaders)
                }

            // Get the telemetry interceptor
            val telemetryInterceptor = TelemetryManager.createNetworkInterceptor()

            OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .addInterceptor(contentTypeInterceptor)
                .addInterceptor(telemetryInterceptor)  // ✅ Add telemetry tracking
                .connectTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }

        single {
            Retrofit.Builder()
                .baseUrl(BuildConfig.BASE_URL)
                .client(get())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        single {
            get<Retrofit>().create(ApiService::class.java)
        }
    }
