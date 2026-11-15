package com.livespace.sensor.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API 23 AndroidKeyStore AES/GCM; preferences contain only ciphertext and a random nonce. Backups disabled. */
class DeviceTokenStore(context: Context,storageName: String="device_credentials",private val alias: String="livespace.device-token.v1") {
    private val prefs=context.getSharedPreferences(storageName,Context.MODE_PRIVATE)
    @Synchronized private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun save(token: String) {
        require(CredentialSafety.valid(token)) { "Invalid token format" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("cipher",Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit()) { "Credential storage failed" }
    }
    @Synchronized fun load(): String? {
        val ciphertext=prefs.getString("cipher",null) ?: return null
        val iv=prefs.getString("iv",null) ?: error("Credential nonce missing; enter token again")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ciphertext,Base64.NO_WRAP)),Charsets.UTF_8)
    }
    fun present()=prefs.contains("cipher")
    fun clear() { check(prefs.edit().clear().commit()) { "Credential removal failed" } }
}
