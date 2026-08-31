plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Every CI build must produce a strictly increasing versionCode, otherwise
// Android refuses to install the APK over an existing one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.wisp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wisp.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // Phones only. WebRTC ships a native library per ABI, and dropping the
        // x86 ones roughly halves the APK we have to sideload.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Populated from env by the release workflow. When the keystore is absent
    // (local dev, or a branch build with no secrets) the release build falls
    // back to the debug signing config so `assembleRelease` still works.
    // CI passes an empty string rather than unsetting the variable, so blank
    // has to count as absent too.
    val keystoreFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // One APK per chip type. The WebRTC engine is ~18 MB of native library and
    // a phone only ever needs its own ABI, so a per-ABI APK is roughly half the
    // size of the combined one — which matters on an older phone that is short
    // on space. The universal APK stays available for anyone unsure which to take.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        viewBinding = true
        // Needed so a crash report can name the exact build it came from.
        buildConfig = true
    }

    lint {
        // Advisory-only checks that don't apply to a two-phone, sideloaded app.
        disable += setOf(
            "ChromeOsAbiSupport",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "Overdraw",
            "UnusedResources",
            // mipmap-anydpi-v26 is the form AAPT accepts for adaptive icons.
            "ObsoleteSdkInt"
        )
        warningsAsErrors = false
        abortOnError = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
}
