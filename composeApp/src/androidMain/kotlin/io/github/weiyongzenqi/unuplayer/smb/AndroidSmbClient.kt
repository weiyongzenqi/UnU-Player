package io.github.weiyongzenqi.unuplayer.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** 只封装 SMBJ，不把第三方类型泄漏到 commonMain。所有调用方必须在 IO 调度器执行。 */
internal class AndroidSmbClient(
    private val connection: SmbConnection,
) : AutoCloseable {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(30, TimeUnit.SECONDS)
            .withWriteTimeout(30, TimeUnit.SECONDS)
            .withSigningEnabled(true)
            .withEncryptData(connection.requireEncryption)
            .build(),
    )

    fun list(path: String): List<MediaEntry> = withShare { share ->
        share.list(normalizePath(path))
            .asSequence()
            .filterNot { it.fileName == "." || it.fileName == ".." }
            .map { info ->
                val isDirectory = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                MediaEntry(
                    name = info.fileName,
                    path = joinPath(path, info.fileName),
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else info.endOfFile,
                    lastModified = 0L,
                )
            }
            .toList()
    }

    fun openRead(path: String): AndroidSmbFileHandle {
        val connected = connectShare()
        return try {
            val file = connected.share.openFile(
                normalizePath(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(
                    SMB2ShareAccess.FILE_SHARE_READ,
                    SMB2ShareAccess.FILE_SHARE_WRITE,
                    SMB2ShareAccess.FILE_SHARE_DELETE,
                ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_RANDOM_ACCESS),
            )
            val size = file.getFileInformation(FileStandardInformation::class.java).endOfFile
            AndroidSmbFileHandle(file, size) {
                connected.close()
                close()
            }
        } catch (error: Throwable) {
            connected.close()
            throw error
        }
    }

    override fun close() = client.close()

    private fun <T> withShare(block: (DiskShare) -> T): T {
        val connected = connectShare()
        return try {
            block(connected.share)
        } finally {
            connected.close()
        }
    }

    private fun connectShare(): ConnectedShare {
        val tcp = client.connect(connection.host, connection.port)
        return try {
            val authentication = AuthenticationContext(
                connection.username,
                connection.password.toCharArray(),
                connection.domain.ifBlank { null },
            )
            val session = tcp.authenticate(authentication)
            val share = session.connectShare(connection.share) as? DiskShare
                ?: error("SMB 共享不是磁盘共享")
            ConnectedShare(tcp, session, share)
        } catch (error: Throwable) {
            runCatching { tcp.close() }
            throw error
        }
    }

    private data class ConnectedShare(
        val connection: com.hierynomus.smbj.connection.Connection,
        val session: com.hierynomus.smbj.session.Session,
        val share: DiskShare,
    ) : AutoCloseable {
        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    companion object {
        fun normalizePath(path: String): String = path.trim().trim('/', '\\').replace('/', '\\')

        fun joinPath(parent: String, child: String): String = listOf(
            parent.trim().trim('/', '\\'),
            child.trim().trim('/', '\\'),
        ).filter { it.isNotEmpty() }.joinToString("/")
    }
}

internal class AndroidSmbFileHandle(
    private val file: SmbFile,
    val size: Long,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun read(offset: Long, destination: ByteArray, size: Int): Int {
        check(!closed) { "SMB 文件已关闭" }
        require(offset >= 0L && size >= 0 && size <= destination.size) { "SMB 读取范围无效" }
        return file.read(destination, offset, 0, size).let { if (it < 0) 0 else it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { file.close() }
        onClosed()
    }
}
