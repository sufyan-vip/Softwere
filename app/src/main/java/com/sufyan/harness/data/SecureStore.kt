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
    fun maskedApiKey(): String? = mask(apiKey())

    // ---- §30: GitHub credentials -------------------------------------------
    // Same encrypted store, same masking rules. The token is never logged, never placed in a
    // terminal command line, and never included in anything sent to the AI provider.

    fun githubToken(): String? = prefs.getString(KEY_GITHUB, null)?.takeIf { it.isNotBlank() }

    fun setGithubToken(value: String) {
        prefs.edit().putString(KEY_GITHUB, value.trim()).apply()
    }

    fun clearGithubToken() {
        prefs.edit().remove(KEY_GITHUB).remove(KEY_GITHUB_LOGIN).apply()
    }

    fun hasGithubToken(): Boolean = githubToken() != null

    fun maskedGithubToken(): String? = mask(githubToken())

    /** The login the token was last verified against — not a secret, but it belongs with the token. */
    var githubLogin: String?
        get() = prefs.getString(KEY_GITHUB_LOGIN, null)
        set(v) = prefs.edit().putString(KEY_GITHUB_LOGIN, v).apply()

    private fun mask(value: String?): String? {
        val k = value ?: return null
        if (k.length <= 12) return "\u2022".repeat(k.length)
        return k.take(6) + "\u2022".repeat(8) + k.takeLast(4)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_OPENROUTER = "openrouter_api_key"
        const val KEY_GITHUB = "github_token"
        const val KEY_GITHUB_LOGIN = "github_login"
    }
}
