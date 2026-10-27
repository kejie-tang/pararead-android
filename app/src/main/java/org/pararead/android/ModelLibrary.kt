package org.pararead.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.InstalledModel
import org.pararead.core.ModelAsset
import java.io.File

/** Metadata for verified files in app-owned storage. The active selection remains in Settings. */
data class SavedModel(val model: InstalledModel, val name: String, val license: String = "",
    val repository: String = "", val revision: String = "")

internal class ModelLibrary(context: Context, private val directory: File, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("model-library", Context.MODE_PRIVATE)
    init {
        // Upgrade the single-model installation in place: no copy, re-download or cache change.
        val path = settings.modelPath
        if (path.isNotEmpty()) {
            val file = File(path)
            if (settings.modelHash.matches(Regex("[a-f0-9]{64}")) && file.isFile && file.canonicalFile.parentFile == directory.canonicalFile &&
                all().none { it.model.sha256 == settings.modelHash }) {
                remember(SavedModel(InstalledModel(path, settings.modelHash, file.length()), settings.model,
                    settings.modelLicense, settings.modelRepository, settings.modelRevision))
            }
        }
    }
    @Synchronized fun all(): List<SavedModel> {
        val rows = runCatching { JSONArray(prefs.getString("models", "[]")) }.getOrDefault(JSONArray())
        return (0 until rows.length()).mapNotNull { i -> runCatching {
            val row = rows.getJSONObject(i); val hash = row.getString("sha256")
            require(hash.matches(Regex("[a-f0-9]{64}")))
            val basename = row.optString("file", "$hash.gguf")
            require(basename == File(basename).name && basename.endsWith(".gguf"))
            val file = File(directory, basename)
            require(file.canonicalFile.parentFile == directory.canonicalFile)
            require(file.isFile && file.length() == row.getLong("bytes"))
            SavedModel(InstalledModel(file.absolutePath, hash, file.length()), row.getString("name"),
                row.optString("license"), row.optString("repository"), row.optString("revision"))
        }.getOrNull() }.distinctBy { it.model.sha256 }
    }
    @Synchronized fun remember(saved: SavedModel) {
        require(saved.model.sha256.matches(Regex("[a-f0-9]{64}")))
        require(File(saved.model.path).canonicalFile.parentFile == directory.canonicalFile)
        write(all().filterNot { it.model.sha256 == saved.model.sha256 } + saved)
    }
    @Synchronized fun forget(hash: String) = write(all().filterNot { it.model.sha256 == hash })
    private fun write(models: List<SavedModel>) {
        val rows = JSONArray()
        models.forEach { saved -> rows.put(JSONObject().put("sha256", saved.model.sha256).put("bytes", saved.model.bytes).put("file", File(saved.model.path).name)
            .put("name", saved.name).put("license", saved.license).put("repository", saved.repository).put("revision", saved.revision)) }
        check(prefs.edit().putString("models", rows.toString()).commit()) { "无法保存模型列表，请重试" }
    }
}
