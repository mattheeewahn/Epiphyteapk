package org.epiphyte.app.storage

import android.content.Context
import org.epiphyte.app.crypto.CryptoEngine
import org.epiphyte.app.crypto.toHexString
import org.epiphyte.app.crypto.hexToByteArray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Encrypted local storage - compatible with Python EncryptedStorage.
 * All data encrypted with passphrase-derived key (scrypt + ChaCha20-Poly1305).
 */

data class Contact(
    val onionAddress: String,
    var displayName: String,
    var verifyKeyHex: String = "",
    var dhPublicHex: String = "",
    var fingerprint: String = "",
    var addedAt: Double = 0.0,
    var lastSeen: Double = 0.0,
    var verified: Boolean = false,
    var blocked: Boolean = false,
    var notes: String = "",
    var status: String = "offline"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("onion_address", onionAddress)
        put("display_name", displayName)
        put("verify_key_hex", verifyKeyHex)
        put("dh_public_hex", dhPublicHex)
        put("fingerprint", fingerprint)
        put("added_at", addedAt)
        put("last_seen", lastSeen)
        put("verified", verified)
        put("blocked", blocked)
        put("notes", notes)
    }

    companion object {
        fun fromJson(obj: JSONObject): Contact = Contact(
            onionAddress = obj.getString("onion_address"),
            displayName = obj.optString("display_name", ""),
            verifyKeyHex = obj.optString("verify_key_hex", ""),
            dhPublicHex = obj.optString("dh_public_hex", ""),
            fingerprint = obj.optString("fingerprint", ""),
            addedAt = obj.optDouble("added_at", 0.0),
            lastSeen = obj.optDouble("last_seen", 0.0),
            verified = obj.optBoolean("verified", false),
            blocked = obj.optBoolean("blocked", false),
            notes = obj.optString("notes", "")
        )
    }
}

data class StoredMessage(
    val msgId: Long,
    val sender: String,
    val text: String,
    val timestamp: Double,
    val isOurs: Boolean,
    val delivered: Boolean = true,
    val read: Boolean = false,
    val msgType: String = "text"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("msg_id", msgId)
        put("sender", sender)
        put("text", text)
        put("timestamp", timestamp)
        put("is_ours", isOurs)
        put("delivered", delivered)
        put("read", read)
        put("msg_type", msgType)
    }

    companion object {
        fun fromJson(obj: JSONObject): StoredMessage = StoredMessage(
            msgId = obj.getLong("msg_id"),
            sender = obj.getString("sender"),
            text = obj.getString("text"),
            timestamp = obj.getDouble("timestamp"),
            isOurs = obj.getBoolean("is_ours"),
            delivered = obj.optBoolean("delivered", true),
            read = obj.optBoolean("read", false),
            msgType = obj.optString("msg_type", "text")
        )
    }
}

class EncryptedStorage(private val context: Context) {
    private val storeDir: File = File(context.filesDir, "store")
    private var key: ByteArray? = null
    private var opened = false

    fun open(passphrase: String): Boolean {
        storeDir.mkdirs()

        val saltFile = File(storeDir, "salt")
        val verifyFile = File(storeDir, "verify")

        val salt = if (saltFile.exists()) {
            saltFile.readBytes()
        } else {
            val s = CryptoEngine.randomBytes(16)
            saltFile.writeBytes(s)
            s
        }

        try {
            key = CryptoEngine.deriveKey(passphrase, salt)
        } catch (e: Exception) {
            return false
        }

        if (verifyFile.exists()) {
            val encrypted = verifyFile.readBytes()
            val decrypted = decrypt(encrypted)
            if (decrypted == null || String(decrypted, Charsets.UTF_8) != "EPIPHYTE_OK") {
                key = null
                return false
            }
        } else {
            val encrypted = encrypt("EPIPHYTE_OK".toByteArray(Charsets.UTF_8))
            verifyFile.writeBytes(encrypted)
        }

        opened = true
        return true
    }

    fun isOpen(): Boolean = opened

    fun close() {
        key = null
        opened = false
    }

    // --- Identity ---
    fun saveIdentity(data: Map<String, String>) {
        put("identity", JSONObject(data).toString())
    }

    fun loadIdentity(): Map<String, String>? {
        val json = get("identity") ?: return null
        val obj = JSONObject(json)
        val map = mutableMapOf<String, String>()
        for (k in obj.keys()) map[k] = obj.getString(k)
        return map
    }

