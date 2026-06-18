package org.epiphyte.app.crypto

import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto engine compatible with the Python desktop version.
 * Pure Bouncy Castle implementation.
 * X25519 DH, Ed25519 signing, ChaCha20-Poly1305 AEAD, HKDF-SHA256.
 */
object CryptoEngine {
    private val random = SecureRandom()

    data class Identity(
        val signingKey: ByteArray, // Ed25519 private seed (32 bytes)
        val verifyKey: ByteArray,  // Ed25519 public key (32 bytes)
        val dhPrivate: ByteArray,  // X25519 private (32 bytes)
        val dhPublic: ByteArray    // X25519 public (32 bytes)
    ) {
        fun getFingerprint(): String {
            val raw = verifyKey + dhPublic
            val hash = sha256(raw)
            val hex = hash.toHexString().uppercase()
            return hex.take(32).chunked(4).joinToString(" ")
        }
    }

    fun generateIdentity(): Identity {
        val edPriv = Ed25519PrivateKeyParameters(random)
        val edPub = edPriv.generatePublicKey()

        val xPriv = X25519PrivateKeyParameters(random)
        val xPub = xPriv.generatePublicKey()

        return Identity(
            signingKey = edPriv.encoded,
            verifyKey = edPub.encoded,
            dhPrivate = xPriv.encoded,
            dhPublic = xPub.encoded
        )
    }

    fun generateDH(): Pair<ByteArray, ByteArray> {
        val xPriv = X25519PrivateKeyParameters(random)
        val xPub = xPriv.generatePublicKey()
        return Pair(xPriv.encoded, xPub.encoded)
    }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val xPriv = X25519PrivateKeyParameters(privateKey, 0)
        val xPub = X25519PublicKeyParameters(publicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(xPriv)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(xPub, shared, 0)
        return shared
    }

    fun sign(signingKey: ByteArray, message: ByteArray): ByteArray {
        val privKey = Ed25519PrivateKeyParameters(signingKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(verifyKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val pubKey = Ed25519PublicKeyParameters(verifyKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubKey)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    fun hkdfDerive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }

    /**
     * ChaCha20-Poly1305 AEAD encryption via Bouncy Castle.
     * Returns: nonce(12) + ciphertext + tag(16)
     * Fully compatible with Python's ChaCha20Poly1305.
     */
    fun encryptAead(key: ByteArray, plaintext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        val nonce = randomBytes(12)
        val cipher = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, nonce, aad)
        cipher.init(true, params)

        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += cipher.doFinal(output, len)

        return nonce + output.copyOf(len)
    }

    /**
     * ChaCha20-Poly1305 AEAD decryption.
     * Input: nonce(12) + ciphertext + tag(16)
     */
    fun decryptAead(key: ByteArray, data: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray? {
        if (data.size < 12 + 16) return null
        val nonce = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)

        return try {
            val cipher = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
            val params = AEADParameters(KeyParameter(key), 128, nonce, aad)
            cipher.init(false, params)

            val output = ByteArray(cipher.getOutputSize(ct.size))
            var len = cipher.processBytes(ct, 0, ct.size, output, 0)
            len += cipher.doFinal(output, len)
            output.copyOf(len)
        } catch (e: Exception) {
            null
        }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun computeFingerprint(verifyKeyBytes: ByteArray, dhPublic: ByteArray): String {
        val raw = verifyKeyBytes + dhPublic
        val hash = sha256(raw).toHexString().uppercase()
        return hash.take(32).chunked(4).joinToString(" ")
    }

    fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    /**
     * Derive key from passphrase using scrypt.
     * Uses n=2^14=16384 on Android (lower than desktop's 2^17 to avoid OOM).
     * Still secure for mobile use - 16384 is Signal's default.
     * 
     * NOTE: For cross-platform compatibility with desktop (n=2^17),
     * we try n=2^17 first and fall back to n=2^14 if OOM occurs.
     */
    fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        return try {
            // Try desktop-compatible parameters first: n=2^17, r=8, p=1
            SCrypt.generate(passBytes, salt, 131072, 8, 1, 32)
        } catch (e: OutOfMemoryError) {
            // Fallback for low-memory devices: n=2^14, r=8, p=1
            System.gc()
            SCrypt.generate(passBytes, salt, 16384, 8, 1, 32)
        }
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
