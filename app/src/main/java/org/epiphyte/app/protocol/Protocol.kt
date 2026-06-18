package org.epiphyte.app.protocol

import org.epiphyte.app.crypto.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol - 100% compatible with the Python desktop version.
 * Format: version(1) + type(1) + msg_id(8) + timestamp(8) + payload_len(4) + payload + sig_len(2) + sig
 */

enum class MsgType(val value: Int) {
    HELLO(1),
    KEY_EXCHANGE(2),
    KEY_EXCHANGE_REPLY(3),
    TEXT(10),
    FILE_META(11),
    FILE_CHUNK(12),
    ACK(20),
    DELIVERY_RECEIPT(21),
    READ_RECEIPT(22),
    PING(30),
    PONG(31),
    SESSION_RESET(40);

    companion object {
        fun fromValue(value: Int): MsgType? = entries.find { it.value == value }
    }
}

data class ProtocolMessage(
    val msgType: Int,
    val payload: ByteArray,
    val timestamp: Double,
    val msgId: Long,
    val signature: ByteArray = byteArrayOf()
)

fun serializeMessage(msg: ProtocolMessage): ByteArray {
    val buf = ByteBuffer.allocate(22 + msg.payload.size + 2 + msg.signature.size)
        .order(ByteOrder.LITTLE_ENDIAN)
    buf.put(1) // protocol version
    buf.put(msg.msgType.toByte())
    buf.putLong(msg.msgId)
    buf.putDouble(msg.timestamp)
    buf.putInt(msg.payload.size)
    buf.put(msg.payload)
    buf.putShort(msg.signature.size.toShort())
    buf.put(msg.signature)
    return buf.array()
}

fun deserializeMessage(data: ByteArray): ProtocolMessage? {
    try {
        if (data.size < 22) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get().toInt() and 0xFF

        if (version != 1) return deserializeLegacy(data)

        val msgType = buf.get().toInt() and 0xFF
        val msgId = buf.getLong()
        val timestamp = buf.getDouble()
        val payloadLen = buf.getInt()

        if (buf.remaining() < payloadLen) return null
        val payload = ByteArray(payloadLen)
        buf.get(payload)

        if (buf.remaining() < 2) return null
        val sigLen = buf.getShort().toInt() and 0xFFFF

        val signature = if (sigLen > 0 && buf.remaining() >= sigLen) {
            ByteArray(sigLen).also { buf.get(it) }
        } else byteArrayOf()

        return ProtocolMessage(msgType, payload, timestamp, msgId, signature)
    } catch (e: Exception) {
        return null
    }
}

private fun deserializeLegacy(data: ByteArray): ProtocolMessage? {
    try {
        if (data.size < 21) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val msgType = buf.get().toInt() and 0xFF
        val msgId = buf.getLong()
        val timestamp = buf.getFloat().toDouble()
        val payloadLen = buf.getInt()

        if (buf.remaining() < payloadLen) return null
        val payload = ByteArray(payloadLen)
        buf.get(payload)

        if (buf.remaining() < 2) return null
        val sigLen = buf.getShort().toInt() and 0xFFFF
        val signature = if (sigLen > 0 && buf.remaining() >= sigLen) {
            ByteArray(sigLen).also { buf.get(it) }
        } else byteArrayOf()

        return ProtocolMessage(msgType, payload, timestamp, msgId, signature)
    } catch (e: Exception) {
        return null
    }
}

/**
 * Encrypted session with a peer - handles X3DH key exchange and Double Ratchet messaging.
 * Wire-compatible with the Python Session class.
 */
