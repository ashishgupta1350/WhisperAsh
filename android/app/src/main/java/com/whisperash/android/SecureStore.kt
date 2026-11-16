package com.whisperash.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (Groq key, Gemini key, sync session token) encrypted with an AES-256-GCM key that
 * lives in the Android Keystore and never leaves it. Only the ciphertext is stored, in a
 * private SharedPreferences file that is excluded from backups (allowBackup=false).
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val cache = HashMap<String, String?>()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun get(name: String): String? {
        if (cache.containsKey(name)) return cache[name]
        val stored = prefs.getString(name, null)
        val value = stored?.let {
            try {
                val bytes = Base64.decode(it, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
        cache[name] = value
        return value
    }

    @Synchronized
    fun put(name: String, value: String?) {
        cache.remove(name)
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        cache[name] = value
    }

    fun has(name: String) = !get(name).isNullOrEmpty()

    companion object {
        private const val ALIAS = "whisperash_secrets_v1"
        const val GROQ_KEY = "groq_api_key"
        const val GEMINI_KEY = "gemini_api_key"
        const val SYNC_TOKEN = "sync_token"
    }
}
