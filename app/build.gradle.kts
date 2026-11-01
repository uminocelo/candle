plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release key and version are shared with the other app; see buildSrc.
val signing = releaseSigning()
val candleVersion = appVersion()

android {
    namespace = "com.uminocelo.candle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.uminocelo.candle"
        // The HY300 runs Android 11 (API 30) with a 32-bit userspace.
        minSdk = 30
        targetSdk = 30
        versionCode = candleVersion.code
        versionName = candleVersion.name

        ndk {
            abiFilters += "armeabi-v7a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            signing?.let {
                storeFile = file(it.storeFile)
                storePassword = it.storePassword
                keyAlias = it.keyAlias
                keyPassword = it.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // Candle is sideloaded, not published on Google Play; API 30 matches the HY300 on purpose.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":upnp"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    // AC3, E-AC3 and DTS audio: the HY300 has no platform decoders for them.
    implementation(libs.jellyfin.ffmpeg.decoder)
    implementation(libs.smbj)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

requireReleaseSigning(signing)
verifyThirdParty("Candle (projector)")

// LICENSE and THIRD_PARTY.md, shown by the About screen.
val legalAssets = tasks.register<LegalAssets>("legalAssets") {
    files.from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY.md"))
}
androidComponents {
    onVariants { it.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssets::outputDir) }
}
