package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.app.ui.chat.AndroidTtsSpeaker
import com.contextsolutions.mobileagent.app.ui.chat.ChatSpeaker
import com.contextsolutions.mobileagent.app.ui.chat.SharedPreferencesTtsPreferences
import com.contextsolutions.mobileagent.app.ui.chat.TtsPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the read-aloud (text-to-speech) seam — a persisted on/off toggle and
 * the on-device speaker engine. Both app-scoped singletons (mirrors
 * [ThemeModule]); the [ChatSpeaker] holds a single [android.speech.tts.TextToSpeech]
 * for the app's life.
 */
@Module
@InstallIn(SingletonComponent::class)
object TtsModule {

    @Provides
    @Singleton
    fun provideTtsPreferences(
        @ApplicationContext context: Context,
    ): TtsPreferences = SharedPreferencesTtsPreferences(context)

    @Provides
    @Singleton
    fun provideChatSpeaker(
        @ApplicationContext context: Context,
    ): ChatSpeaker = AndroidTtsSpeaker(context)
}
