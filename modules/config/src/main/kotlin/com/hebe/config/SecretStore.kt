package com.hebe.config

import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.logging.Level
import java.util.logging.Logger
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

interface SecretStoreProvider {
    suspend fun get(key: String): ByteArray?

    suspend fun set(
        key: String,
        value: ByteArray,
    )

    suspend fun delete(key: String): Boolean
}

class OsKeychainSecretStore : SecretStoreProvider {
    private val logger = Logger.getLogger(OsKeychainSecretStore::class.java.name)
    private val keyStore: KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    private val secretPrefix = "hebe.secret."

    private companion object {
        const val KEY_SIZE = 256
    }

    override suspend fun get(key: String): ByteArray? =
        try {
            val entry = keyStore.getEntry(secretPrefix + key, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey?.encoded
        } catch (e: KeyStoreException) {
            logger.log(Level.WARNING, "Failed to get secret: $key", e)
            null
        }

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        try {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(KEY_SIZE, SecureRandom())
            val secretKey = keyGen.generateKey()
            keyStore.setEntry(secretPrefix + key, KeyStore.SecretKeyEntry(secretKey), null)
        } catch (e: KeyStoreException) {
            logger.log(Level.SEVERE, "Failed to set secret: $key", e)
        }
    }

    override suspend fun delete(key: String): Boolean =
        try {
            keyStore.deleteEntry(secretPrefix + key)
            true
        } catch (e: KeyStoreException) {
            logger.log(Level.WARNING, "Failed to delete secret: $key", e)
            false
        }
}

object AeadEncryptor {
    private const val KEY_SIZE_BYTES = 32
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val ALGORITHM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val spec = javax.crypto.spec.GCMParameterSpec(TAG_SIZE_BITS, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance(ALGORITHM)
        val iv = ciphertext.sliceArray(0 until IV_SIZE_BYTES)
        val encrypted = ciphertext.sliceArray(IV_SIZE_BYTES until ciphertext.size)
        val spec = javax.crypto.spec.GCMParameterSpec(TAG_SIZE_BITS, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encrypted)
    }
}
