package org.epiphyte.app.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Double Ratchet protocol - wire-compatible with the Python desktop version.
 * Header format: dhPublic(32) + prevChainLen(4, LE) + msgNum(4, LE) = 40 bytes
 */
class DoubleRatchet {
    companion object {
        const val HEADER_SIZE = 40 // 32 + 4 + 4
        const val MAX_SKIP = 512
    }

    var rootKey: ByteArray = byteArrayOf()
    var sendChainKey: ByteArray = byteArrayOf()
    var recvChainKey: ByteArray = byteArrayOf()
    var sendMsgNum: Int = 0
    var recvMsgNum: Int = 0
    var prevChainLength: Int = 0
    var dhPrivate: ByteArray = byteArrayOf()
    var dhPublic: ByteArray = byteArrayOf()
    var peerDhPublic: ByteArray = byteArrayOf()
    val skippedKeys: MutableMap<Pair<String, Int>, ByteArray> = mutableMapOf()

    fun initSender(sharedSecret: ByteArray, peerDhPub: ByteArray) {
        peerDhPublic = peerDhPub
        val keypair = CryptoEngine.generateDH()
        dhPrivate = keypair.first
        dhPublic = keypair.second

        val dhOut = CryptoEngine.dh(dhPrivate, peerDhPublic)
        val derived = CryptoEngine.hkdfDerive(dhOut, sharedSecret, "EpiphyteRatchetInit".toByteArray(), 64)
        rootKey = derived.copyOfRange(0, 32)
        sendChainKey = derived.copyOfRange(32, 64)
        sendMsgNum = 0
        recvMsgNum = 0
        prevChainLength = 0
    }

    fun initReceiver(sharedSecret: ByteArray, dhKeypair: Pair<ByteArray, ByteArray>) {
        dhPrivate = dhKeypair.first
        dhPublic = dhKeypair.second
        rootKey = sharedSecret
        sendMsgNum = 0
        recvMsgNum = 0
        prevChainLength = 0
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val (newChain, msgKey) = kdfChain(sendChainKey)
        sendChainKey = newChain

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(dhPublic)
            .putInt(prevChainLength)
            .putInt(sendMsgNum)
            .array()
        sendMsgNum++

        val ct = CryptoEngine.encryptAead(msgKey, plaintext, header)
        return header + ct
    }

    fun decrypt(message: ByteArray): ByteArray? {
        if (message.size < HEADER_SIZE) return null

        val header = message.copyOfRange(0, HEADER_SIZE)
        val ct = message.copyOfRange(HEADER_SIZE, message.size)

        val peerPub = header.copyOfRange(0, 32)
        val buf = ByteBuffer.wrap(header, 32, 8).order(ByteOrder.LITTLE_ENDIAN)
        val prevChainLen = buf.getInt()
        val msgNum = buf.getInt()

        // Check skipped keys
        val keyId = Pair(peerPub.toHexString(), msgNum)
        skippedKeys.remove(keyId)?.let { mk ->
            return CryptoEngine.decryptAead(mk, ct, header)
        }

        // DH ratchet step if new peer public key
        if (!peerPub.contentEquals(peerDhPublic)) {
            skipMessages(prevChainLen)
            dhRatchet(peerPub)
        }

        // Skip ahead if needed
        while (recvMsgNum < msgNum) {
            val (newChain, mk) = kdfChain(recvChainKey)
            recvChainKey = newChain
            val skipId = Pair(peerDhPublic.toHexString(), recvMsgNum)
            skippedKeys[skipId] = mk
            recvMsgNum++
            pruneSkippedKeys()
        }

        // Derive message key
        val (newChain, msgKey) = kdfChain(recvChainKey)
        recvChainKey = newChain
        recvMsgNum++

        return CryptoEngine.decryptAead(msgKey, ct, header)
    }

