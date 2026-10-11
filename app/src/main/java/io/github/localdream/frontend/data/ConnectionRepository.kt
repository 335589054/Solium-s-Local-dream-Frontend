package io.github.localdream.frontend.data

import io.github.localdream.frontend.remote.GenerationClient
import io.github.localdream.frontend.remote.RemoteApiClient
import io.github.localdream.frontend.remote.RemoteCatalog
import io.github.localdream.frontend.remote.RemoteHostInfo
import io.github.localdream.frontend.remote.RemoteHostStatus
import io.github.localdream.frontend.remote.RemoteProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnPhase { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class ConnState(
    val phase: ConnPhase = ConnPhase.DISCONNECTED,
    val host: String = "",
    val port: Int = RemoteProtocol.CONTROL_PORT,
    val hostInfo: RemoteHostInfo? = null,
    val catalog: RemoteCatalog? = null,
    val status: RemoteHostStatus? = null,
    val error: String? = null,
    // True while a poll has failed but not yet often enough to call the link
    // lost. Healthy in every other respect, so the UI just shows a warning.
    val unstable: Boolean = false,
    // Non-null while the controller waits for a just-issued /select to become
    // the running backend (model load can take a while on device).
    val activatingModelId: String? = null,
) {
    val isReady: Boolean
        get() = phase == ConnPhase.CONNECTED &&
            status?.state == RemoteProtocol.STATE_RUNNING &&
            status.servingModelId != null
}

/**
 * Owns the connection to a local-dream host: control API client, catalog,
 * live status polling, and model activation/stop lifecycle.
 */
class ConnectionRepository(
    private val log: LogRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private var api: RemoteApiClient? = null
    private var pollJob: Job? = null
    private var activationJob: Job? = null
    private var retryJob: Job? = null

    /** Set while the host backend is generating, to slow status polling down. */
    @Volatile
    private var backendBusy = false

    val generationHost: String?
        get() = api?.generationHost

    fun connect(host: String, port: Int = RemoteProtocol.CONTROL_PORT) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            _state.update { it.copy(phase = ConnPhase.FAILED, error = "主机地址不能为空") }
            return
        }
        disconnect(keepError = true)
        _state.value = ConnState(phase = ConnPhase.CONNECTING, host = cleanHost, port = port)
        log.info(TAG, "正在连接 $cleanHost:$port …")
        api = RemoteApiClient(cleanHost, port)

        scope.launch {
            val client = api ?: return@launch
            val info = client.fetchInfo()
            if (info == null) {
                _state.update {
                    it.copy(
                        phase = ConnPhase.FAILED,
                        error = "无法连接到该主机（/info 无响应），正在重试…",
                    )
                }
                log.error(TAG, "连接失败：$cleanHost:$port 的 /info 无响应，转入后台重试")
                startRetryLoop(cleanHost, port)
                return@launch
            }
            log.info(TAG, "已连接：设备=${info.deviceName} 版本=${info.version} 协议=${info.protocol}")
            val catalog = client.fetchCatalog()
            if (catalog == null) {
                log.warn(TAG, "已连接但未获取到模型列表")
            } else {
                log.info(TAG, "模型 ${catalog.models.size} 个，放大器 ${catalog.upscalers.size} 个")
            }
            _state.update {
                it.copy(phase = ConnPhase.CONNECTED, hostInfo = info, catalog = catalog, error = null)
            }
            startPolling()
        }
    }

    fun disconnect(keepError: Boolean = false) {
        pollJob?.cancel()
        activationJob?.cancel()
        retryJob?.cancel()
        pollJob = null
        activationJob = null
        retryJob = null
        api = null
        if (!keepError) {
            _state.value = ConnState()
            log.info(TAG, "已断开连接")
        } else {
            _state.value = _state.value.copy(activatingModelId = null)
        }
    }

    fun refreshCatalog() {
        val client = api ?: return
        scope.launch {
            val catalog = client.fetchCatalog()
            if (catalog == null) {
                log.warn(TAG, "刷新模型列表失败")
            } else {
                _state.update { it.copy(catalog = catalog) }
                log.info(TAG, "已刷新模型列表（${catalog.models.size} 个模型）")
            }
        }
    }

    /** Issues /select and waits until the backend reports it is running. */
    fun startModel(modelId: String, width: Int, height: Int) {
        val client = api ?: run {
            log.warn(TAG, "未连接，无法启动模型")
            return
        }
        activationJob?.cancel()
        _state.update { it.copy(activatingModelId = modelId) }
        log.info(TAG, "请求启动模型 $modelId（${width}x${height}）")

        activationJob = scope.launch {
            // The host processes every control route on a single worker, so the
            // regular poll would just queue up behind /select and the status
            // checks below. Let this loop own the port until the model settles.
            pollJob?.cancel()
            pollJob = null
            try {
                val accepted = client.selectModel(modelId, width, height)
                if (!accepted) {
                    log.error(TAG, "启动请求被拒绝（/select 失败）")
                    _state.update { it.copy(activatingModelId = null) }
                    return@launch
                }
                val timeoutMs = 180_000L
                val start = System.currentTimeMillis()
                var delayMs = 1_000L
                while (isActive && System.currentTimeMillis() - start < timeoutMs) {
                    val status = client.fetchStatus()
                    if (status != null) {
                        _state.update { it.copy(status = status) }
                        val ownError = status.state == RemoteProtocol.STATE_ERROR &&
                            (status.errorModelId == null || status.errorModelId == modelId)
                        if (ownError) {
                            log.error(TAG, "模型启动失败：${status.message ?: "未知错误"}")
                            _state.update { it.copy(activatingModelId = null) }
                            return@launch
                        }
                        if (status.servingModelId == modelId &&
                            status.state == RemoteProtocol.STATE_RUNNING &&
                            status.width == width &&
                            status.height == height &&
                            client.checkGenerationHealth()
                        ) {
                            log.info(TAG, "模型已就绪：$modelId")
                            _state.update { it.copy(activatingModelId = null) }
                            return@launch
                        }
                    }
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(3_000L)
                }
                if (isActive) {
                    log.error(TAG, "等待模型就绪超时")
                    _state.update { it.copy(activatingModelId = null) }
                }
            } finally {
                if (isActive) startPolling()
            }
        }
    }

    fun stopModel() {
        val client = api ?: return
        val serving = _state.value.status?.servingModelId
        if (serving == null) {
            log.warn(TAG, "当前没有运行中的模型")
            return
        }
        scope.launch {
            log.info(TAG, "请求停止模型 $serving")
            client.stopBackend(serving)
            val status = client.fetchStatus()
            _state.update { it.copy(status = status, activatingModelId = null) }
        }
    }

    /**
     * Keeps knocking on the control port until the host answers, so a host that
     * is still booting (or a Wi-Fi hiccup) recovers without user action.
     */
    private fun startRetryLoop(host: String, port: Int) {
        retryJob?.cancel()
        retryJob = scope.launch {
            var attempt = 0
            while (isActive && _state.value.phase == ConnPhase.FAILED) {
                attempt++
                delay(RETRY_INTERVAL_MS)

                val client = api ?: break
                val info = client.fetchInfo()
                if (info != null) {
                    val catalog = client.fetchCatalog()
                    log.info(TAG, "已重新连接：设备=${info.deviceName} 版本=${info.version}")
                    _state.update {
                        it.copy(
                            phase = ConnPhase.CONNECTED,
                            hostInfo = info,
                            catalog = catalog ?: it.catalog,
                            unstable = false,
                            error = null,
                        )
                    }
                    startPolling()
                    return@launch
                }
                if (attempt % 5 == 0) {
                    log.info(TAG, "仍在尝试连接 $host:$port（第 $attempt 次）")
                }
            }
        }
    }

    /**
     * Keeps polling forever once connected. A poll that fails is tolerated up
     * to [FAILURE_THRESHOLD] times (the host's control port can stall for
     * seconds while the GPU is busy generating) and, even after the link is
     * declared lost, polling continues so the connection heals on its own as
     * soon as the host answers again.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var failures = 0
            while (isActive) {
                val client = api ?: break
                val status = client.fetchStatus()

                if (status != null) {
                    if (failures > 0) {
                        log.info(TAG, "与主机的连接已恢复")
                    }
                    failures = 0
                    val wasDown = _state.value.phase != ConnPhase.CONNECTED
                    _state.update {
                        it.copy(
                            phase = ConnPhase.CONNECTED,
                            status = status,
                            unstable = false,
                            error = null,
                        )
                    }
                    if (wasDown) {
                        // The host may have swapped models while we were away.
                        client.fetchCatalog()?.let { catalog ->
                            _state.update { it.copy(catalog = catalog) }
                        }
                    }
                } else {
                    failures++
                    if (failures >= FAILURE_THRESHOLD) {
                        if (_state.value.phase != ConnPhase.FAILED) {
                            log.warn(TAG, "与主机失去联系，正在后台重试")
                        }
                        _state.update {
                            it.copy(phase = ConnPhase.FAILED, unstable = false, error = "与主机失去联系，正在重试…")
                        }
                    } else {
                        log.debug(TAG, "状态查询超时（第 $failures 次）")
                        _state.update { it.copy(unstable = true) }
                    }
                }

                delay(nextInterval(failures))
            }
        }
    }

    /**
     * Poll spacing. The host serves its control API from a single worker, so
     * polling is kept deliberately gentle: slower while the backend is
     * generating, and backing off geometrically once a poll has been missed.
     */
    private fun nextInterval(failures: Int): Long = when {
        failures > 0 -> (BASE_POLL_MS shl (failures - 1).coerceAtMost(3)).coerceAtMost(MAX_POLL_MS)
        backendBusy -> BUSY_POLL_MS
        else -> BASE_POLL_MS
    }

    /** Tells the poller that the host is busy generating, so it should back off. */
    fun setBackendBusy(busy: Boolean) {
        backendBusy = busy
    }

    /** One-shot tokenize against the host backend, used for the token counter. */
    suspend fun tokenize(text: String): Int? {
        val host = generationHost ?: return null
        return GenerationClient.tokenize(host, text)?.count
    }

    private companion object {
        const val TAG = "Connection"

        /** Idle spacing between status polls. */
        const val BASE_POLL_MS = 5_000L

        /** Slower while the backend is generating, to stay out of its way. */
        const val BUSY_POLL_MS = 15_000L

        const val MAX_POLL_MS = 30_000L

        /** Consecutive missed polls before the link is called lost. */
        const val FAILURE_THRESHOLD = 3

        /** Gap between reconnect attempts while the host is unreachable. */
        const val RETRY_INTERVAL_MS = 3_000L
    }
}