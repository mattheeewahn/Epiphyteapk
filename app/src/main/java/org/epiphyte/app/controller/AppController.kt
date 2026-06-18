package org.epiphyte.app.controller

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.epiphyte.app.crypto.CryptoEngine
import org.epiphyte.app.crypto.toHexString
import org.epiphyte.app.crypto.hexToByteArray
import org.epiphyte.app.filetransfer.FileTransferManager
import org.epiphyte.app.group.GroupManager
import org.epiphyte.app.network.NetworkManager
import org.epiphyte.app.network.TorManager
import org.epiphyte.app.protocol.*
import org.epiphyte.app.stealth.*
import org.epiphyte.app.storage.*
import org.json.JSONObject

/**
 * Main app controller - identical logic to desktop app.py.
 * Manages identity, Tor, network, sessions, contacts.
 */

data class AppStatus(
    val text: String = "Offline",
    val connected: Boolean = false,
    val progress: Int = 0
)

class AppController(private val context: Context) {
    val torManager = TorManager()
    var networkManager: NetworkManager? = null
        private set
    val storage = EncryptedStorage(context)
    val groupManager = GroupManager()
    val fileTransferManager = FileTransferManager(context)
    val disappearingManager = DisappearingManager()
    val panicWipe = PanicWipe(context)
    val decoyMode = DecoyMode(context)

    var identity: CryptoEngine.Identity? = null
        private set
    val sessions = mutableMapOf<String, Session>()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val _status = MutableStateFlow(AppStatus())
    val status: StateFlow<AppStatus> = _status

    private val _messages = MutableStateFlow<Map<String, List<StoredMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<StoredMessage>>> = _messages

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var useBridges = true

    fun unlock(passphrase: String, isNew: Boolean, bridges: Boolean): Boolean {
        if (panicWipe.checkPanic(passphrase)) {
            panicWipe.executeWipe()
            return false
        }
        if (decoyMode.isDecoyPassphrase(passphrase)) {
            return false
        }
        if (isNew) {
            // Clear old data for fresh account
            java.io.File(context.filesDir, "store").deleteRecursively()
            java.io.File(context.filesDir, "hidden_service").deleteRecursively()
        }
        if (!storage.open(passphrase)) return false
        useBridges = bridges
        loadIdentity()
        loadContacts()
        loadSessions()
        return true
    }

    fun startTor() {
        torManager.init(context)
        scope.launch {
            torManager.setStatusCallback { msg, progress ->
                _status.value = AppStatus(msg, false, progress)
            }
            val success = torManager.start(useBridges = useBridges)
            if (success) {
                _status.value = AppStatus("Connected", true, 100)
                startNetwork()
            } else {
                _status.value = AppStatus("Failed", false, -1)
            }
        }
    }

    private fun startNetwork() {
        val nm = NetworkManager(torManager)
        networkManager = nm

        nm.onDataReceived = { peer, data -> handleDataReceived(peer, data) }
        nm.onPeerConnected = { peer -> handlePeerConnected(peer) }
        nm.onPeerDisconnected = { peer -> handlePeerDisconnected(peer) }
        nm.start()

        // Auto-connect to contacts
        scope.launch {
            delay(3000)
            for (c in _contacts.value) {
                if (!c.blocked) {
                    connectPeer(c.onionAddress)
                    delay(2000)
                }
            }
        }
    }

    fun addContact(address: String) {
        val addr = address.replace(".onion", "").trim()
        if (addr.length < 10) return
        if (_contacts.value.any { it.onionAddress == addr }) return

        val contact = Contact(onionAddress = addr, displayName = "${addr.take(12)}...", addedAt = System.currentTimeMillis() / 1000.0)
        storage.saveContact(contact)
        _contacts.value = _contacts.value + contact
        scope.launch { connectPeer(addr) }
    }

    fun connectPeer(onion: String) {
        val nm = networkManager ?: return
        if (nm.isPeerConnected(onion)) return
        val success = nm.connectToPeer(onion)
        if (success) {
            nm.sendHello(onion, torManager.onionAddress)
            initiateKex(onion)
        }
    }

    fun sendMessage(peer: String, text: String) {
        val session = sessions[peer]
        if (session == null || !session.established) {
            scope.launch { connectPeer(peer) }
            return
        }
        val encrypted = session.encryptMessage(text) ?: return
        networkManager?.sendToPeer(peer, encrypted)
        saveSession(peer)

        val msg = StoredMessage(
            msgId = System.currentTimeMillis(),
            sender = torManager.onionAddress,
            text = text,
            timestamp = System.currentTimeMillis() / 1000.0,
            isOurs = true
        )
        storage.saveMessage(peer, msg)
        refreshMessages(peer)
    }

    fun getMessages(peer: String): List<StoredMessage> = storage.loadMessages(peer)

    fun isPeerConnected(onion: String): Boolean = networkManager?.isPeerConnected(onion) ?: false

    fun shutdown() {
        sessions.keys.forEach { saveSession(it) }
        networkManager?.stop()
        torManager.stop()
        storage.close()
        scope.cancel()
    }

