pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Secure Gateway client SDK (com.contextsolutions.securegateway:{core,java,android}) —
        // the GPG-signed artifacts published to GitHub Packages (M4 Step 2; secure-gateway's
        // publish-sdk.yml). Replaces the old unsigned mavenLocal() consumption. Auth: gpr.user/
        // gpr.key gradle props (~/.gradle/gradle.properties) or GITHUB_ACTOR/GITHUB_TOKEN env —
        // a PAT with `read:packages`. GitHub Packages always requires auth, even to read.
        // Content-filtered so ONLY this coordinate is queried here (everything else stays on
        // google()/mavenCentral()), and dependency verification pins it (gradle/verification-metadata.xml).
        maven {
            name = "SecureGatewayGitHubPackages"
            url = uri("https://maven.pkg.github.com/Context-Solutions-Inc/secure-gateway")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("com.contextsolutions.securegateway") }
        }
    }
}

rootProject.name = "local-agent"

include(":shared")
include(":androidApp")
// Headless desktop integration harness — Phase 0 of the desktop port
// (docs/DESKTOP_PORT_PLAN.md). Drives AgentLoop + llama.cpp with no UI/DI.
include(":desktopHarness")
// Phase 1 — shared Compose Multiplatform UI + thin Compose Desktop shell.
include(":ui")
include(":desktopApp")
