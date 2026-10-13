plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
}

// Same derivation as :wear's, one counter for every artifact (gradle.properties). The face is its own package,
// so its version code only has to rise.
val watchfaceVersionCodeBase = 1_000_000_000

// The Knit monogram (ADR 2026-10.3y2s, its amendment): an analog Watch Face Format v4 face, resources only — the
// system renders res/raw/watchface.xml, which scripts/gen-watchface.py generates. A prototype in the build only
// under `-Pknit.wear=true` (settings.gradle.kts), beside :wear and :watchface; sideloaded, never shipped yet.
android {
    // A WFF APK carries no code (hasCode="false" in the manifest), so no Kotlin either.
    enableKotlin = false
    namespace = "app.getknit.knit.watchface.monogram"
    // Lockstep with :app, :wear and :watchface (see the compileSdk / buildToolsVersion comments in :app).
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // Watch Face Push's naming (<marketplace package>.watchfacepush.<name>), chosen now so a later push from
        // :wear needs no new package; a face is its own APK and may not share :wear's id.
        applicationId = "app.getknit.knit.watchfacepush.monogram"
        // WFF v4 is Wear OS 6.
        minSdk = 36
        targetSdk = 36
        versionCode = watchfaceVersionCodeBase + providers.gradleProperty("knit.versionCode").get().toInt()
        versionName = providers.gradleProperty("knit.versionName").get()
    }

    buildTypes {
        // Both minified, as the WFF samples are: R8 is what keeps a dex out of a code-less APK (the debug build
        // otherwise packages AGP's own classes, and a face with a dex is refused). Never shrink resources — that
        // would drop the ones only watchface.xml names. Release is unsigned: sideload the debug variant until
        // distribution is decided.
        getByName("debug") {
            isMinifyEnabled = true
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = false
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
}

dependencyLocking {
    lockAllConfigurations()
}
