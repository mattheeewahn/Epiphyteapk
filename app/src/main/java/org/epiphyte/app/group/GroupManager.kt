package org.epiphyte.app.group

import org.epiphyte.app.crypto.CryptoEngine
import org.epiphyte.app.crypto.toHexString
import org.epiphyte.app.crypto.hexToByteArray
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Encrypted group chat with Sender Keys protocol.
 * Wire-compatible with Python GroupManager.
 */

data class GroupMember(
    val onionAddress: String,
    var displayName: String,
    var role: String = "member",
    var joinedAt: Double = 0.0,
    var senderKey: String = ""
)

data class GroupInfo(
    val groupId: String,
    var name: String,
    val createdAt: Double,
    val creator: String,
    val members: MutableList<GroupMember> = mutableListOf(),
    var ourSenderKey: String = "",
    var ourSenderChainIndex: Int = 0,
    var description: String = "",
    val maxMembers: Int = 50
)

data class GroupMessage(
    val msgId: Long,
    val groupId: String,
    val sender: String,
    val text: String,
    val timestamp: Double,
    val msgType: String = "text"
)

class SenderKeyState(key: ByteArray? = null) {
    var chainKey: ByteArray = key ?: CryptoEngine.randomBytes(32)
    var iteration: Int = 0

    fun nextMessageKey(): ByteArray {
        val msgKey = CryptoEngine.hmacSha256(chainKey, byteArrayOf(0x6D, 0x73, 0x67)) // "msg"
        chainKey = CryptoEngine.hmacSha256(chainKey, byteArrayOf(0x63, 0x68, 0x61, 0x69, 0x6E)) // "chain"
        iteration++
        return msgKey
    }

    fun exportState(): Map<String, Any> = mapOf(
        "chain_key" to chainKey.toHexString(),
        "iteration" to iteration
    )

    fun importState(state: Map<String, Any>) {
        chainKey = (state["chain_key"] as String).hexToByteArray()
        iteration = (state["iteration"] as Number).toInt()
    }
}

class GroupManager {
    val groups = mutableMapOf<String, GroupInfo>()
    private val senderKeys = mutableMapOf<String, SenderKeyState>()
    private val peerSenderKeys = mutableMapOf<String, MutableMap<String, SenderKeyState>>()
    private val messageHistory = mutableMapOf<String, MutableList<GroupMessage>>()

    fun createGroup(name: String, ourOnion: String, description: String = ""): GroupInfo {
        val raw = "$name$ourOnion${System.currentTimeMillis()}${CryptoEngine.randomBytes(8).toHexString()}"
        val groupId = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray()).toHexString().take(24)

        val senderKey = SenderKeyState()
        senderKeys[groupId] = senderKey

        val group = GroupInfo(
            groupId = groupId,
            name = name,
            createdAt = System.currentTimeMillis() / 1000.0,
            creator = ourOnion,
            members = mutableListOf(GroupMember(ourOnion, "You", "admin", System.currentTimeMillis() / 1000.0, senderKey.chainKey.toHexString())),
            ourSenderKey = senderKey.chainKey.toHexString(),
            description = description
        )

