package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.agent.ClockToolHandler
import com.contextsolutions.mobileagent.app.service.clock.AndroidAlarmScheduler
import com.contextsolutions.mobileagent.app.service.clock.ClockNotifications
import com.contextsolutions.mobileagent.clock.AlarmScheduler
import com.contextsolutions.mobileagent.clock.ClockRepository
import com.contextsolutions.mobileagent.clock.ClockService
import com.contextsolutions.mobileagent.clock.SharedPreferencesClockRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the clock subsystem: persistent repository, AlarmManager-backed
 * scheduler, notification helper, domain service, and the tool handler
 * Gemma calls into.
 *
 * [ClockService.rearmAll] is invoked from [ClockBootReceiver][com.contextsolutions.mobileagent.app.service.clock.ClockBootReceiver]
 * on device boot / app upgrade — no app-start hook here; the application
 * doesn't need to re-arm on every cold launch because AlarmManager
 * preserves scheduled fires across process kills.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    @Singleton
    fun provideClockRepository(@ApplicationContext context: Context): ClockRepository =
        SharedPreferencesClockRepository(context)

    @Provides
    @Singleton
    fun provideAlarmScheduler(@ApplicationContext context: Context): AlarmScheduler =
        AndroidAlarmScheduler(context)

    @Provides
    @Singleton
    fun provideClockNotifications(@ApplicationContext context: Context): ClockNotifications =
        ClockNotifications(context)

    @Provides
    @Singleton
    fun provideClockService(
        repository: ClockRepository,
        scheduler: AlarmScheduler,
    ): ClockService = ClockService(repository = repository, scheduler = scheduler)

    @Provides
    @Singleton
    fun provideClockToolHandler(clockService: ClockService): ClockToolHandler =
        ClockToolHandler(clockService)
}
