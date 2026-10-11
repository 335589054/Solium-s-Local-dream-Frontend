package io.github.localdream.frontend.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Prompts that are filled in automatically when a model is selected. */
data class ModelPreset(
    val modelId: String,
    val positive: String,
    val negative: String,
)

/**
 * Per-model default prompts. Selecting a model in the studio applies its preset
 * (if any) so a model that expects a particular trigger word or quality tags
 * always gets them.
 */
class ModelPresetRepository(
    context: Context,
    private val log: LogRepository,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "model_presets.json")

    private val _presets = MutableStateFlow(load())
    val presets: StateFlow<List<ModelPreset>> = _presets.asStateFlow()

    fun forModel(modelId: String): ModelPreset? =
        _presets.value.firstOrNull { it.modelId == modelId }?.takeIf {
            it.positive.isNotBlank() || it.negative.isNotBlank()
        }

    fun set(modelId: String, positive: String, negative: String) {
        val preset = ModelPreset(modelId, positive.trim(), negative.trim())
        val next = _presets.value.filterNot { it.modelId == modelId } +
            if (preset.positive.isBlank() && preset.negative.isBlank()) emptyList() else listOf(preset)
        _presets.value = next
        persist(next)
        log.info(TAG, "已保存模型预设：$modelId")
    }

    fun clear(modelId: String) {
        val next = _presets.value.filterNot { it.modelId == modelId }
        _presets.value = next
        persist(next)
    }

    private fun persist(items: List<ModelPreset>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    file.writeText(
                        JSONArray().apply {
                            items.forEach {
                                put(
                                    JSONObject()
                                        .put("modelId", it.modelId)
                                        .put("positive", it.positive)
                                        .put("negative", it.negative),
                                )
                            }
                        }.toString(),
                    )
                }.onFailure { log.error(TAG, "模型预设保存失败：${it.message}") }
            }
        }
    }

    private fun load(): List<ModelPreset> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val modelId = obj.optString("modelId")
            if (modelId.isEmpty()) return@mapNotNull null
            ModelPreset(modelId, obj.optString("positive"), obj.optString("negative"))
        }
    }.getOrElse { emptyList() }

    private companion object {
        const val TAG = "ModelPreset"
    }
}