        groups[groupId] = group
        peerSenderKeys[groupId] = mutableMapOf()
        messageHistory[groupId] = mutableListOf()
        return group
    }

    fun addMember(groupId: String, memberOnion: String, displayName: String, ourOnion: String): JSONObject? {
        val group = groups[groupId] ?: return null
        if (group.members.none { it.onionAddress == ourOnion && it.role == "admin" }) return null
        if (group.members.any { it.onionAddress == memberOnion }) return null
        if (group.members.size >= group.maxMembers) return null

        group.members.add(GroupMember(memberOnion, displayName, "member", System.currentTimeMillis() / 1000.0))
        rotateSenderKey(groupId)

        return JSONObject().apply {
            put("type", "group_invite")
            put("group_id", groupId)
            put("name", group.name)
            put("description", group.description)
            put("creator", group.creator)
            put("members", JSONArray().apply {
                group.members.forEach { m ->
                    put(JSONObject().apply {
                        put("onion", m.onionAddress)
                        put("name", m.displayName)
                        put("role", m.role)
                    })
                }
            })
            put("sender_key", senderKeys[groupId]!!.chainKey.toHexString())
            put("sender_iteration", senderKeys[groupId]!!.iteration)
        }
    }

    fun handleInvite(inviteData: JSONObject, ourOnion: String): String? {
        val groupId = inviteData.optString("group_id") ?: return null
        val senderKey = SenderKeyState()
        senderKeys[groupId] = senderKey

        val membersArr = inviteData.optJSONArray("members") ?: JSONArray()
        val members = mutableListOf<GroupMember>()
        for (i in 0 until membersArr.length()) {
            val m = membersArr.getJSONObject(i)
            members.add(GroupMember(m.getString("onion"), m.getString("name"), m.getString("role"), System.currentTimeMillis() / 1000.0))
        }

        val group = GroupInfo(
            groupId = groupId,
            name = inviteData.optString("name", "Unknown"),
            createdAt = System.currentTimeMillis() / 1000.0,
            creator = inviteData.optString("creator", ""),
            members = members,
            ourSenderKey = senderKey.chainKey.toHexString(),
            description = inviteData.optString("description", "")
        )

        groups[groupId] = group
        peerSenderKeys[groupId] = mutableMapOf()
        messageHistory[groupId] = mutableListOf()

        inviteData.optString("sender_key", "").takeIf { it.isNotEmpty() }?.let { key ->
            val state = SenderKeyState(key.hexToByteArray())
            state.iteration = inviteData.optInt("sender_iteration", 0)
            peerSenderKeys[groupId]!![inviteData.optString("creator", "")] = state
        }

        return groupId
    }

    fun removeMember(groupId: String, memberOnion: String, ourOnion: String): Boolean {
        val group = groups[groupId] ?: return false
        if (group.members.none { it.onionAddress == ourOnion && it.role == "admin" }) return false
        group.members.removeAll { it.onionAddress == memberOnion }
        peerSenderKeys[groupId]?.remove(memberOnion)
        rotateSenderKey(groupId)
        return true
    }

    fun leaveGroup(groupId: String) {
        groups.remove(groupId)
        senderKeys.remove(groupId)
        peerSenderKeys.remove(groupId)
    }

    fun encryptGroupMessage(groupId: String, plaintext: String, ourOnion: String): ByteArray? {
        val senderState = senderKeys[groupId] ?: return null
        val msgKey = senderState.nextMessageKey()

        val payload = JSONObject().apply {
            put("sender", ourOnion)
            put("iteration", senderState.iteration)
            put("group_id", groupId)
        }
        val header = payload.toString().toByteArray(Charsets.UTF_8)
        val encryptedText = CryptoEngine.encryptAead(msgKey, plaintext.toByteArray(Charsets.UTF_8), header)

        val buf = ByteBuffer.allocate(4 + header.size + encryptedText.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(header.size)
        buf.put(header)
        buf.put(encryptedText)
        return buf.array()
    }

    fun decryptGroupMessage(groupId: String, data: ByteArray): GroupMessage? {
        try {
            if (data.size < 4) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val headerLen = buf.getInt()
            if (data.size < 4 + headerLen) return null

            val header = ByteArray(headerLen)
            buf.get(header)
            val encrypted = ByteArray(data.size - 4 - headerLen)
            buf.get(encrypted)

            val payload = JSONObject(String(header, Charsets.UTF_8))
            val sender = payload.getString("sender")
            val iteration = payload.getInt("iteration")

            val senderState = peerSenderKeys[groupId]?.get(sender) ?: return null

            while (senderState.iteration < iteration) {
                senderState.nextMessageKey()
            }
            val msgKey = senderState.nextMessageKey()

            val plaintext = CryptoEngine.decryptAead(msgKey, encrypted, header) ?: return null

            val msg = GroupMessage(
                msgId = System.currentTimeMillis(),
                groupId = groupId,
                sender = sender,
                text = String(plaintext, Charsets.UTF_8),
                timestamp = System.currentTimeMillis() / 1000.0
            )
            messageHistory.getOrPut(groupId) { mutableListOf() }.add(msg)
            return msg
        } catch (e: Exception) {
            return null
        }
    }

    fun updatePeerSenderKey(groupId: String, peerOnion: String, keyHex: String, iteration: Int = 0) {
        val state = SenderKeyState(keyHex.hexToByteArray())
        state.iteration = iteration
        peerSenderKeys.getOrPut(groupId) { mutableMapOf() }[peerOnion] = state
    }

    fun getMessages(groupId: String, limit: Int = 100): List<GroupMessage> =
        messageHistory[groupId]?.takeLast(limit) ?: emptyList()

    private fun rotateSenderKey(groupId: String) {
        val newKey = SenderKeyState()
        senderKeys[groupId] = newKey
        groups[groupId]?.let {
            it.ourSenderKey = newKey.chainKey.toHexString()
            it.ourSenderChainIndex = 0
        }
    }
}
