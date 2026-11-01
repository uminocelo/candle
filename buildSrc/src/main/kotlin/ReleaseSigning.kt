import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The release key, from Gradle properties (`~/.gradle/gradle.properties`) or the matching
 * environment variables (for CI). Nothing secret lives in the repo.
 */
data class ReleaseSigning(val storeFile: String, val storePassword: String, val keyAlias: String, val keyPassword: String) {
    companion object {
        val KEYS = listOf("candle.signing.storeFile", "candle.signing.storePassword", "candle.signing.keyAlias", "candle.signing.keyPassword")

        /** `candle.signing.storeFile` → `CANDLE_SIGNING_STORE_FILE`. */
        fun envName(key: String) = key.replace('.', '_').replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

        /** The key, or null when any part is missing; [lookup] gives a property's value. */
        fun from(lookup: (String) -> String?): ReleaseSigning? {
            val (file, storePassword, alias, keyPassword) = KEYS.map { lookup(it)?.takeIf(String::isNotBlank) ?: return null }
            return ReleaseSigning(file, storePassword, alias, keyPassword)
        }

        val MISSING = "Release signing is not set up. Set ${KEYS.joinToString()} in ~/.gradle/gradle.properties " +
            "(or ${KEYS.joinToString { envName(it) }}). Debug builds and tests do not need it."
    }
}

/** This project's release key, or null; see [ReleaseSigning]. */
fun Project.releaseSigning(): ReleaseSigning? =
    ReleaseSigning.from { key -> providers.gradleProperty(key).orNull ?: System.getenv(ReleaseSigning.envName(key)) }

/** Makes packaging a release APK fail with a clear message when there is no release key, never sign with the debug key. */
fun Project.requireReleaseSigning(signing: ReleaseSigning?) {
    if (signing != null) return
    tasks.matching { it.name == "packageRelease" }.configureEach { doFirst { throw GradleException(ReleaseSigning.MISSING) } }
}

/** The version of both apps; see [AppVersion]. */
fun Project.appVersion(): AppVersion =
    AppVersion.resolve(providers.gradleProperty("candleVersion").orNull, providers.gradleProperty("candle.version").orNull)