class Session(
    private val identity: CryptoEngine.Identity,
    val peerOnion: String
) {
    var peerVerifyKey: ByteArray? = null
    var peerDhPublic: ByteArray = byteArrayOf()
    var peerFingerprint: String = ""
    var ratchet: DoubleRatchet? = null
    var established: Boolean = false
    private var msgCounter: Long = 0
    private var ourEphemeralPrivate: ByteArray = byteArrayOf()
    private var ourEphemeralPublic: ByteArray = byteArrayOf()

    fun createKeyExchangeInit(): ByteArray {
        val ephemeral = CryptoEngine.generateDH()
        ourEphemeralPrivate = ephemeral.first
        ourEphemeralPublic = ephemeral.second

        val payload = JSONObject().apply {
            put("verify_key", identity.verifyKey.toHexString())
            put("dh_public", identity.dhPublic.toHexString())
            put("ephemeral_public", ourEphemeralPublic.toHexString())
            put("protocol_version", 1)
        }

        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val signature = CryptoEngine.sign(identity.signingKey, payloadBytes)

        val msg = ProtocolMessage(
            msgType = MsgType.KEY_EXCHANGE.value,
            payload = payloadBytes,
            timestamp = System.currentTimeMillis() / 1000.0,
            msgId = nextId(),
            signature = signature
        )

        return serializeMessage(msg)
    }

    fun handleKeyExchangeInit(data: ByteArray): ByteArray? {
        val msg = deserializeMessage(data) ?: return null
        if (msg.msgType != MsgType.KEY_EXCHANGE.value) return null

        try {
            val payload = JSONObject(String(msg.payload, Charsets.UTF_8))
            val peerVk = payload.getString("verify_key").hexToByteArray()

            // Verify signature
            if (!CryptoEngine.verify(peerVk, msg.payload, msg.signature)) return null

            peerVerifyKey = peerVk
            peerDhPublic = payload.getString("dh_public").hexToByteArray()
            val peerEphemeral = payload.getString("ephemeral_public").hexToByteArray()

            peerFingerprint = CryptoEngine.computeFingerprint(peerVk, peerDhPublic)

            // Generate our ephemeral
            val ephemeral = CryptoEngine.generateDH()
            ourEphemeralPrivate = ephemeral.first
            ourEphemeralPublic = ephemeral.second

            // Triple DH (X3DH)
            val dh1 = CryptoEngine.dh(identity.dhPrivate, peerEphemeral)
            val dh2 = CryptoEngine.dh(ourEphemeralPrivate, peerDhPublic)
            val dh3 = CryptoEngine.dh(ourEphemeralPrivate, peerEphemeral)

            val combined = dh1 + dh2 + dh3
            val sharedSecret = CryptoEngine.hkdfDerive(combined, "Epiphyte-X3DH".toByteArray(), "session-key".toByteArray())

            // Init ratchet as receiver
            val ratchetKeypair = CryptoEngine.generateDH()
            ratchet = DoubleRatchet().apply {
                initReceiver(sharedSecret, ratchetKeypair)
            }

            // Build reply
            val replyPayload = JSONObject().apply {
                put("verify_key", identity.verifyKey.toHexString())
                put("dh_public", identity.dhPublic.toHexString())
                put("ephemeral_public", ourEphemeralPublic.toHexString())
                put("ratchet_public", ratchetKeypair.second.toHexString())
                put("protocol_version", 1)
            }

            val replyBytes = replyPayload.toString().toByteArray(Charsets.UTF_8)
            val replySignature = CryptoEngine.sign(identity.signingKey, replyBytes)

            val replyMsg = ProtocolMessage(
                msgType = MsgType.KEY_EXCHANGE_REPLY.value,
                payload = replyBytes,
                timestamp = System.currentTimeMillis() / 1000.0,
                msgId = nextId(),
                signature = replySignature
            )

            established = true
            return serializeMessage(replyMsg)
        } catch (e: Exception) {
            return null
        }
    }

    fun handleKeyExchangeReply(data: ByteArray): Boolean {
        val msg = deserializeMessage(data) ?: return false
        if (msg.msgType != MsgType.KEY_EXCHANGE_REPLY.value) return false

        try {
            val payload = JSONObject(String(msg.payload, Charsets.UTF_8))
            val peerVk = payload.getString("verify_key").hexToByteArray()

            if (!CryptoEngine.verify(peerVk, msg.payload, msg.signature)) return false

            peerVerifyKey = peerVk
            peerDhPublic = payload.getString("dh_public").hexToByteArray()
            val peerEphemeral = payload.getString("ephemeral_public").hexToByteArray()
            val peerRatchetPub = payload.getString("ratchet_public").hexToByteArray()

            peerFingerprint = CryptoEngine.computeFingerprint(peerVk, peerDhPublic)

            // DH (initiator order is swapped)
            val dh1 = CryptoEngine.dh(ourEphemeralPrivate, peerDhPublic)
            val dh2 = CryptoEngine.dh(identity.dhPrivate, peerEphemeral)
            val dh3 = CryptoEngine.dh(ourEphemeralPrivate, peerEphemeral)

            val combined = dh1 + dh2 + dh3
            val sharedSecret = CryptoEngine.hkdfDerive(combined, "Epiphyte-X3DH".toByteArray(), "session-key".toByteArray())

            ratchet = DoubleRatchet().apply {
                initSender(sharedSecret, peerRatchetPub)
            }

            established = true
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun encryptMessage(text: String): ByteArray? {
        if (!established || ratchet == null) return null

        val msgId = nextId()
        val payload = text.toByteArray(Charsets.UTF_8)
        val encrypted = ratchet!!.encrypt(payload)

        val msg = ProtocolMessage(
            msgType = MsgType.TEXT.value,
            payload = encrypted,
            timestamp = System.currentTimeMillis() / 1000.0,
            msgId = msgId
        )

        return serializeMessage(msg)
    }

    fun decryptMessage(data: ByteArray): String? {
        if (!established || ratchet == null) return null
        val msg = deserializeMessage(data) ?: return null
        if (msg.msgType != MsgType.TEXT.value) return null

        val plaintext = ratchet!!.decrypt(msg.payload) ?: return null
        return String(plaintext, Charsets.UTF_8)
    }

    fun exportState(): Map<String, Any> {
        val state = mutableMapOf<String, Any>(
            "peer_onion" to peerOnion,
            "established" to established,
            "msg_counter" to msgCounter,
            "peer_fingerprint" to peerFingerprint
        )
        peerVerifyKey?.let { state["peer_verify_key"] = it.toHexString() }
        if (peerDhPublic.isNotEmpty()) state["peer_dh_public"] = peerDhPublic.toHexString()
        ratchet?.let { state["ratchet"] = it.exportState() }
        return state
    }

    @Suppress("UNCHECKED_CAST")
    fun importState(state: Map<String, Any>): Boolean {
        try {
            established = state["established"] as? Boolean ?: false
            msgCounter = (state["msg_counter"] as? Number)?.toLong() ?: 0
            peerFingerprint = state["peer_fingerprint"] as? String ?: ""

            (state["peer_verify_key"] as? String)?.let {
                peerVerifyKey = it.hexToByteArray()
            }
            (state["peer_dh_public"] as? String)?.let {
                peerDhPublic = it.hexToByteArray()
            }
            (state["ratchet"] as? Map<String, Any>)?.let {
                ratchet = DoubleRatchet().apply { importState(it) }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun nextId(): Long {
        msgCounter++
        return msgCounter
    }
}
