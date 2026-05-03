package com.contextsolutions.mobileagent.app.di

import com.contextsolutions.mobileagent.app.spike.StubInferenceEngine
import com.contextsolutions.mobileagent.inference.InferenceEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * In M0 we bind the InferenceEngine to [StubInferenceEngine]. M1 swaps this for the
 * real LiteRT-LM-backed implementation. The agent loop in commonMain is identical
 * either way — that's the whole point of the [InferenceEngine] seam.
 */
@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {

    @Provides
    @Singleton
    fun provideInferenceEngine(): InferenceEngine = StubInferenceEngine()
}
