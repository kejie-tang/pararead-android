package org.pararead.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.pararead.core.TranslationConfig
import org.pararead.core.CpuThreads
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var provider: String get() = prefs.getString("provider", "llama")!!; set(v) { prefs.edit().putString("provider", v).apply() }
    var baseUrl: String get() = prefs.getString("baseUrl", "http://127.0.0.1:8080/v1")!!; set(v) { prefs.edit().putString("baseUrl", v).apply() }
    var model: String get() = prefs.getString("model", "qwen3:1.7b")!!; set(v) { prefs.edit().putString("model", v).apply() }
    var modelPath: String get() = prefs.getString("modelPath", "")!!; set(v) { prefs.edit().putString("modelPath", v).apply() }
    var modelHash: String get() = prefs.getString("modelHash", "unloaded")!!; set(v) { prefs.edit().putString("modelHash", v).apply() }
    var targetLanguage: String get() = prefs.getString("targetLanguage", "zh-CN")!!; set(v) { prefs.edit().putString("targetLanguage", v).apply() }
    var sourceLanguage: String get() = prefs.getString("sourceLanguage", "auto")!!; set(v) { prefs.edit().putString("sourceLanguage", v).apply() }
    var glossary: String get() = prefs.getString("glossary", "{}")!!; set(v) { prefs.edit().putString("glossary", v).apply() }
    var batchSize: Int get() = prefs.getInt("batchSize", 4); set(v) { prefs.edit().putInt("batchSize", v.coerceIn(1, 40)).apply() }
    var cpuThreads: Int
        get() = prefs.getInt("cpuThreads", 0).coerceAtLeast(0)
        set(value) { require(value in 0..CpuThreads.maximum()); prefs.edit().putInt("cpuThreads", value).apply() }
    val modelLicense: String get() = prefs.getString("modelLicense", "")!!
    val modelRepository: String get() = prefs.getString("modelRepository", "")!!
    val modelRevision: String get() = prefs.getString("modelRevision", "")!!
    fun installModel(path: String, hash: String, name: String, license: String = "", repository: String = "", revision: String = "") {
        prefs.edit(commit = true) { putString("modelPath", path); putString("modelHash", hash); putString("model", name); putString("provider", "llama")
            putString("modelLicense", license); putString("modelRepository", repository); putString("modelRevision", revision) }
    }
    fun clearModel() {
        prefs.edit(commit = true) { remove("modelPath"); remove("modelHash"); remove("modelLicense"); remove("modelRepository"); remove("modelRevision") }
    }
    var translateRange: String get() = prefs.getString("translateRange", "nearby")!!; set(v) { prefs.edit().putString("translateRange", v).apply() }
    var apiKey: String
        get() = runCatching {
            val stored = prefs.getString("apiKey", null) ?: return ""
            val values = stored.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(values[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(values[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            prefs.edit().putString("apiKey", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
        }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pararead-api", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pararead-api", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun config(): TranslationConfig {
        val locale = java.util.Locale.getDefault().toLanguageTag().lowercase()
        val effective = if (targetLanguage != "auto") targetLanguage else when {
            locale.startsWith("zh-tw") || locale.startsWith("zh-hk") || locale.contains("hant") -> "zh-TW"
            locale.startsWith("zh") -> "zh-CN"
            locale.substringBefore('-') in setOf("en","ja","ko","fr","de","es","pt","ru","it") -> locale.substringBefore('-')
            else -> "en"
        }
        return TranslationConfig(provider, model, if (provider == "api") "endpoint:$baseUrl" else modelHash, sourceLanguage, effective, glossary, batchSize)
    }
}
