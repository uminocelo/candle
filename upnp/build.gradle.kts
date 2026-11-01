// HTTP server and UPnP pieces (SOAP, SSDP, GENA, XML) shared by Candle and Candle Server.
// Plain Kotlin/JVM: no Android code, so its tests run without Android stubs.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
