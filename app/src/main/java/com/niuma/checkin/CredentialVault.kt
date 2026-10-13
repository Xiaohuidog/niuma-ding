package com.niuma.checkin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext goes to app-private preferences; the database stores an opaque reference. */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("mail-vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(secret: ByteArray): String {
        require(secret.isNotEmpty())
        val ref = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(ref.toByteArray(Charsets.UTF_8)) }
        val encrypted = cipher.doFinal(secret)
        check(prefs.edit().putString(ref, encode(cipher.iv) + ":" + encode(encrypted)).commit()) { "安全配置保存失败" }
        return ref
    }
    fun read(ref: String): ByteArray {
        val parts = requireNotNull(prefs.getString(ref, null)) { "发信认证不可用，请重新配置" }.split(':')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(parts[0])))
            updateAAD(ref.toByteArray(Charsets.UTF_8))
        }
        return cipher.doFinal(decode(parts[1]))
    }
    fun remove(ref: String) { check(prefs.edit().remove(ref).commit()) { "安全配置删除失败" } }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
    companion object { private val KEY_LOCK = Any(); private const val ALIAS = "niuma.mail.v1" }
}
