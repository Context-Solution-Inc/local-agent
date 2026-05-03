package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.AndroidHttpEngineFactory
import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.platform.LocaleProvider
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges the KMP shared layer's platform contracts (interfaces in commonMain) into Hilt's
 * graph. Constructed once per process and injected wherever the agent needs them.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun provideAgentClock(): AgentClock = AgentClock()

    @Provides
    @Singleton
    fun provideLocaleProvider(): LocaleProvider = LocaleProvider()

    @Provides
    @Singleton
    fun provideSecureStorage(@ApplicationContext context: Context): SecureStorage =
        SecureStorageFactory.create(context)

    @Provides
    @Singleton
    fun provideHttpEngineFactory(): HttpEngineFactory = AndroidHttpEngineFactory()
}
