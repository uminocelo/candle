/**
 * The version of both apps: `MAJOR.MINOR.PATCH`, optionally `-rcN`, with or without a leading `v`
 * (so a git tag can be passed as is). The versionCode always grows: a release candidate sorts
 * before its release (`1.2.3-rc1` → 1020301, `1.2.3` → 1020399).
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val rc: Int?) {
    val name: String get() = "$major.$minor.$patch" + (rc?.let { "-rc$it" } ?: "")
    val code: Int get() = major * 1_000_000 + minor * 10_000 + patch * 100 + (rc ?: FINAL)

    companion object {
        private const val FINAL = 99
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-rc(\d+))?""")

        fun parse(text: String): AppVersion {
            val match = PATTERN.matchEntire(text.trim())
                ?: throw IllegalArgumentException("Bad version \"$text\": use 1.2.3 or 1.2.3-rc1")
            val (major, minor, patch, rc) = match.destructured
            val version = AppVersion(major.toInt(), minor.toInt(), patch.toInt(), rc.ifEmpty { null }?.toInt())
            require(version.minor < 100 && version.patch < 100) { "Bad version \"$text\": minor and patch must be below 100" }
            require(version.rc == null || version.rc in 1 until FINAL) { "Bad version \"$text\": rc must be 1 to ${FINAL - 1}" }
            return version
        }

        /** [tag] (the release tag CI passes as `-PcandleVersion`) wins over [default] (`candle.version`). */
        fun resolve(tag: String?, default: String?): AppVersion =
            parse(tag?.takeIf { it.isNotBlank() } ?: default ?: throw IllegalArgumentException("Set candle.version in gradle.properties"))
    }
}
