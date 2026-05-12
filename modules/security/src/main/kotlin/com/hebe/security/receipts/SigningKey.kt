package com.hebe.security.receipts

import com.hebe.config.SecretStoreProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.spec.NamedParameterSpec

private const val ED25519_KEY_SIZE = 32

object SigningKey {
    private const val PRIVATE_KEY_NAME = "receipts.signing_key"

    init {
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }

    suspend fun bootstrap(secretStore: SecretStoreProvider): Ed25519PrivateKey {
        val existing = secretStore.get(PRIVATE_KEY_NAME)
        if (existing != null) {
            return Ed25519PrivateKey.load(existing)
        }

        val privateKey = Ed25519PrivateKey.generate()
        secretStore.set(PRIVATE_KEY_NAME, privateKey.encode())
        return privateKey
    }

    suspend fun load(secretStore: SecretStoreProvider): Ed25519PrivateKey? {
        val bytes = secretStore.get(PRIVATE_KEY_NAME) ?: return null
        return try {
            Ed25519PrivateKey.load(bytes)
        } catch (e: Exception) {
            null
        }
    }
}

class Ed25519PrivateKey(
    private val seed: ByteArray,
) {
    init {
        require(seed.size == ED25519_KEY_SIZE) { "Ed25519 seed must be 32 bytes" }
    }

    fun sign(message: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("EdDSA", "BC")
        val privateKey = keyFactory.generatePrivate(
            java.security.spec.EdECPrivateKeySpec(NamedParameterSpec("Ed25519"), seed)
        )
        val signature = java.security.Signature.getInstance("EdDSA", "BC")
        signature.initSign(privateKey)
        signature.update(message)
        return signature.sign()
    }

    fun publicKeyBytes(): ByteArray {
        val keyGen = KeyPairGenerator.getInstance("EdDSA", "BC")
        keyGen.initialize(NamedParameterSpec("Ed25519"))
        return keyGen.generateKeyPair().public.encoded
    }

    fun encode(): ByteArray = seed.copyOf()

    companion object {
        fun generate(): Ed25519PrivateKey {
            val seed = ByteArray(ED25519_KEY_SIZE)
            SecureRandom().nextBytes(seed)
            return Ed25519PrivateKey(seed)
        }

        fun load(bytes: ByteArray): Ed25519PrivateKey {
            require(bytes.size == ED25519_KEY_SIZE) { "Ed25519 seed must be 32 bytes" }
            return Ed25519PrivateKey(bytes.copyOf())
        }
    }
}

object Ed25519Verifier {
    fun verify(publicKeyBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("EdDSA", "BC")
            val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
            val sig = java.security.Signature.getInstance("EdDSA", "BC")
            sig.initVerify(publicKey)
            sig.update(message)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
}
