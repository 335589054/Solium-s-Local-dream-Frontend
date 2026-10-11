package io.github.localdream.frontend.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.localdream.frontend.data.AppSettings
import io.github.localdream.frontend.data.ConnectionRepository
import io.github.localdream.frontend.data.ConnPhase
import io.github.localdream.frontend.data.HistoryKind
import io.github.localdream.frontend.data.HistoryRepository
import io.github.localdream.frontend.data.LogRepository
import io.github.localdream.frontend.data.MessageRepository
import io.github.localdream.frontend.data.ModelPresetRepository
import io.github.localdream.frontend.data.PromptLibraryRepository
import io.github.localdream.frontend.data.PromptToken
import io.github.localdream.frontend.data.PromptTokens
import io.github.localdream.frontend.data.SettingsRepository
import io.github.localdream.frontend.remote.GenerationClient
import io.github.localdream.frontend.remote.GenerationEvent
import io.github.localdream.frontend.remote.GenerateRequest
import io.github.localdream.frontend.remote.RemoteModelInfo
import io.github.localdream.frontend.remote.RemoteProtocol
import io.github.localdream.frontend.ui.components.ImageCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "Studio"

data class StudioState(
    val positiveTokens: List<PromptToken> = emptyList(),
    val negativeTokens: List<PromptToken> = emptyList(),
    val steps: Int = 20,
    val cfg: Float = 7f,
    val scheduler: String = "dpm",
    val width: Int = 512,
    val height: Int = 512,
    val seed: String = "",
    val selectedModelId: String? = null,
    val isGenerating: Boolean = false,
    val isUpscaling: Boolean = false,
    val progress: Float = 0f,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val preview: Bitmap? = null,
    val result: Bitmap? = null,
    val lastSeed: Long? = null,
    val promptTokenCount: Int? = null,
    val tokenLimit: Int = 77,
    val baseImage: Bitmap? = null,
    val denoiseStrength: Float = 0.7f,
) {
    val positivePrompt: String get() = PromptTokens.join(positiveTokens)
    val negativePrompt: String get() = PromptTokens.join(negativeTokens)

    /** img2img is active as soon as a base image is loaded. */
    val img2imgActive: Boolean get() = baseImage != null
}

/**
 * Drives the studio: structured prompt editing, parameters, img2img, optional
 * auto-upscaling and history bookkeeping. Model loading itself stays manual and
 * lives in [ConnectionRepository].
 */
