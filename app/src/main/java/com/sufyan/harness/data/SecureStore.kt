package com.sufyan.harness.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore backed credential storage. The OpenRouter API key is only
 * ever persisted encrypted at rest and is never logged or displayed in full.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "harness_credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun apiKey(): String? = prefs.getString(KEY_OPENROUTER, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_OPENROUTER, value.trim()).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_OPENROUTER).apply()
    }

    fun hasApiKey(): Boolean = apiKey() != null

    /** Never returns more than the first/last few characters. */
    fun maskedApiKey(): String? {
        val k = apiKey() ?: return null
        if (k.length <= 12) return "•".repeat(k.length)
        return k.take(6) + "•".repeat(8) + k.takeLast(4)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_OPENROUTER = "openrouter_api_key"
    }
}
