import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Every CI build gets a distinct versionCode so APKs are distinguishable and
// installable over one another. Locally there's no run number, so it stays 1.
val buildNumber = (System.getenv("BUILD_NUMBER")
    ?: providers.gradleProperty("buildNumber").orNull)
    ?.toIntOrNull() ?: 1

android {
    namespace   = "app.rommdroid"
    compileSdk  = 35
    buildToolsVersion = "35.0.0"   // matches what nixpkgs androidenv provides

    defaultConfig {
        applicationId   = "app.rommdroid"
        minSdk          = 29          // Android 10 — clean SAF, scoped storage
        targetSdk       = 35
        versionCode     = buildNumber
        versionName     = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Launcher label. Build types override this so a debug build sitting
        // next to a release one on the same device is tellable apart at a
        // glance — see the resValue in the debug block below.
        resValue("string", "app_launcher_name", "RomMDroid")
    }

    // Release signing. Values come from local.properties (untracked) or, for CI,
    // the matching env vars. Without them the release build stays unsigned and
    // will not install on a device.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(key: String, env: String): String? =
        keystoreProps.getProperty(key) ?: System.getenv(env)

    val releaseStorePath = signingValue("release.storeFile", "RELEASE_STORE_FILE")

    signingConfigs {
        // Checked into the repo on purpose. The SDK's auto-generated debug key
        // differs per machine and per CI runner, so debug APKs from two sources
        // can't be installed over each other. A shared key fixes that; the
        // credentials are the Android debug defaults and guard nothing.
        getByName("debug") {
            storeFile     = file("debug.keystore")
            storePassword = "android"
            keyAlias      = "androiddebugkey"
            keyPassword   = "android"
        }

        if (releaseStorePath != null) {
            create("release") {
                storeFile     = rootProject.file(releaseStorePath)
                storePassword = signingValue("release.storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias      = signingValue("release.keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword   = signingValue("release.keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig     = signingConfigs.findByName("release")
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            // Overrides the defaultConfig value above (build types win), so the
            // debug launcher icon reads "RomMDroid (Beta)".
            resValue("string", "app_launcher_name", "RomMDroid (Beta)")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ── Core ─────────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)

    // ── Compose BOM ──────────────────────────────────────────────────────────
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // ── Navigation ───────────────────────────────────────────────────────────
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // ── DI ───────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // ── Room ─────────────────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── WorkManager ──────────────────────────────────────────────────────────
    implementation(libs.work.runtime.ktx)

    // ── DataStore + Security ─────────────────────────────────────────────────
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // ── Network ──────────────────────────────────────────────────────────────
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // ── Images ───────────────────────────────────────────────────────────────
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ── DocumentFile (SAF) ───────────────────────────────────────────────────
    implementation(libs.documentfile)

    // ── Tests ────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
