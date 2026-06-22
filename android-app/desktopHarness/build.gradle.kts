plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Reuses the desktop (jvm) variant of :shared — the whole agent loop, search,
    // memory, classifier, prompt assembly, and the desktopMain platform impls
    // (LlamaCppInferenceEngine, DesktopHttpEngineFactory, NoOp aux engines).
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    // JDBC SQLite driver so the harness can stand up an in-memory LocalAgentDatabase
    // (proves the SQLDelight JVM driver seam too). M1 — :shared (desktop) now bundles the
    // willena fork's org.sqlite, so exclude xerial here to avoid a duplicate org.sqlite.JDBC.
    implementation(libs.sqldelight.sqlite.driver) {
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }
}

application {
    mainClass.set("com.contextsolutions.localagent.desktop.harness.MainKt")
}