class StudioViewModel(
    private val connection: ConnectionRepository,
    private val log: LogRepository,
    private val message: MessageRepository,
    private val promptLibrary: PromptLibraryRepository,
    private val modelPresets: ModelPresetRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    val connectionState = connection.state
    val library = promptLibrary.library
    val modelPresetList = modelPresets.presets

    private var generateJob: Job? = null
    private var tokenizeJob: Job? = null
    private var activeSettings = AppSettings()

    init {
        viewModelScope.launch {
            settings.settings.collect { activeSettings = it }
        }
        viewModelScope.launch {
            connection.state.collect { conn ->
                if (_state.value.selectedModelId == null) {
                    conn.catalog?.models?.firstOrNull()?.let { selectModel(it) }
                }
            }
        }
    }

    // ---------------- prompt editing ----------------

    /** Replaces the positive prompt from the free-text editor dialog. */
    fun setPositiveText(text: String) {
        _state.update { it.copy(positiveTokens = PromptTokens.parse(text, ::translationOf)) }
        scheduleTokenize(text)
    }

    fun setNegativeText(text: String) {
        _state.update { it.copy(negativeTokens = PromptTokens.parse(text, ::translationOf)) }
    }

    fun addPositiveTag(en: String, zh: String) {
        val next = PromptTokens.add(_state.value.positiveTokens, en, zh)
        if (next == _state.value.positiveTokens) return
        _state.update { it.copy(positiveTokens = next) }
        scheduleTokenize(PromptTokens.join(next))
    }

    fun addNegativeTag(en: String, zh: String) {
        _state.update { it.copy(negativeTokens = PromptTokens.add(it.negativeTokens, en, zh)) }
    }

    /** Adds one parenthesis level, the Stable Diffusion way to raise emphasis. */
    fun increasePositiveWeight(id: String) {
        val next = PromptTokens.increaseWeight(_state.value.positiveTokens, id)
        _state.update { it.copy(positiveTokens = next) }
        scheduleTokenize(PromptTokens.join(next))
    }

    fun increaseNegativeWeight(id: String) {
        _state.update { it.copy(negativeTokens = PromptTokens.increaseWeight(it.negativeTokens, id)) }
    }

    /**
     * Peels one parenthesis level off; once a tag has no parentheses left the
     * same button removes it entirely.
     */
    fun decreasePositiveWeight(id: String) {
        val current = _state.value.positiveTokens
        val token = current.firstOrNull { it.id == id } ?: return
        val next = if (token.weight <= 0) {
            PromptTokens.remove(current, id)
        } else {
            PromptTokens.decreaseWeight(current, id)
        }
        _state.update { it.copy(positiveTokens = next) }
        scheduleTokenize(PromptTokens.join(next))
    }

    fun decreaseNegativeWeight(id: String) {
        val current = _state.value.negativeTokens
        val token = current.firstOrNull { it.id == id } ?: return
        val next = if (token.weight <= 0) {
            PromptTokens.remove(current, id)
        } else {
            PromptTokens.decreaseWeight(current, id)
        }
        _state.update { it.copy(negativeTokens = next) }
    }

    fun clearPositive() {
        _state.update { it.copy(positiveTokens = emptyList()) }
        scheduleTokenize("")
    }

    fun clearNegative() = _state.update { it.copy(negativeTokens = emptyList()) }

    fun applyCombo(positive: String, negative: String) {
        setPositiveText(positive)
        setNegativeText(negative)
    }

    private fun translationOf(tag: String): String? =
        promptLibrary.library.value.tags.firstOrNull { it.en.equals(tag, ignoreCase = true) }?.zh

    // ---------------- img2img ----------------

    fun setBaseImage(bitmap: Bitmap) {
        _state.update { it.copy(baseImage = bitmap, result = bitmap) }
        log.info(TAG, "已载入 img2img 底图：${bitmap.width}x${bitmap.height}")
    }

    fun clearBaseImage() = _state.update { it.copy(baseImage = null) }

    fun setDenoiseStrength(value: Float) = _state.update { it.copy(denoiseStrength = value.coerceIn(0.1f, 1f)) }

    fun setAutoUpscale(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoUpscale(enabled) }
    }

    // ---------------- parameters ----------------

    fun setSteps(value: Int) = _state.update { it.copy(steps = value.coerceIn(1, 150)) }

    fun setCfg(value: Float) = _state.update { it.copy(cfg = value.coerceIn(1f, 30f)) }

    fun setScheduler(value: String) = _state.update { it.copy(scheduler = value) }

    fun setSeed(value: String) = _state.update { it.copy(seed = value.filter { c -> c.isDigit() }) }

    /** Only preset canvases are offered, so the backend never gets a size it
     * cannot patch. */
    fun setResolution(width: Int, height: Int) = _state.update { it.copy(width = width, height = height) }

    fun availableResolutions(): List<Pair<Int, Int>> {
        val model = selectedModel() ?: return emptyList()
        if (model.resolutions.isNotEmpty()) return model.resolutions
        val size = model.generationSize
        return RATIO_FACTORS.map { (rw, rh) ->
            align8((size * rw).toInt()) to align8((size * rh).toInt())
        }
    }

    private fun align8(value: Int): Int = (value / 8) * 8

    // ---------------- model ----------------

    fun selectModel(model: RemoteModelInfo) {
        val resolutions = model.resolutions.ifEmpty {
            val size = model.generationSize
            RATIO_FACTORS.map { (rw, rh) ->
                align8((size * rw).toInt()) to align8((size * rh).toInt())
            }
        }
        val first = resolutions.firstOrNull()
        // A model can carry preset prompts (trigger words, quality tags); they
        // replace the fields they define as soon as the model is picked.
        val preset = modelPresets.forModel(model.id)
        _state.update {
            it.copy(
                selectedModelId = model.id,
                steps = model.defaults.steps.toInt().coerceAtLeast(1),
                cfg = model.defaults.cfg,
                scheduler = model.defaults.scheduler.ifEmpty { it.scheduler },
                width = first?.first ?: model.generationSize,
                height = first?.second ?: model.generationSize,
                positiveTokens = preset?.positive?.takeIf { p -> p.isNotBlank() }
                    ?.let { p -> PromptTokens.parse(p, ::translationOf) } ?: it.positiveTokens,
                negativeTokens = preset?.negative?.takeIf { n -> n.isNotBlank() }
                    ?.let { n -> PromptTokens.parse(n, ::translationOf) } ?: it.negativeTokens,
            )
        }
        log.info(TAG, "已选择模型：${model.name}" + if (preset != null) "（已套用预设提示词）" else "")
        if (preset != null) message.show("已套用「${model.name}」的预设提示词")
        scheduleTokenize(PromptTokens.join(_state.value.positiveTokens))
    }

    fun selectedModel(): RemoteModelInfo? {
        val catalog = connection.state.value.catalog ?: return null
        val id = _state.value.selectedModelId
        return catalog.models.firstOrNull { it.id == id }
    }

    fun startSelectedModel() {
        val model = selectedModel() ?: run {
            message.show("请先选择一个模型")
            return
        }
        connection.startModel(model.id, _state.value.width, _state.value.height)
    }

    fun stopModel() = connection.stopModel()

    fun refreshCatalogNow() = connection.refreshCatalog()

    // ---------------- prompt library pass-through ----------------

    fun importTags(text: String): Int = promptLibrary.importTags(text)

    fun clearTagLibrary() = promptLibrary.clearTags()

    fun addTag(category: String, zh: String, en: String) = promptLibrary.addTag(category, zh, en)

    fun saveCombo(name: String) {
        val snapshot = _state.value
        promptLibrary.addCombo(name, snapshot.positivePrompt, snapshot.negativePrompt)
    }

    fun removeCombo(id: String) = promptLibrary.removeCombo(id)

    /** Saves (or clears) the preset prompts bound to a model. */
    fun saveModelPreset(modelId: String, positive: String, negative: String) =
        modelPresets.set(modelId, positive, negative)

    fun clearModelPreset(modelId: String) = modelPresets.clear(modelId)

    // ---------------- generation ----------------

    fun generate() {
        if (_state.value.isGenerating || _state.value.isUpscaling) return
        val issue = readinessIssue()
        if (issue != null) {
            message.show(issue)
            log.warn(TAG, "无法生成：$issue")
            return
        }
        val host = connection.generationHost ?: return
        runGeneration(host, _state.value)
    }

    /**
     * Explains why generation is not possible right now, or returns null when
     * everything is in place. Blocking setup problems come before "prompt is
     * empty" so the disabled button always shows its real cause.
     */
    fun readinessIssue(): String? {
        val conn = connection.state.value
        if (conn.phase == ConnPhase.DISCONNECTED) return "尚未连接到主机"
        if (conn.phase != ConnPhase.CONNECTED) return "与主机失去联系，正在自动重试…"

        val modelId = _state.value.selectedModelId ?: return "请先在「模型」页选择一个模型"
        val status = conn.status
        if (status?.state != RemoteProtocol.STATE_RUNNING || status.servingModelId != modelId) {
            return "模型未启动：请到「模型」页点击「启动所选模型」"
        }
        val width = _state.value.width
        val height = _state.value.height
        if (status.width != width || status.height != height) {
            return "模型正以 ${status.width}×${status.height} 运行，请重新启动以匹配当前分辨率"
        }
        if (_state.value.positivePrompt.isBlank()) return "提示词不能为空"
        return null
    }

    private fun runGeneration(host: String, snapshot: StudioState) {
        val baseImage = snapshot.baseImage
        val request = GenerateRequest(
            prompt = snapshot.positivePrompt,
            negativePrompt = snapshot.negativePrompt,
            steps = snapshot.steps,
            cfg = snapshot.cfg,
            width = snapshot.width,
            height = snapshot.height,
            scheduler = snapshot.scheduler,
            seed = snapshot.seed.toLongOrNull(),
            showDiffusionProcess = activeSettings.showDiffusionProcess,
            showDiffusionStride = activeSettings.showDiffusionStride,
            denoiseStrength = if (baseImage != null) snapshot.denoiseStrength else 1.0f,
            image = baseImage?.let { ImageCodec.toBase64Png(it, maxSide = maxOf(snapshot.width, snapshot.height)) },
        )
        val kind = if (baseImage != null) HistoryKind.IMG2IMG else HistoryKind.TEXT2IMG

        _state.update {
            it.copy(
                isGenerating = true,
                progress = 0f,
                currentStep = 0,
                totalSteps = snapshot.steps,
                preview = null,
            )
        }
        log.info(
            TAG,
            "开始生成（${kind.label}）：${snapshot.width}x${snapshot.height} steps=${snapshot.steps} " +
                "cfg=${snapshot.cfg} scheduler=${snapshot.scheduler}",
        )

        generateJob = viewModelScope.launch {
            // While a generation runs the host is saturated; slowing its status
            // polling keeps the single-worker control queue free.
            connection.setBackendBusy(true)
            try {
                GenerationClient.generate(host, request).collect { event ->
                    when (event) {
                        is GenerationEvent.Progress -> {
                            val progress = if (event.totalSteps > 0) {
                                event.step.toFloat() / event.totalSteps
                            } else {
                                0f
                            }
                            val preview = event.imageBase64?.let { b64 ->
                                ImageCodec.decode(b64, event.format, snapshot.width, snapshot.height)
                            }
                            _state.update {
                                it.copy(
                                    progress = progress,
                                    currentStep = event.step,
                                    totalSteps = event.totalSteps,
                                    preview = preview ?: it.preview,
                                )
                            }
                        }

                        is GenerationEvent.Complete -> {
                            val bitmap = ImageCodec.decode(
                                event.imageBase64,
                                event.format,
                                event.width,
                                event.height,
                                event.channels,
                            )
                            _state.update {
                                it.copy(
                                    isGenerating = false,
                                    progress = 1f,
                                    result = bitmap ?: it.result,
                                    preview = null,
                                    lastSeed = event.seed,
                                )
                            }
                            log.info(
                                TAG,
                                "生成完成：${event.width}x${event.height}" +
                                    (event.seed?.let { s -> " seed=$s" } ?: ""),
                            )
                            if (bitmap != null) {
                                history.add(
                                    bitmap = bitmap,
                                    kind = kind,
                                    prompt = snapshot.positivePrompt,
                                    negativePrompt = snapshot.negativePrompt,
                                    modelId = snapshot.selectedModelId.orEmpty(),
                                    seed = event.seed,
                                )
                                if (activeSettings.autoUpscale) {
                                    upscaleInternal(bitmap, snapshot, auto = true)
                                }
                            }
                        }
                    }
                }
                if (_state.value.isGenerating) {
                    _state.update { it.copy(isGenerating = false) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isGenerating = false) }
                message.show(e.message ?: "生成失败")
                log.error(TAG, "生成失败：${e.message}")
            } finally {
                connection.setBackendBusy(false)
            }
        }
    }

    fun cancelGeneration() {
        generateJob?.cancel()
        generateJob = null
        _state.update { it.copy(isGenerating = false) }
        log.warn(TAG, "已取消生成")
    }

    // ---------------- upscaling ----------------

    /** Manually upscales whatever is on screen (history "send to upscale" too). */
    fun upscaleResult() {
        if (_state.value.isUpscaling) return
        val snapshot = _state.value
        val bitmap = snapshot.result ?: snapshot.baseImage ?: return
        upscaleInternal(bitmap, snapshot, auto = false)
    }

    fun canUpscale(): Boolean =
        connection.state.value.catalog?.upscalers?.isNotEmpty() == true &&
            connection.state.value.phase == ConnPhase.CONNECTED

    private fun upscaleInternal(bitmap: Bitmap, snapshot: StudioState, auto: Boolean) {
        val host = connection.generationHost ?: return
        val upscaler = connection.state.value.catalog?.upscalers?.firstOrNull()
        if (upscaler == null) {
            if (auto) log.warn(TAG, "主机未安装放大器，跳过自动超分")
            else message.show("主机上未安装放大器")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isUpscaling = true) }
            log.info(TAG, if (auto) "正在自动超分…" else "正在超分…")

            GenerationClient.upscale(host, bitmap, upscaler.path)
                .onSuccess { upscaled ->
                    _state.update { it.copy(isUpscaling = false, result = upscaled) }
                    log.info(TAG, "超分完成：${upscaled.width}x${upscaled.height}")
                    history.add(
                        bitmap = upscaled,
                        kind = HistoryKind.UPSCALE,
                        prompt = snapshot.positivePrompt,
                        negativePrompt = snapshot.negativePrompt,
                        modelId = snapshot.selectedModelId.orEmpty(),
                        seed = snapshot.lastSeed,
                    )
                    if (!auto) message.show("超分完成 ${upscaled.width}×${upscaled.height}")
                }
                .onFailure { error ->
                    _state.update { it.copy(isUpscaling = false) }
                    val reason = error.message ?: "未知错误"
                    // Surfaced even for the automatic path: otherwise a failed
                    // auto-upscale looks like the result simply never changed.
                    message.show("超分失败：$reason")
                    log.error(TAG, "超分失败：$reason")
                }
        }
    }

    /** Loads a history entry as the img2img base image. */
    fun loadHistoryAsBaseImage(bitmap: Bitmap) {
        setBaseImage(bitmap)
        message.show("已发送到图生图")
    }

    /** Sends a history entry straight to the host upscaler. */
    fun upscaleFromHistory(bitmap: Bitmap) {
        if (_state.value.isUpscaling) return
        _state.update { it.copy(result = bitmap) }
        val snapshot = _state.value
        upscaleInternal(bitmap, snapshot, auto = false)
    }

    fun clearResult() = _state.update { it.copy(result = null, preview = null, progress = 0f) }

    /** Surfaces a short note as the top banner. */
    fun notify(text: String) = message.show(text)

    /** Writes a bitmap straight to the system gallery. */
    fun saveBitmapToGallery(bitmap: Bitmap) {
        viewModelScope.launch {
            val ok = history.saveBitmapToGallery(bitmap, "result_${System.currentTimeMillis()}")
            message.show(if (ok) "已保存至图库" else "保存失败（需要 Android 10 及以上）")
        }
    }

    private fun scheduleTokenize(text: String) {
        tokenizeJob?.cancel()
        val host = connection.generationHost
        if (text.isBlank() || host == null) {
            _state.update { it.copy(promptTokenCount = null) }
            return
        }
        tokenizeJob = viewModelScope.launch {
            // Debounce: tokenize runs on the host's generation port too, and
            // typing would otherwise fire one request per keystroke.
            kotlinx.coroutines.delay(500)
            val result = GenerationClient.tokenize(host, text) ?: return@launch
            _state.update { it.copy(promptTokenCount = result.count, tokenLimit = result.maxLength) }
        }
    }

    companion object {
        /** Canvas shape factors applied to a model's generation size. */
        private val RATIO_FACTORS = listOf(
            1.0f to 1.0f,
            1.125f to 0.875f,
            0.875f to 1.125f,
            1.1875f to 0.8125f,
            0.8125f to 1.1875f,
        )

        fun factory(
            connection: ConnectionRepository,
            log: LogRepository,
            message: MessageRepository,
            promptLibrary: PromptLibraryRepository,
            modelPresets: ModelPresetRepository,
            history: HistoryRepository,
            settings: SettingsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StudioViewModel(
                    connection,
                    log,
                    message,
                    promptLibrary,
                    modelPresets,
                    history,
                    settings,
                ) as T
        }
    }
}