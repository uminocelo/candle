plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release key and version are shared with the other app; see buildSrc.
val signing = releaseSigning()
val candleVersion = appVersion()

android {
    namespace = "com.uminocelo.candle.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.uminocelo.candle.server"
        // Built for the user's Galaxy S10 (Android 12, API 31).
        minSdk = 31
        targetSdk = 31
        versionCode = candleVersion.code
        versionName = candleVersion.name
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
        // Sideloaded, not published on Google Play; API 31 matches the S10 on purpose.
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
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}

requireReleaseSigning(signing)
verifyThirdParty("Candle Server (phone)")

// LICENSE and THIRD_PARTY.md, shown by the About screen.
val legalAssets = tasks.register<LegalAssets>("legalAssets") {
    files.from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY.md"))
}
androidComponents {
    onVariants { it.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssets::outputDir) }
}
