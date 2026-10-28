package com.snapshelf.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultManager(private val root: File) {
    private val alias = "SnapShelfVaultKey"
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(false).build())
            }.generateKey()
        }

    fun encrypt(source: File, id: String): File {
        root.mkdirs()
        val target = File(root, "$id.bin")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        target.outputStream().use { out ->
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            source.inputStream().use { input -> javax.crypto.CipherOutputStream(out, cipher).use { encrypted -> input.copyTo(encrypted) } }
        }
        return target
    }

    fun decrypt(source: File, target: File) {
        source.inputStream().use { input ->
            val iv = ByteArray(input.read())
            if (input.read(iv) != iv.size) error("Vault file is incomplete")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> javax.crypto.CipherInputStream(input, cipher).use { encrypted -> encrypted.copyTo(out) } }
        }
    }
}