    // --- Contacts ---
    fun saveContact(contact: Contact) {
        val contacts = loadContacts().toMutableList()
        val idx = contacts.indexOfFirst { it.onionAddress == contact.onionAddress }
        if (idx >= 0) contacts[idx] = contact else contacts.add(contact)
        val arr = JSONArray()
        contacts.forEach { arr.put(it.toJson()) }
        put("contacts", arr.toString())
    }

    fun loadContacts(): List<Contact> {
        val json = get("contacts") ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { Contact.fromJson(arr.getJSONObject(it)) }
    }

    fun removeContact(onionAddress: String) {
        val contacts = loadContacts().filter { it.onionAddress != onionAddress }
        val arr = JSONArray()
        contacts.forEach { arr.put(it.toJson()) }
        put("contacts", arr.toString())
    }

    // --- Messages ---
    fun saveMessage(peerOnion: String, message: StoredMessage) {
        val key = "messages_${peerOnion.take(16)}"
        val existing = getMessages(key)
        val messages = existing.toMutableList()
        messages.add(message)
        if (messages.size > 5000) {
            val trimmed = messages.takeLast(5000)
            messages.clear()
            messages.addAll(trimmed)
        }
        val arr = JSONArray()
        messages.forEach { arr.put(it.toJson()) }
        put(key, arr.toString())
    }

    fun loadMessages(peerOnion: String, limit: Int = 200): List<StoredMessage> {
        val key = "messages_${peerOnion.take(16)}"
        return getMessages(key).takeLast(limit)
    }

    fun deleteMessages(peerOnion: String) {
        val key = "messages_${peerOnion.take(16)}"
        put(key, "[]")
    }

    private fun getMessages(key: String): List<StoredMessage> {
        val json = get(key) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { StoredMessage.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    // --- Sessions ---
    fun saveSession(peerOnion: String, state: Map<String, Any>) {
        put("session_${peerOnion.take(16)}", JSONObject(state).toString())
    }

    @Suppress("UNCHECKED_CAST")
    fun loadSession(peerOnion: String): Map<String, Any>? {
        val json = get("session_${peerOnion.take(16)}") ?: return null
        return try {
            jsonToMap(JSONObject(json))
        } catch (e: Exception) { null }
    }

    fun deleteSession(peerOnion: String) {
        val filename = safeFilename("session_${peerOnion.take(16)}")
        File(storeDir, "$filename.enc").delete()
    }

    // --- Settings ---
    fun saveSettings(settings: Map<String, Any>) {
        put("settings", JSONObject(settings).toString())
    }

    fun loadSettings(): Map<String, Any> {
        val json = get("settings") ?: return mapOf(
            "use_bridges" to true,
            "transport" to "obfs4",
            "theme" to "dark",
            "notifications" to true,
            "auto_connect" to true
        )
        return jsonToMap(JSONObject(json))
    }

    // --- Internal crypto ---
    private fun encrypt(data: ByteArray): ByteArray {
        val k = key ?: throw IllegalStateException("Storage not opened")
        return CryptoEngine.encryptAead(k, data)
    }

    private fun decrypt(data: ByteArray): ByteArray? {
        val k = key ?: return null
        return CryptoEngine.decryptAead(k, data)
    }

    private fun put(key: String, value: String) {
        if (this.key == null) return
        val data = value.toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(data)
        val filename = safeFilename(key)
        val tmpFile = File(storeDir, "$filename.tmp")
        val file = File(storeDir, "$filename.enc")
        tmpFile.writeBytes(encrypted)
        tmpFile.renameTo(file)
    }

    private fun get(key: String): String? {
        if (this.key == null) return null
        val filename = safeFilename(key)
        val file = File(storeDir, "$filename.enc")
        if (!file.exists()) return null
        val encrypted = file.readBytes()
        val decrypted = decrypt(encrypted) ?: return null
        return String(decrypted, Charsets.UTF_8)
    }

    private fun safeFilename(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray()).toHexString().take(32)
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (k in obj.keys()) {
            val v = obj.get(k)
            map[k] = when (v) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> jsonArrayToList(v)
                else -> v
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any> {
        return (0 until arr.length()).map { arr.get(it) }
    }

    /** Wipe all storage securely */
    fun wipeAll() {
        storeDir.listFiles()?.forEach { file ->
            // Overwrite with random data before deleting
            try {
                val size = file.length().toInt()
                if (size > 0) {
                    file.writeBytes(CryptoEngine.randomBytes(size))
                }
            } catch (e: Exception) { }
            file.delete()
        }
        key = null
        opened = false
    }
}