    // --- Internal ---

    private fun loadIdentity() {
        val saved = storage.loadIdentity()
        if (saved != null) {
            try {
                val signingKeyBytes = saved["signing_key"]!!.hexToByteArray()
                // Python stores the 32-byte Ed25519 seed
                // Bouncy Castle Ed25519PrivateKeyParameters also takes 32-byte seed
                val edPriv = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(signingKeyBytes, 0)
                val edPub = edPriv.generatePublicKey()

                identity = CryptoEngine.Identity(
                    signingKey = signingKeyBytes,
                    verifyKey = edPub.encoded,
                    dhPrivate = saved["dh_private"]!!.hexToByteArray(),
                    dhPublic = saved["dh_public"]!!.hexToByteArray()
                )
                return
            } catch (e: Exception) { /* regenerate */ }
        }
        identity = CryptoEngine.generateIdentity()
        storage.saveIdentity(mapOf(
            "signing_key" to identity!!.signingKey.toHexString(),
            "dh_private" to identity!!.dhPrivate.toHexString(),
            "dh_public" to identity!!.dhPublic.toHexString()
        ))
    }

    private fun loadContacts() {
        _contacts.value = storage.loadContacts()
    }

    private fun loadSessions() {
        for (c in _contacts.value) {
            if (c.blocked) continue
            val state = storage.loadSession(c.onionAddress) ?: continue
            try {
                val s = Session(identity!!, c.onionAddress)
                if (s.importState(state)) {
                    sessions[c.onionAddress] = s
                }
            } catch (e: Exception) { /* skip */ }
        }
    }

    private fun initiateKex(peer: String) {
        if (sessions[peer]?.established == true) return
        val s = Session(identity!!, peer)
        sessions[peer] = s
        networkManager?.sendToPeer(peer, s.createKeyExchangeInit())
    }

    private fun saveSession(peer: String) {
        sessions[peer]?.let { storage.saveSession(peer, it.exportState()) }
    }

    private fun handleDataReceived(peer: String, data: ByteArray) {
        if (data.isEmpty() || data[0] == 0x00.toByte()) return // padding

        val msg = deserializeMessage(data)
        if (msg == null) {
            tryJson(peer, data)
            return
        }

        when (msg.msgType) {
            MsgType.KEY_EXCHANGE.value -> handleKex(peer, data)
            MsgType.KEY_EXCHANGE_REPLY.value -> handleKexReply(peer, data)
            MsgType.TEXT.value -> handleText(peer, data)
            MsgType.PING.value -> {
                val pong = ProtocolMessage(MsgType.PONG.value, byteArrayOf(), System.currentTimeMillis() / 1000.0, 0)
                networkManager?.sendToPeerNoQueue(peer, serializeMessage(pong))
            }
        }
    }

    private fun handleKex(peer: String, data: ByteArray) {
        val s = Session(identity!!, peer)
        val reply = s.handleKeyExchangeInit(data)
        if (reply != null) {
            sessions[peer] = s
            saveSession(peer)
            networkManager?.sendToPeer(peer, reply)
            notifyStatus(peer, "connected")
        }
    }

    private fun handleKexReply(peer: String, data: ByteArray) {
        val s = sessions[peer] ?: return
        if (s.handleKeyExchangeReply(data)) {
            saveSession(peer)
            notifyStatus(peer, "connected")
        }
    }

    private fun handleText(peer: String, data: ByteArray) {
        val s = sessions[peer]
        if (s == null || !s.established) {
            initiateKex(peer)
            return
        }
        val text = s.decryptMessage(data)
        if (text != null) {
            saveSession(peer)
            val msg = StoredMessage(
                msgId = System.currentTimeMillis(),
                sender = peer,
                text = text,
                timestamp = System.currentTimeMillis() / 1000.0,
                isOurs = false
            )
            storage.saveMessage(peer, msg)
            refreshMessages(peer)
        }
    }

    private fun tryJson(peer: String, data: ByteArray) {
        try {
            val obj = JSONObject(String(data, Charsets.UTF_8))
            if (obj.optString("type") == "hello") {
                val real = obj.optString("onion", peer)
                notifyStatus(real, "connected")
                if (_contacts.value.none { it.onionAddress == real }) {
                    val c = Contact(onionAddress = real, displayName = "${real.take(12)}...", addedAt = System.currentTimeMillis() / 1000.0)
                    storage.saveContact(c)
                    _contacts.value = _contacts.value + c
                }
            }
        } catch (e: Exception) { }
    }

    private fun handlePeerConnected(peer: String) { notifyStatus(peer, "connected") }
    private fun handlePeerDisconnected(peer: String) { notifyStatus(peer, "offline") }

    private fun notifyStatus(peer: String, status: String) {
        _contacts.value = _contacts.value.map {
            if (it.onionAddress == peer) it.copy(status = status) else it
        }
    }

    private fun refreshMessages(peer: String) {
        val msgs = storage.loadMessages(peer)
        _messages.value = _messages.value + (peer to msgs)
    }
}
