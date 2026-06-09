package com.safemesh.android.proto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal crypto needed for THIS mockup:
 *
 *   - sha256
 *   - X25519 identity keypair generation
 *   - public_id derivation (first 4 bytes of SHA-256(identity_pub) as u32 BE)
 *
 * Private-DM crypto (PASSKEY wrap, X25519 KEX, AEAD session keys) is intentionally
 * left as a TODO for this mockup — `SafeMeshNode` ignores incoming PVT packets
 * gracefully instead of crashing. The full crypto stack is in the Rust
 * `crypto.rs` and can be ported when DM becomes a demo goal.
 */
object Crypto {
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n); rng.nextBytes(b); return b
    }

    fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    /** Same derivation as Rust `crypto::public_id_from_identity`. */
    fun publicIdFromIdentity(identityPub: ByteArray): Int {
        require(identityPub.size == 32) { "identity_pub must be 32 bytes" }
        val h = sha256(identityPub)
        return ByteBuffer.wrap(h, 0, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    /** (privateKey32, publicKey32) — X25519, same shape as Rust `crypto::x25519_keypair`. */
    fun x25519Keypair(): Pair<ByteArray, ByteArray> {
        val sk = X25519PrivateKeyParameters(rng)
        val pk = sk.generatePublicKey()
        return sk.encoded to (pk as X25519PublicKeyParameters).encoded
    }

    fun validatePasskey(passkey: String): Boolean {
        val b = passkey.toByteArray(Charsets.UTF_8)
        if (b.isEmpty() || b.size > 24) return false
        return b.all { (it >= 'a'.code.toByte() && it <= 'z'.code.toByte()) ||
                       (it >= 'A'.code.toByte() && it <= 'Z'.code.toByte()) ||
                       (it >= '0'.code.toByte() && it <= '9'.code.toByte()) ||
                       it == ' '.code.toByte() }
    }

    // ===================================================================== DM crypto (v3)
    // Byte-for-byte mirror of Rust crypto.rs: X25519 DH, HKDF(extract+expand),
    // passkey-free v3 root derivation, per-message session keys, AEAD. Keeping
    // these identical is what lets Android <-> Linux DMs interoperate.

    /** X25519 Diffie-Hellman. Same as Rust `crypto::x25519`. */
    fun x25519(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        require(privateKey.size == 32 && peerPublic.size == 32)
        val agree = X25519Agreement()
        agree.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(agree.agreementSize)
        agree.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        return out
    }

    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    /** HKDF (RFC 5869, extract-then-expand). Matches Rust `crypto::hkdf_expand`. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)               // extract
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < len) {                    // expand
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(len)
    }

    private fun intBE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    /** v3 DM root: NO passkey; bound to both static identity keys + ephemerals. Mirror of Rust `derive_dm_root`. */
    fun deriveDmRoot(
        shared: ByteArray,
        initiatorPublicId: Int, responderPublicId: Int,
        initiatorIdentityPub: ByteArray, responderIdentityPub: ByteArray,
        initiatorEphPub: ByteArray, responderEphPub: ByteArray
    ): ByteArray {
        val m = ByteArrayOutputStream()
        m.write("SafeMesh/v3/dm-root/salt".toByteArray(Charsets.US_ASCII))
        m.write(intBE(initiatorPublicId)); m.write(intBE(responderPublicId))
        m.write(initiatorIdentityPub); m.write(responderIdentityPub)
        m.write(initiatorEphPub); m.write(responderEphPub)
        val salt = sha256(m.toByteArray())
        return hkdf(salt, shared, "SafeMesh/v3/private-dm/root".toByteArray(Charsets.US_ASCII), 32)
    }

    /** Per-message key from root + nonce. Matches Rust `derive_session_key`. */
    fun deriveSessionKey(root: ByteArray, label: ByteArray, seqMaterial: ByteArray): ByteArray =
        hkdf("SafeMesh/v2/session-key".toByteArray(Charsets.US_ASCII), root, label + seqMaterial, 32)

    /** AEAD negotiation, mirror of Rust `util::choose_alg`. */
    fun chooseAlg(aFlags: Int, bFlags: Int): Byte =
        if ((aFlags and Constants.LOCAL_FLAG_AES_GCM) != 0 && (bFlags and Constants.LOCAL_FLAG_AES_GCM) != 0)
            Constants.AEAD_AES_256_GCM else Constants.AEAD_CHACHA20_POLY1305

    fun aeadEncrypt(alg: Byte, key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = aeadCipher(alg, Cipher.ENCRYPT_MODE, key, nonce)
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)              // ciphertext || 16-byte tag
    }

    fun aeadDecrypt(alg: Byte, key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = aeadCipher(alg, Cipher.DECRYPT_MODE, key, nonce)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun aeadCipher(alg: Byte, mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        when (alg) {
            Constants.AEAD_AES_256_GCM -> Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            }
            Constants.AEAD_CHACHA20_POLY1305 -> Cipher.getInstance("ChaCha20-Poly1305").apply {
                init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            }
            else -> throw IllegalArgumentException("unsupported AEAD alg $alg")
        }
}
