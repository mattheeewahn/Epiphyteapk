package org.epiphyte.app.filetransfer

import android.content.Context
import org.epiphyte.app.crypto.CryptoEngine
import org.epiphyte.app.crypto.toHexString
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Encrypted file transfer - wire-compatible with Python FileTransferManager.
 * Chunked transfer with ChaCha20-Poly1305 per-chunk encryption.
 */

const val CHUNK_SIZE = 64 * 1024 // 64KB
const val MAX_FILE_SIZE = 100L * 1024 * 1024 // 100MB

data class FileMetadata(
    val fileId: String,
    val filename: String,
    val fileSize: Long,
    val chunkCount: Int,
    val sha256Hash: String,
    val mimeType: String = "",
    val burnAfterRead: Boolean = false,
    val expirySeconds: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("file_id", fileId)
        put("filename", filename)
        put("file_size", fileSize)
        put("chunk_count", chunkCount)
        put("sha256_hash", sha256Hash)
        put("mime_type", mimeType)
        put("burn_after_read", burnAfterRead)
        put("expiry_seconds", expirySeconds)
    }

    fun serialize(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(obj: JSONObject): FileMetadata = FileMetadata(
            fileId = obj.getString("file_id"),
            filename = obj.getString("filename"),
            fileSize = obj.getLong("file_size"),
            chunkCount = obj.getInt("chunk_count"),
            sha256Hash = obj.getString("sha256_hash"),
            mimeType = obj.optString("mime_type", ""),
            burnAfterRead = obj.optBoolean("burn_after_read", false),
            expirySeconds = obj.optInt("expiry_seconds", 0)
        )

        fun deserialize(data: ByteArray): FileMetadata? = try {
            fromJson(JSONObject(String(data, Charsets.UTF_8)))
        } catch (e: Exception) { null }
    }
}

data class FileTransferState(
    val metadata: FileMetadata,
    var chunksReceived: Int = 0,
    var chunksSent: Int = 0,
    var complete: Boolean = false,
    var cancelled: Boolean = false,
    var tempPath: String = "",
    var startedAt: Long = 0L
)

class FileTransferManager(private val context: Context) {
    private val transfersDir = File(context.filesDir, "transfers").also { it.mkdirs() }
    private val incoming = mutableMapOf<String, FileTransferState>()
    private val outgoing = mutableMapOf<String, FileTransferState>()

    var onProgress: ((String, Int) -> Unit)? = null
    var onComplete: ((String, String) -> Unit)? = null
    var onError: ((String, String) -> Unit)? = null

    fun prepareSend(filepath: String, burnAfterRead: Boolean = false, expirySeconds: Int = 0): FileMetadata? {
        val file = File(filepath)
        if (!file.exists()) return null
        val fileSize = file.length()
        if (fileSize > MAX_FILE_SIZE || fileSize == 0L) return null

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK_SIZE)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }

        val chunkCount = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val fileId = MessageDigest.getInstance("SHA-256")
            .digest("${file.name}$fileSize${System.currentTimeMillis()}".toByteArray())
            .toHexString().take(16)

        val metadata = FileMetadata(
            fileId = fileId,
            filename = file.name,
            fileSize = fileSize,
            chunkCount = chunkCount,
            sha256Hash = digest.digest().toHexString(),
            burnAfterRead = burnAfterRead,
            expirySeconds = expirySeconds
        )

        outgoing[fileId] = FileTransferState(metadata, startedAt = System.currentTimeMillis())
        return metadata
    }

    fun getChunk(filepath: String, fileId: String, chunkIndex: Int, encryptionKey: ByteArray): ByteArray? {
        val state = outgoing[fileId] ?: return null
        if (state.cancelled) return null

        val file = File(filepath)
        if (!file.exists()) return null

        return try {
            val raf = RandomAccessFile(file, "r")
            raf.seek(chunkIndex.toLong() * CHUNK_SIZE)
            val buf = ByteArray(CHUNK_SIZE)
            val read = raf.read(buf)
            raf.close()
            if (read <= 0) return null
            val data = buf.copyOf(read)

            // AAD: file_id(16 bytes padded) + chunk_index(4 LE)
            val aad = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .put(fileId.toByteArray(Charsets.UTF_8).copyOf(16))
                .putInt(chunkIndex)
                .array()

            val encrypted = CryptoEngine.encryptAead(encryptionKey, data, aad)
            state.chunksSent = chunkIndex + 1
            onProgress?.invoke(fileId, (state.chunksSent * 100) / state.metadata.chunkCount)
            encrypted
        } catch (e: Exception) { null }
    }

    fun startReceive(metadata: FileMetadata): Boolean {
        if (metadata.fileSize > MAX_FILE_SIZE) return false
        val tempPath = File(transfersDir, "${metadata.fileId}.tmp")
        val state = FileTransferState(metadata, tempPath = tempPath.absolutePath, startedAt = System.currentTimeMillis())
        incoming[metadata.fileId] = state

        // Pre-allocate
        RandomAccessFile(tempPath, "rw").use { it.setLength(metadata.fileSize) }
        return true
    }

    fun receiveChunk(fileId: String, chunkIndex: Int, encryptedData: ByteArray, encryptionKey: ByteArray): Boolean {
        val state = incoming[fileId] ?: return false
        if (state.cancelled) return false

        val aad = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(fileId.toByteArray(Charsets.UTF_8).copyOf(16))
            .putInt(chunkIndex)
            .array()

        val data = CryptoEngine.decryptAead(encryptionKey, encryptedData, aad) ?: run {
            onError?.invoke(fileId, "Chunk decryption failed")
            return false
        }

        RandomAccessFile(File(state.tempPath), "rw").use { raf ->
            raf.seek(chunkIndex.toLong() * CHUNK_SIZE)
            raf.write(data)
        }

        state.chunksReceived = chunkIndex + 1
        onProgress?.invoke(fileId, (state.chunksReceived * 100) / state.metadata.chunkCount)

        if (state.chunksReceived >= state.metadata.chunkCount) {
            return finalizeReceive(fileId)
        }
        return true
    }

    private fun finalizeReceive(fileId: String): Boolean {
        val state = incoming[fileId] ?: return false
        val tempFile = File(state.tempPath)

        // Verify SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        tempFile.inputStream().use { input ->
            val buf = ByteArray(CHUNK_SIZE)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }

        if (digest.digest().toHexString() != state.metadata.sha256Hash) {
            onError?.invoke(fileId, "File integrity check failed")
            tempFile.delete()
            return false
        }

        var finalFile = File(transfersDir, state.metadata.filename)
        var counter = 1
        while (finalFile.exists()) {
            val stem = state.metadata.filename.substringBeforeLast(".")
            val ext = state.metadata.filename.substringAfterLast(".", "")
            finalFile = File(transfersDir, "${stem}_${counter}${if (ext.isNotEmpty()) ".$ext" else ""}")
            counter++
        }

        tempFile.renameTo(finalFile)
        state.complete = true
        onComplete?.invoke(fileId, finalFile.absolutePath)
        return true
    }

    fun cancelTransfer(fileId: String) {
        listOf(incoming, outgoing).forEach { store ->
            store[fileId]?.let {
                it.cancelled = true
                if (it.tempPath.isNotEmpty()) File(it.tempPath).delete()
            }
        }
    }
}
