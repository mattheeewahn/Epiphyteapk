package org.epiphyte.app.stealth

import android.content.Context
import org.epiphyte.app.crypto.CryptoEngine
import org.epiphyte.app.crypto.toHexString
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Stealth features - Panic Wipe, Disappearing Messages, Decoy Mode.
 * Compatible with Python stealth module.
 */

// ============================================================
// 1. PANIC WIPE
// ============================================================

class PanicWipe(private val context: Context) {
    private val panicHashFile: File get() = File(context.filesDir, ".panic")

    fun setPanicPassphrase(passphrase: String) {
        val salt = CryptoEngine.randomBytes(16)
        val hash = scryptHash(passphrase, salt)
        panicHashFile.writeBytes(salt + hash)
    }

    fun checkPanic(passphrase: String): Boolean {
        if (!panicHashFile.exists()) return false
        val data = panicHashFile.readBytes()
        if (data.size < 48) return false
        val salt = data.copyOfRange(0, 16)
        val storedHash = data.copyOfRange(16, 48)
        val hash = scryptHash(passphrase, salt)
        return hash.contentEquals(storedHash)
    }

    fun executeWipe() {
        val targets = listOf("store", "tor_data", "hidden_service", "transfers", "backups")
        for (target in targets) {
            val dir = File(context.filesDir, target)
            if (dir.exists()) secureWipeDir(dir)
        }
        // Remove config files
        context.filesDir.listFiles()?.filter { it.isFile }?.forEach { secureWipeFile(it) }
    }

    private fun secureWipeFile(file: File) {
        try {
            val size = file.length().toInt()
            if (size > 0) {
                // 3-pass overwrite
                file.writeBytes(ByteArray(size)) // zeros
                file.writeBytes(ByteArray(size) { 0xFF.toByte() }) // ones
                file.writeBytes(CryptoEngine.randomBytes(size)) // random
            }
            file.delete()
        } catch (e: Exception) {
            file.delete()
        }
    }

    private fun secureWipeDir(dir: File) {
        dir.walkBottomUp().forEach { f ->
            if (f.isFile) secureWipeFile(f)
            else if (f.isDirectory && f != dir) f.delete()
        }
        dir.delete()
    }

    private fun scryptHash(passphrase: String, salt: ByteArray): ByteArray {
        // Using Bouncy Castle SCrypt: n=2^14, r=8, p=1, dkLen=32
        return org.bouncycastle.crypto.generators.SCrypt.generate(
            passphrase.toByteArray(Charsets.UTF_8),
            salt, 16384, 8, 1, 32
        )
    }
}

// ============================================================
// 2. DISAPPEARING MESSAGES
// ============================================================

data class DisappearingConfig(
    val enabled: Boolean = false,
    val seconds: Int = 300,
    val afterRead: Boolean = true
)

class DisappearingManager {
    private val configs = ConcurrentHashMap<String, DisappearingConfig>()
    private val timers = ConcurrentHashMap<Long, Long>() // msgId -> destroyAt

    fun setConfig(peerOnion: String, enabled: Boolean, seconds: Int, afterRead: Boolean = true) {
        configs[peerOnion] = DisappearingConfig(enabled, seconds, afterRead)
    }

    fun getConfig(peerOnion: String): DisappearingConfig =
        configs[peerOnion] ?: DisappearingConfig()

    fun scheduleDestruction(msgId: Long, peerOnion: String) {
        val config = getConfig(peerOnion)
        if (!config.enabled) return
        timers[msgId] = System.currentTimeMillis() + config.seconds * 1000L
    }

    fun getExpiredMessages(): List<Long> {
        val now = System.currentTimeMillis()
        val expired = timers.filter { now >= it.value }.keys.toList()
        expired.forEach { timers.remove(it) }
        return expired
    }

    fun exportState(): Map<String, Any> = mapOf(
        "configs" to configs.mapValues { mapOf("enabled" to it.value.enabled, "seconds" to it.value.seconds, "after_read" to it.value.afterRead) },
        "timers" to timers.toMap()
    )

    @Suppress("UNCHECKED_CAST")
    fun importState(state: Map<String, Any>) {
        (state["configs"] as? Map<String, Map<String, Any>>)?.forEach { (k, v) ->
            configs[k] = DisappearingConfig(
                enabled = v["enabled"] as? Boolean ?: false,
                seconds = (v["seconds"] as? Number)?.toInt() ?: 300,
                afterRead = v["after_read"] as? Boolean ?: true
            )
        }
        (state["timers"] as? Map<String, Number>)?.forEach { (k, v) ->
            timers[k.toLong()] = v.toLong()
        }
    }
}

// ============================================================
// 3. DECOY MODE
// ============================================================

class DecoyMode(private val context: Context) {
    private val decoyHashFile: File get() = File(context.filesDir, ".decoy")

    fun setupDecoy(decoyPassphrase: String) {
        File(context.filesDir, "decoy_store").mkdirs()
        val salt = CryptoEngine.randomBytes(16)
        val hash = scryptHash(decoyPassphrase, salt)
        decoyHashFile.writeBytes(salt + hash)
    }

    fun isDecoyPassphrase(passphrase: String): Boolean {
        if (!decoyHashFile.exists()) return false
        val data = decoyHashFile.readBytes()
        if (data.size < 48) return false
        val salt = data.copyOfRange(0, 16)
        val storedHash = data.copyOfRange(16, 48)
        val hash = scryptHash(passphrase, salt)
        return hash.contentEquals(storedHash)
    }

    fun hasDecoy(): Boolean = decoyHashFile.exists()

    private fun scryptHash(passphrase: String, salt: ByteArray): ByteArray {
        return org.bouncycastle.crypto.generators.SCrypt.generate(
            passphrase.toByteArray(Charsets.UTF_8),
            salt, 16384, 8, 1, 32
        )
    }
}

// ============================================================
// 4. TRAFFIC PADDING
// ============================================================

class TrafficPadder(private val sendFunc: ((String, ByteArray) -> Unit)? = null) {
    private var running = false
    private val peers = mutableSetOf<String>()
    private var thread: Thread? = null
    private var interval = 5000L

    fun start(peerSet: Set<String>, intervalMs: Long = 5000L) {
        peers.addAll(peerSet)
        interval = intervalMs
        running = true
        thread = Thread {
            while (running) {
                Thread.sleep(interval)
                if (!running) break
                for (peer in peers.toList()) {
                    val padLen = (Math.random() * 256).toInt() + 32
                    val padding = byteArrayOf(0x00) + CryptoEngine.randomBytes(padLen)
                    try { sendFunc?.invoke(peer, padding) } catch (e: Exception) { }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false }
    fun addPeer(onion: String) { peers.add(onion) }
    fun removePeer(onion: String) { peers.remove(onion) }
}
