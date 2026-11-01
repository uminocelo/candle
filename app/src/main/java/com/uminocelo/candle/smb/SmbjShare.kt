package com.uminocelo.candle.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as ShareFile

/**
 * [SmbShare] over smbj. Keeps one connection and session per server for the whole
 * process, and reconnects on the next call after it drops.
 */
class SmbjShare(private val server: SmbServer, private val password: CharArray) : SmbShare {

    override fun list(path: String): List<SmbEntry> = withShare { share ->
        share.list(SmbPaths.toSmb(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val attributes = info.fileAttributes
                SmbEntry(
                    name = info.fileName,
                    isFolder = attributes has FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
                    size = info.endOfFile,
                    hidden = attributes has FileAttributes.FILE_ATTRIBUTE_HIDDEN || attributes has FileAttributes.FILE_ATTRIBUTE_SYSTEM,
                )
            }
    }

    override fun open(path: String): SmbFile = withShare { share ->
        val file = share.openFile(
            SmbPaths.toSmb(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        SmbjFile(file, file.getFileInformation(FileStandardInformation::class.java).endOfFile)
    }

    /**
     * Runs [block] on the shared session. A dead connection (the server or Wi-Fi
     * dropped it while idle) is replaced and tried once more; an answer from the
     * server, such as a wrong password, is not retried.
     */
    private fun <T> withShare(block: (DiskShare) -> T): T = try {
        block(sharedShare(server, password))
    } catch (e: SMBApiException) {
        throw IOException(describe(e.status) ?: e.message, e)
    } catch (e: Exception) {
        forget(server.id)
        try {
            block(sharedShare(server, password))
        } catch (retry: SMBApiException) {
            throw IOException(describe(retry.status) ?: retry.message, retry)
        } catch (retry: IOException) {
            throw retry
        } catch (retry: RuntimeException) {
            // smbj reports transport failures as unchecked SMBRuntimeExceptions.
            throw IOException("Lost the connection to ${server.host}: ${retry.message}", retry)
        }
    }

    /** Plain words for the failures people can fix themselves. */
    private fun describe(status: NtStatus): String? = when (status) {
        NtStatus.STATUS_LOGON_FAILURE -> "Wrong user name or password"
        NtStatus.STATUS_ACCESS_DENIED -> "This user may not open the share"
        NtStatus.STATUS_BAD_NETWORK_NAME -> "No share called ${server.share} on ${server.host}"
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "Not found on the server"
        else -> null
    }

    private infix fun Long.has(attribute: FileAttributes) = this and attribute.value != 0L

    companion object {
        private val client: SMBClient by lazy {
            SMBClient(
                SmbConfig.builder()
                    .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .withSoTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }
        private val shares = HashMap<String, Pair<Connection, DiskShare>>()

        /** Drops the cached session, e.g. after the server is edited or removed. */
        @Synchronized
        fun forget(serverId: String) {
            shares.remove(serverId)?.let { (connection, _) -> runCatching { connection.close(true) } }
        }

        @Synchronized
        private fun sharedShare(server: SmbServer, password: CharArray): DiskShare {
            shares[server.id]?.let { (connection, share) ->
                if (connection.isConnected && share.isConnected) return share
                runCatching { connection.close(true) }
            }
            // SMBClient hands back its cached connection to the host; after a failed login the
            // server may have closed it, so a failure here closes it for the next attempt.
            val connection = client.connect(server.host)
            try {
                val auth = if (server.username.isEmpty()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(server.username, password, server.domain)
                }
                val share = connection.authenticate(auth).connectShare(server.share) as? DiskShare
                    ?: throw IOException("${server.share} on ${server.host} is not a file share")
                shares[server.id] = connection to share
                return share
            } catch (e: Exception) {
                runCatching { connection.close(true) }
                throw e
            }
        }

        private const val TIMEOUT_SECONDS = 10L
    }
}

private class SmbjFile(private val file: ShareFile, override val length: Long) : SmbFile {
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        try {
            file.read(buffer, position, offset, length)
        } catch (e: RuntimeException) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }

    override fun close() = runCatching { file.close() }.let { }
}
