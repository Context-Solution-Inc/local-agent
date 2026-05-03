package com.contextsolutions.mobileagent.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MobileAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Auxiliary models (pre-flight classifier, memory extractor, embedder) will be
        // loaded here at app start in M3+ since their combined footprint is small enough
        // (PRD section 4.2). Gemma 4 is loaded lazily on first query, not here.
    }
}