    private fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val newChain = CryptoEngine.hmacSha256(chainKey, byteArrayOf(0x01))
        val msgKey = CryptoEngine.hmacSha256(chainKey, byteArrayOf(0x02))
        return Pair(newChain, msgKey)
    }

    private fun kdfRoot(dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = CryptoEngine.hkdfDerive(dhOutput, rootKey, "EpiphyteRatchet".toByteArray(), 64)
        return Pair(derived.copyOfRange(0, 32), derived.copyOfRange(32, 64))
    }

    private fun skipMessages(until: Int) {
        if (recvChainKey.isEmpty()) return
        val count = until - recvMsgNum
        for (i in 0 until minOf(count, MAX_SKIP)) {
            if (recvChainKey.isEmpty()) break
            val (newChain, mk) = kdfChain(recvChainKey)
            recvChainKey = newChain
            val skipId = Pair(peerDhPublic.toHexString(), recvMsgNum)
            skippedKeys[skipId] = mk
            recvMsgNum++
        }
        pruneSkippedKeys()
    }

    private fun dhRatchet(newPeerPub: ByteArray) {
        prevChainLength = sendMsgNum
        sendMsgNum = 0
        recvMsgNum = 0
        peerDhPublic = newPeerPub

        val dhRecv = CryptoEngine.dh(dhPrivate, peerDhPublic)
        val (newRoot1, newRecvChain) = kdfRoot(dhRecv)
        rootKey = newRoot1
        recvChainKey = newRecvChain

        val keypair = CryptoEngine.generateDH()
        dhPrivate = keypair.first
        dhPublic = keypair.second

        val dhSend = CryptoEngine.dh(dhPrivate, peerDhPublic)
        val (newRoot2, newSendChain) = kdfRoot(dhSend)
        rootKey = newRoot2
        sendChainKey = newSendChain
    }

    private fun pruneSkippedKeys() {
        while (skippedKeys.size > MAX_SKIP) {
            val oldest = skippedKeys.keys.first()
            skippedKeys.remove(oldest)
        }
    }

    fun exportState(): Map<String, Any> {
        val skipped = mutableMapOf<String, String>()
        for ((key, value) in skippedKeys) {
            skipped["${key.first}:${key.second}"] = value.toHexString()
        }
        return mapOf(
            "root_key" to rootKey.toHexString(),
            "send_chain_key" to sendChainKey.toHexString(),
            "recv_chain_key" to recvChainKey.toHexString(),
            "send_msg_num" to sendMsgNum,
            "recv_msg_num" to recvMsgNum,
            "prev_chain_length" to prevChainLength,
            "dh_private" to dhPrivate.toHexString(),
            "dh_public" to dhPublic.toHexString(),
            "peer_dh_public" to peerDhPublic.toHexString(),
            "skipped_keys" to skipped
        )
    }

    fun importState(state: Map<String, Any>) {
        rootKey = (state["root_key"] as String).hexToByteArray()
        sendChainKey = (state["send_chain_key"] as String).hexToByteArray()
        val recvHex = state["recv_chain_key"] as? String ?: ""
        recvChainKey = if (recvHex.isNotEmpty()) recvHex.hexToByteArray() else byteArrayOf()
        sendMsgNum = (state["send_msg_num"] as Number).toInt()
        recvMsgNum = (state["recv_msg_num"] as Number).toInt()
        prevChainLength = (state["prev_chain_length"] as Number).toInt()
        dhPrivate = (state["dh_private"] as String).hexToByteArray()
        dhPublic = (state["dh_public"] as String).hexToByteArray()
        val peerHex = state["peer_dh_public"] as? String ?: ""
        peerDhPublic = if (peerHex.isNotEmpty()) peerHex.hexToByteArray() else byteArrayOf()

        skippedKeys.clear()
        @Suppress("UNCHECKED_CAST")
        val skipped = state["skipped_keys"] as? Map<String, String> ?: emptyMap()
        for ((k, v) in skipped) {
            val parts = k.split(":")
            if (parts.size == 2) {
                skippedKeys[Pair(parts[0], parts[1].toInt())] = v.hexToByteArray()
            }
        }
    }
}
