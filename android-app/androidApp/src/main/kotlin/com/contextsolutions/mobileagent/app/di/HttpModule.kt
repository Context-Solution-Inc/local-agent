package com.contextsolutions.mobileagent.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Single OkHttpClient process-wide for the model download. Ktor (in :shared) has
 * its own client for Brave Search; this one is dedicated to long-lived binary
 * downloads with timeouts tuned for 2.58 GB streaming.
 *
 * Connect/read timeouts are explicit: a stuck CDN connection should error after
 * 30 s and let WorkManager retry rather than holding a foreground worker open
 * for hours.
 */
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {

    @Provides
    @Singleton
    @ModelDownloadHttp
    fun provideModelDownloadHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // No write timeout — we're only GETing.
        .retryOnConnectionFailure(true)
        .build()
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ModelDownloadHttp
