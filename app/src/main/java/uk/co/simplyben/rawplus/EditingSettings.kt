package uk.co.simplyben.rawplus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Configuration only. Credentials are encrypted with a non-exportable Android Keystore key. */
class EditingSettings(context: Context) {
    // Preserve the previous preferences and key alias so existing backend installs migrate.
    private val preferences = context.getSharedPreferences("backend", Context.MODE_PRIVATE)

    // The old "provider" field remains the provider owning the encrypted cloud credential.
    // Choosing llama.cpp changes only "selected_provider", leaving that credential untouched.
    val savedProvider: EditingProvider
        get() = ProviderPolicy.savedProvider(preferences.getString("provider", null),
            preferences.getString("url", "").orEmpty(), preferences.contains("token"))

    val provider: EditingProvider
        get() = ProviderPolicy.selectedProvider(preferences.getString("selected_provider", null), savedProvider)

    val url: String
        get() = savedUrl(provider)

    val consent: Boolean get() = savedConsent(provider)

    fun savedUrl(provider: EditingProvider): String = when (provider) {
        EditingProvider.OPENAI -> EditingConfig.OPENAI_ENDPOINT
        EditingProvider.LLAMA -> ""
        EditingProvider.BACKEND -> if (savedProvider == provider) preferences.getString("url", "").orEmpty() else ""
    }

    fun savedConsent(provider: EditingProvider): Boolean = provider.external && provider == savedProvider &&
        preferences.getBoolean("consent", false)

    fun hasSavedCredential(provider: EditingProvider): Boolean = provider.external && provider == savedProvider &&
        !preferences.getString("token", "").isNullOrBlank() && !preferences.getString("iv", "").isNullOrBlank()

    /** Select a provider without moving, decrypting or deleting the saved cloud credential. */
    fun select(provider: EditingProvider) {
        check(preferences.edit().putString("selected_provider", provider.name).commit()) {
            "Could not save the selected editing provider."
        }
    }

    fun read(): EditingConfig? {
        val selected = provider
        if (selected == EditingProvider.LLAMA) return EditingConfig(selected, "", "")
        if (selected != savedProvider) return null
        return readSavedCloud()
    }

    private fun readSavedCloud(): EditingConfig? {
        val saved = savedProvider
        if (!savedConsent(saved)) return null
        return try {
            val endpoint = ProviderPolicy.endpoint(saved, savedUrl(saved))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)
            val encrypted = Base64.decode(preferences.getString("token", ""), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            val credential = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            ProviderPolicy.validateCredential(saved, credential)
            EditingConfig(saved, endpoint, credential)
        } catch (_: Exception) { null }
    }

    fun save(provider: EditingProvider, url: String, credential: String, consent: Boolean) {
        if (provider == EditingProvider.LLAMA) {
            select(provider)
            return
        }
        if (!consent) {
            forgetCredentials(provider)
            return
        }
        val endpoint = ProviderPolicy.endpoint(provider, url)
        val actualCredential = if (credential.isEmpty()) {
            val saved = readSavedCloud()
            require(ProviderPolicy.mayReuseCredential(provider, endpoint, saved)) {
                "Enter a new credential when changing provider or backend address."
            }
            checkNotNull(saved).credential
        } else credential
        ProviderPolicy.validateCredential(provider, actualCredential)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val encrypted = cipher.doFinal(actualCredential.toByteArray(Charsets.UTF_8))
        check(preferences.edit().clear()
            .putString("selected_provider", provider.name).putString("provider", provider.name)
            .putString("url", endpoint).putBoolean("consent", true)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Could not save editing settings securely."
        }
    }

    /** Remove all cloud credentials, preserving the selected provider and the downloaded local model. */
    fun forgetCredentials() = forgetCredentials(provider)

    fun forget() = forgetCredentials()

    private fun forgetCredentials(selected: EditingProvider) {
        check(preferences.edit().clear().putString("selected_provider", selected.name).commit()) {
            "Could not remove saved editing settings. Please try again."
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "The saved credential is unavailable. Enter it again." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    companion object { private const val ALIAS = "rawplus.backend.token" }
}
