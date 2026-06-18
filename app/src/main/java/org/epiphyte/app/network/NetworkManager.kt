package org.epiphyte.app.network

import kotlinx.coroutines.*
import org.epiphyte.app.crypto.toHexString
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.zip.CRC32

/**
 * P2P networking over Tor - wire-compatible with Python NetworkManager.
 * Length-prefixed framing with CRC32 integrity check.
 * Frame: length(4, BE) + checksum(4, BE) + data
 */

data class PeerInfo(
    val onionAddress: String,
    var displayName: String = "",
    var connected: Boolean = false,
    var socket: Socket? = null,
    var lastHeartbeat: Long = 0L,
    var reconnectAttempts: Int = 0,
    var autoReconnect: Boolean = true
)

data class QueuedMessage(
    val peerOnion: String,
    val data: ByteArray,
    val timestamp: Long,
    var attempts: Int = 0,
    val maxAttempts: Int = 10
)

class NetworkManager(private val torManager: TorManager) {
    companion object {
        const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024 // 16MB
        const val HEARTBEAT_INTERVAL = 30_000L
        const val HEARTBEAT_TIMEOUT = 90_000L
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val RECONNECT_BASE_DELAY = 5_000L
    }

    val peers = ConcurrentHashMap<String, PeerInfo>()
    private val messageQueue = ConcurrentLinkedDeque<QueuedMessage>()
    private var listenerSocket: java.net.ServerSocket? = null
    private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onPeerConnected: ((String) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    var onDataReceived: ((String, ByteArray) -> Unit)? = null

    fun start() {
        running = true
        scope.launch { acceptLoop() }
        scope.launch { heartbeatLoop() }
        scope.launch { reconnectLoop() }
        scope.launch { queueCleanupLoop() }
    }

    fun stop() {
        running = false
        scope.cancel()
        peers.values.forEach { it.socket?.runCatching { close() } }
        peers.clear()
        listenerSocket?.runCatching { close() }
    }

    fun connectToPeer(onionAddress: String): Boolean {
        if (peers[onionAddress]?.connected == true) return true

        val sock = torManager.connectToOnion(onionAddress, 80) ?: return false

        val peer = peers.getOrPut(onionAddress) { PeerInfo(onionAddress) }
        peer.socket = sock
        peer.connected = true
        peer.lastHeartbeat = System.currentTimeMillis()
        peer.reconnectAttempts = 0

        scope.launch { peerReceiveLoop(onionAddress) }
        onPeerConnected?.invoke(onionAddress)
        flushQueueForPeer(onionAddress)
        return true
    }

    fun sendToPeer(onionAddress: String, data: ByteArray): Boolean {
        val peer = peers[onionAddress]
        if (peer == null || !peer.connected || peer.socket == null) {
            enqueueMessage(onionAddress, data)
            return false
        }
        val success = sendFramed(peer.socket!!, data)
        if (!success) {
            enqueueMessage(onionAddress, data)
            handlePeerDisconnect(onionAddress)
        }
        return success
    }

    fun sendToPeerNoQueue(onionAddress: String, data: ByteArray): Boolean {
        val peer = peers[onionAddress]
        if (peer == null || !peer.connected || peer.socket == null) return false
        val success = sendFramed(peer.socket!!, data)
        if (!success) handlePeerDisconnect(onionAddress)
        return success
    }

    fun isPeerConnected(onionAddress: String): Boolean =
        peers[onionAddress]?.connected == true

    fun sendHello(onionAddress: String, ourOnion: String): Boolean {
        val hello = JSONObject().apply {
            put("type", "hello")
            put("onion", ourOnion)
            put("version", "1.0")
        }.toString().toByteArray(Charsets.UTF_8)
        return sendToPeerNoQueue(onionAddress, hello)
    }

    // --- Framing (wire-compatible with Python version) ---

    private fun sendFramed(sock: Socket, data: ByteArray): Boolean {
        return try {
            val crc = CRC32()
            crc.update(data)
            val checksum = crc.value.toInt()
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(data.size)
                .putInt(checksum)
                .array()
            val out = sock.getOutputStream()
            synchronized(out) {
                out.write(header)
                out.write(data)
                out.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun recvFramed(sock: Socket, timeout: Long = 30_000L): ByteArray? {
        return try {
            sock.soTimeout = timeout.toInt()
            val input = sock.getInputStream()

            val header = readExactly(input, 8) ?: return null
            val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val length = buf.getInt()
            val expectedChecksum = buf.getInt()

            if (length > MAX_MESSAGE_SIZE || length < 0) return null

            val data = readExactly(input, length) ?: return null

            val crc = CRC32()
            crc.update(data)
            if (crc.value.toInt() != expectedChecksum) return null

            data
        } catch (e: Exception) {
            null
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read == -1) return null
            offset += read
        }
        return buffer
    }

    // --- Internal loops ---

    private suspend fun acceptLoop() {
        try {
            listenerSocket = java.net.ServerSocket()
            listenerSocket!!.reuseAddress = true
            listenerSocket!!.bind(InetSocketAddress("127.0.0.1", torManager.hiddenServicePort))
            listenerSocket!!.soTimeout = 1000

            while (running) {
                try {
                    val client = listenerSocket!!.accept()
                    scope.launch { handleIncoming(client) }
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                }
            }
        } catch (e: Exception) {
            // Port binding failed, try any port
            try {
                listenerSocket = java.net.ServerSocket(0)
                torManager.hiddenServicePort = listenerSocket!!.localPort
                listenerSocket!!.soTimeout = 1000
                while (running) {
                    try {
                        val client = listenerSocket!!.accept()
                        scope.launch { handleIncoming(client) }
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                }
            } catch (e2: Exception) { /* give up */ }
        }
    }

    private fun handleIncoming(sock: Socket) {
        val data = recvFramed(sock, 30_000L) ?: run { sock.close(); return }

        try {
            val msg = JSONObject(String(data, Charsets.UTF_8))
            if (msg.optString("type") != "hello" || !msg.has("onion")) {
                sock.close(); return
            }
            val peerOnion = msg.getString("onion")

            val peer = peers.getOrPut(peerOnion) { PeerInfo(peerOnion) }
            peer.socket = sock
            peer.connected = true
            peer.lastHeartbeat = System.currentTimeMillis()
            peer.reconnectAttempts = 0

            onPeerConnected?.invoke(peerOnion)
            flushQueueForPeer(peerOnion)
            peerReceiveLoop(peerOnion)
        } catch (e: Exception) {
            sock.close()
        }
    }

    private fun peerReceiveLoop(onionAddress: String) {
        while (running) {
            val peer = peers[onionAddress] ?: break
            if (!peer.connected || peer.socket == null) break

            val data = recvFramed(peer.socket!!, HEARTBEAT_TIMEOUT)
            if (data == null) {
                if (System.currentTimeMillis() - peer.lastHeartbeat > HEARTBEAT_TIMEOUT * 2) {
                    break
                }
                continue
            }

            peer.lastHeartbeat = System.currentTimeMillis()

            if (isControlMessage(data)) {
                handleControlMessage(onionAddress, data)
                continue
            }

            try {
                onDataReceived?.invoke(onionAddress, data)
            } catch (e: Exception) { /* ignore handler errors */ }
        }
        handlePeerDisconnect(onionAddress)
    }

    private fun isControlMessage(data: ByteArray): Boolean {
        return try {
            val obj = JSONObject(String(data, Charsets.UTF_8))
            obj.optString("type") in listOf("ping", "pong", "heartbeat")
        } catch (e: Exception) { false }
    }

    private fun handleControlMessage(peerOnion: String, data: ByteArray) {
        try {
            val obj = JSONObject(String(data, Charsets.UTF_8))
            if (obj.optString("type") in listOf("ping", "heartbeat")) {
                val pong = JSONObject().put("type", "pong").put("ts", System.currentTimeMillis() / 1000.0)
                sendToPeerNoQueue(peerOnion, pong.toString().toByteArray())
            }
        } catch (e: Exception) { }
    }

    private suspend fun heartbeatLoop() {
        while (running) {
            delay(HEARTBEAT_INTERVAL)
            if (!running) break
            val connectedPeers = peers.filter { it.value.connected }.keys.toList()
            for (addr in connectedPeers) {
                val hb = JSONObject().put("type", "heartbeat").put("ts", System.currentTimeMillis() / 1000.0)
                sendToPeerNoQueue(addr, hb.toString().toByteArray())
            }
        }
    }

    private suspend fun reconnectLoop() {
        while (running) {
            delay(10_000L)
            if (!running) break
            val disconnected = peers.filter { !it.value.connected && it.value.autoReconnect && it.value.reconnectAttempts < MAX_RECONNECT_ATTEMPTS }
            for ((addr, peer) in disconnected) {
                val delay = RECONNECT_BASE_DELAY * (1L shl peer.reconnectAttempts)
                if (System.currentTimeMillis() - peer.lastHeartbeat < delay) continue
                peer.reconnectAttempts++
                if (connectToPeer(addr)) {
                    sendHello(addr, torManager.onionAddress)
                }
            }
        }
    }

    private suspend fun queueCleanupLoop() {
        while (running) {
            delay(60_000L)
            val now = System.currentTimeMillis()
            messageQueue.removeAll { now - it.timestamp > 86_400_000 || it.attempts >= it.maxAttempts }
        }
    }

    private fun enqueueMessage(peerOnion: String, data: ByteArray) {
        messageQueue.add(QueuedMessage(peerOnion, data, System.currentTimeMillis()))
        if (messageQueue.size > 1000) messageQueue.pollFirst()
    }

    private fun flushQueueForPeer(peerOnion: String) {
        val toSend = messageQueue.filter { it.peerOnion == peerOnion }
        messageQueue.removeAll { it.peerOnion == peerOnion }
        for (msg in toSend) {
            if (!sendToPeerNoQueue(peerOnion, msg.data)) {
                msg.attempts++
                if (msg.attempts < msg.maxAttempts) messageQueue.add(msg)
                break
            }
        }
    }

    private fun handlePeerDisconnect(onionAddress: String) {
        val peer = peers[onionAddress] ?: return
        peer.socket?.runCatching { close() }
        peer.connected = false
        peer.socket = null
        onPeerDisconnected?.invoke(onionAddress)
    }
}
