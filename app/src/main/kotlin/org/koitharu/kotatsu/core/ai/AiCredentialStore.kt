package org.koitharu.kotatsu.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed AndroidKeyStore AES-256-GCM vault for AI Router API keys.
 *
 * Keeps Cloud (Gemini / OpenAI-compatible) and optional LAN bearer tokens encrypted at rest
 * in a dedicated private SharedPreferences file outside [org.koitharu.kotatsu.core.prefs.AppSettings]
 * so credentials are never exported in backups or Google Drive sync.
 */
@Singleton
class AiCredentialStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

	var geminiApiKey: String
		get() = readSecret(KEY_GEMINI_API_KEY)
		set(value) = writeSecret(KEY_GEMINI_API_KEY, value.trim())

	var openAiApiKey: String
		get() = readSecret(KEY_OPENAI_API_KEY)
		set(value) = writeSecret(KEY_OPENAI_API_KEY, value.trim())

	var ollamaApiKey: String
		get() = readSecret(KEY_OLLAMA_API_KEY)
		set(value) = writeSecret(KEY_OLLAMA_API_KEY, value.trim())

	private fun writeSecret(prefKey: String, plainText: String) {
		if (plainText.isBlank()) {
			prefs.edit { remove(prefKey) }
			return
		}
		val encoded = runCatching { encryptAesGcm(plainText) }.getOrNull()
		if (encoded != null) {
			prefs.edit { putString(prefKey, encoded) }
		}
	}

	private fun readSecret(prefKey: String): String {
		val stored = prefs.getString(prefKey, null)?.takeIf { it.isNotBlank() } ?: return ""
		return runCatching { decryptAesGcm(stored) }.getOrDefault("")
	}

	private fun encryptAesGcm(plainText: String): String {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
		val iv = cipher.iv
		val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
		val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
		val payloadBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
		return "$ivBase64:$payloadBase64"
	}

	private fun decryptAesGcm(combined: String): String {
		val separatorIndex = combined.indexOf(':')
		if (separatorIndex <= 0) return ""
		val iv = Base64.decode(combined.substring(0, separatorIndex), Base64.NO_WRAP)
		val cipherBytes = Base64.decode(combined.substring(separatorIndex + 1), Base64.NO_WRAP)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
		return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
	}

	@Synchronized
	private fun getOrCreateSecretKey(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
		val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
		if (existing != null) {
			return existing.secretKey
		}
		val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		val spec = KeyGenParameterSpec.Builder(
			KEYSTORE_ALIAS,
			KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
		)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			.setKeySize(256)
			.build()
		keyGenerator.init(spec)
		return keyGenerator.generateKey()
	}

	companion object {
		private const val PREFS_FILE_NAME = "nemuri_ai_keystore_vault"
		private const val ANDROID_KEYSTORE = "AndroidKeyStore"
		private const val KEYSTORE_ALIAS = "nemuri_ai_router_aes_key_v1"
		private const val TRANSFORMATION = "AES/GCM/NoPadding"
		private const val GCM_TAG_BITS = 128

		private const val KEY_GEMINI_API_KEY = "enc_gemini_api_key"
		private const val KEY_OPENAI_API_KEY = "enc_openai_api_key"
		private const val KEY_OLLAMA_API_KEY = "enc_ollama_api_key"
	}
}
