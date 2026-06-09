package com.safemesh.android.proto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistent SafeMesh identity: 32-byte X25519 private + public key. public_id
 * is derived deterministically from the public key. The long-lived private key
 * is stored in the app's internal files dir wrapped by an Android Keystore
 * AES-GCM key. Legacy plaintext files are migrated on first load.
 */
class Identity private constructor(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    val publicId: Int = Crypto.publicIdFromIdentity(publicKey)
    val publicIdHex: String = "0x%08x".format(publicId)

    companion object {
        private const val TAG = "SM/Identity"
        private const val FILE = "safemesh-identity.json"
        private const val FORMAT_V2 = "SMID2"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_KEY_ALIAS = "safemesh.identity.wrap.v1"
        private const val GCM_TAG_BITS = 128

        fun loadOrCreate(ctx: Context): Identity {
            val file = File(ctx.filesDir, FILE)
            if (file.exists()) {
                readStored(file)?.let { return it }
            }
            val (priv, pub) = Crypto.x25519Keypair()
            writeStored(file, priv, pub)
            return Identity(priv, pub)
        }

        internal fun ephemeral(): Identity {
            val (priv, pub) = Crypto.x25519Keypair()
            return Identity(priv, pub)
        }

        fun reset(ctx: Context) {
            File(ctx.filesDir, FILE).delete()
            deleteWrappingKey()
        }

        private fun readStored(file: File): Identity? {
            val encoded = runCatching { file.readText().trim() }.getOrNull()
                ?: return null
            if (encoded.isBlank()) return null

            readEncrypted(encoded)?.let { return it }
            readLegacy(encoded)?.let { legacy ->
                writeStored(file, legacy.privateKey, legacy.publicKey)
                return legacy
            }

            Log.w(TAG, "stored identity is unreadable; generating a new one")
            file.delete()
            deleteWrappingKey()
            return null
        }

        private fun readLegacy(encoded: String): Identity? {
            val raw = encoded.split("|")
            if (raw.size != 2) return null
            val priv = decodeB64(raw[0]) ?: return null
            val pub = decodeB64(raw[1]) ?: return null
            return if (priv.size == 32 && pub.size == 32) Identity(priv, pub) else null
        }

        private fun readEncrypted(encoded: String): Identity? {
            val raw = encoded.split("|")
            if (raw.size != 4 || raw[0] != FORMAT_V2) return null
            val pub = decodeB64(raw[1]) ?: return null
            val iv = decodeB64(raw[2]) ?: return null
            val ciphertext = decodeB64(raw[3]) ?: return null
            if (pub.size != 32 || iv.size != 12 || ciphertext.isEmpty()) return null

            val priv = runCatching { decryptPrivateKey(pub, iv, ciphertext) }
                .onFailure {
                    Log.w(TAG, "failed to decrypt stored identity; resetting identity", it)
                }
                .getOrNull()
                ?: return null
            return if (priv.size == 32) Identity(priv, pub) else null
        }

        private fun writeStored(file: File, privateKey: ByteArray, publicKey: ByteArray) {
            require(privateKey.size == 32 && publicKey.size == 32)
            val encrypted = runCatching { encodeEncrypted(privateKey, publicKey) }
                .onFailure {
                    Log.w(TAG, "Android Keystore wrapping failed; falling back to legacy identity storage", it)
                }
                .getOrNull()
            file.writeText(encrypted ?: encodeLegacy(privateKey, publicKey))
        }

        private fun encodeLegacy(privateKey: ByteArray, publicKey: ByteArray): String =
            encodeB64(privateKey) + "|" + encodeB64(publicKey)

        private fun encodeEncrypted(privateKey: ByteArray, publicKey: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            cipher.updateAAD(identityAad(publicKey))
            val ciphertext = cipher.doFinal(privateKey)
            return listOf(
                FORMAT_V2,
                encodeB64(publicKey),
                encodeB64(cipher.iv),
                encodeB64(ciphertext)
            ).joinToString("|")
        }

        private fun decryptPrivateKey(publicKey: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(identityAad(publicKey))
            return cipher.doFinal(ciphertext)
        }

        private fun identityAad(publicKey: ByteArray): ByteArray =
            FORMAT_V2.toByteArray(Charsets.US_ASCII) + publicKey

        private fun getOrCreateWrappingKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return generateWrappingKey(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        }

        private fun generateWrappingKey(strongBox: Boolean): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            return try {
                generator.init(builder.build())
                generator.generateKey()
            } catch (e: Exception) {
                if (!strongBox) throw e
                Log.i(TAG, "StrongBox wrapping key generation failed; using regular Android Keystore", e)
                generateWrappingKey(strongBox = false)
            }
        }

        private fun deleteWrappingKey() {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    .deleteEntry(WRAP_KEY_ALIAS)
            }.onFailure {
                Log.w(TAG, "failed to delete identity wrapping key", it)
            }
        }

        private fun encodeB64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)

        private fun decodeB64(value: String): ByteArray? =
            runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()
    }
}
