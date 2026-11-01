package com.uminocelo.candle.smb

/** A saved SMB server. The password is kept apart, encrypted ([PasswordVault]). */
data class SmbServer(
    val id: String,
    val host: String,
    val share: String,
    val username: String,
    /** Windows domain or workgroup; usually empty. */
    val domain: String = "",
) {
    val displayName: String get() = "$share on $host"

    /** Resume positions and playback targets carry this as their source id. */
    val sourceId: String get() = SOURCE_PREFIX + id

    companion object {
        const val SOURCE_PREFIX = "smb:"

        /** The server id inside a source id, or null when it is not an SMB source. */
        fun idFromSourceId(sourceId: String): String? = sourceId.removePrefix(SOURCE_PREFIX).takeIf { sourceId.startsWith(SOURCE_PREFIX) && it.isNotEmpty() }

        /**
         * Cleans up what was typed on the remote: `\\nas\Media`, `smb://nas/Media` or a
         * host with the share in its own field all give host "nas", share "Media".
         * Returns null when the host or share is missing.
         */
        fun fromInput(id: String, host: String, share: String, username: String, domain: String = ""): SmbServer? {
            val address = host.trim().removePrefix("smb://").trim('\\', '/').replace('\\', '/')
            val cleanHost = address.substringBefore('/')
            val cleanShare = share.trim().trim('\\', '/').ifEmpty { address.substringAfter('/', "").substringBefore('/') }
            if (cleanHost.isEmpty() || cleanShare.isEmpty()) return null
            return SmbServer(id, cleanHost, cleanShare, username.trim(), domain.trim())
        }
    }
}
