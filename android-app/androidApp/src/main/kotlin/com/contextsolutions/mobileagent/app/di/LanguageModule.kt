package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.agent.TranslationIntentDetector
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.language.SharedPreferencesLanguagePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PR #10 — preferred response language + translation-aware character
 * filter. Wires the non-secret SharedPreferences-backed preference store
 * and the stateless heuristic detector that decides whether to relax the
 * filter for a given turn.
 */
@Module
@InstallIn(SingletonComponent::class)
object LanguageModule {

    @Provides
    @Singleton
    fun provideLanguagePreferences(
        @ApplicationContext context: Context,
    ): LanguagePreferences = SharedPreferencesLanguagePreferences(context)

    @Provides
    @Singleton
    fun provideTranslationIntentDetector(): TranslationIntentDetector = TranslationIntentDetector()
}
