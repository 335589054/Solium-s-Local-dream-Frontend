package io.github.localdream.frontend.webui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.localdream.frontend.data.ConnectionRepository
import io.github.localdream.frontend.data.ConnPhase
import io.github.localdream.frontend.data.LogRepository
import io.github.localdream.frontend.data.PromptLibraryRepository
import io.github.localdream.frontend.remote.GenerationClient
import io.github.localdream.frontend.remote.GenerationEvent
import io.github.localdream.frontend.remote.GenerateRequest
import io.github.localdream.frontend.remote.RemoteProtocol
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

data class WebUiServerState(
    val running: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val addresses: List<String> = emptyList(),
    val error: String? = null,
) {
    companion object {
        const val DEFAULT_PORT = 7860
    }
}

class ApiException(val status: Int, override val message: String) : Exception(message)

/**
 * Publishes this device to the LAN as a Stable Diffusion WebUI.
 *
 * The app already drives a local-dream backend; this server exposes that same
 * backend through the AUTOMATIC1111 HTTP API, so a browser (see [WebUiPage]) or
 * any existing A1111 client can generate against this phone.
 *
 * Only the endpoints this backend can honour are implemented — no script,
 * extension, embedding-training or interrogation routes.
 */
class WebUiServer(
    private val connection: ConnectionRepository,
    private val promptLibrary: PromptLibraryRepository,
    private val log: LogRepository,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val _state = MutableStateFlow(WebUiServerState())
    val state: StateFlow<WebUiServerState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: java.util.concurrent.ExecutorService? = null

    /** Serialises generation: the local-dream backend runs one job at a time. */
    private val generationLock = ReentrantLock()

    // Live job state, polled by /sdapi/v1/progress.
    @Volatile private var jobActive = false
    @Volatile private var jobStep = 0
    @Volatile private var jobTotal = 0
    @Volatile private var jobImage: String? = null
    @Volatile private var jobKind = ""
    @Volatile private var jobInterrupted = false

    fun start(port: Int = WebUiServerState.DEFAULT_PORT) {
        if (_state.value.running) stop()
        runCatching {
            val socket = ServerSocket(port)
            serverSocket = socket
            workers = Executors.newFixedThreadPool(WORKER_COUNT) { r -> Thread(r, "webui-worker") }
            acceptThread = Thread({
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    workers?.execute { serve(client) }
                }
            }, "webui-accept").apply { start() }

            _state.value = WebUiServerState(
                running = true,
                port = socket.localPort,
                addresses = localAddresses(socket.localPort),
            )
            log.info(TAG, "WebUI 服务器已启动：${_state.value.addresses.joinToString()}")
        }.onFailure {
            _state.value = WebUiServerState(running = false, port = port, error = it.message)
            log.error(TAG, "WebUI 服务器启动失败：${it.message}")
        }
    }

    fun stop() {
        runCatching { acceptThread?.interrupt() }
        runCatching { serverSocket?.close() }
        runCatching { workers?.shutdownNow() }
        acceptThread = null
        serverSocket = null
        workers = null
        jobActive = false
        _state.value = WebUiServerState(running = false, port = _state.value.port)
        log.info(TAG, "WebUI 服务器已停止")
    }

    // ---------------- HTTP ----------------

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val request = readRequest(input) ?: return
                val response = try {
                    route(request)
                } catch (e: ApiException) {
                    jsonResponse(e.status, JSONObject().put("detail", e.message))
                } catch (e: Exception) {
                    log.error(TAG, "处理 ${request.path} 失败：${e.message}")
                    jsonResponse(500, JSONObject().put("detail", e.message ?: "内部错误"))
                }
                write(socket, response)
            }
        } catch (e: Exception) {
            log.warn(TAG, "连接处理异常：${e.message}")
        }
    }

    private fun route(request: Request): Response {
        // Browsers preflight any cross-origin JSON POST.
        if (request.method == "OPTIONS") return Response(204, "text/plain", ByteArray(0))

        val path = request.path.trimEnd('/').ifEmpty { "/" }
        return when {
            path == "/" -> Response(200, "text/html; charset=utf-8", WebUiPage.HTML.toByteArray())
            path == "/internal/ping" -> jsonResponse(200, JSONObject())
            // Extra routes for our own page: the tag library and host link state
            // are app concepts, not part of the WebUI API.
            path == "/localdream/tags" -> jsonResponse(200, handleTags())
            path == "/localdream/state" -> jsonResponse(200, handleLocalState())
            path == "/sdapi/v1/options" && request.method == "GET" -> jsonResponse(200, handleOptions())
            path == "/sdapi/v1/options" -> jsonResponse(200, handleSetOptions(request.body))
            path == "/sdapi/v1/sd-models" -> jsonResponse(200, JSONArray(handleModels()))
            path == "/sdapi/v1/samplers" -> jsonResponse(200, JSONArray(handleSamplers()))
            path == "/sdapi/v1/upscalers" -> jsonResponse(200, JSONArray(handleUpscalers()))
            path == "/sdapi/v1/progress" -> jsonResponse(200, handleProgress())
            path == "/sdapi/v1/txt2img" -> jsonResponse(200, handleGenerate(request.body, img2img = false))
            path == "/sdapi/v1/img2img" -> jsonResponse(200, handleGenerate(request.body, img2img = true))
            path == "/sdapi/v1/extra-single-image" -> jsonResponse(200, handleUpscale(request.body))
            path == "/sdapi/v1/interrupt" -> {
                jobInterrupted = true
                jsonResponse(200, JSONObject())
            }

            path == "/sdapi/v1/unload-checkpoint" -> {
                connection.stopModel()
                jsonResponse(200, JSONObject())
            }

            path == "/sdapi/v1/reload-checkpoint" -> {
                val model = connection.state.value.status?.servingModelId
                if (model != null) {
                    val studio = connection.state.value
                    connection.startModel(model, studio.status?.width ?: 512, studio.status?.height ?: 512)
                }
                jsonResponse(200, JSONObject())
            }

            else -> jsonResponse(404, JSONObject().put("detail", "不支持的接口：$path"))
        }
    }

    // ---------------- A1111 routes mapped onto local-dream ----------------

    private fun handleOptions(): JSONObject = JSONObject().apply {
        put("sd_model_checkpoint", connection.state.value.status?.servingModelId ?: JSONObject.NULL)
        put("sd_vae", "Automatic")
        put("samples_format", "png")
    }

    /** Tag library, used by the prompt page of our own front-end. */
    private fun handleTags(): JSONObject {
        val tags = promptLibrary.library.value.tags
        return JSONObject().apply {
            put("categories", JSONArray(tags.map { it.category }.distinct().sorted()))
            put(
                "tags",
                JSONArray().apply {
                    tags.forEach {
                        put(
                            JSONObject()
                                .put("category", it.category)
                                .put("zh", it.zh)
                                .put("en", it.en),
                        )
                    }
                },
            )
        }
    }

    /** Host link + sampler table, so the page can explain what is available. */
    private fun handleLocalState(): JSONObject {
        val conn = connection.state.value
        return JSONObject().apply {
            put("connected", conn.phase == ConnPhase.CONNECTED)
            put("host", conn.host)
            put("serving_model", conn.status?.servingModelId ?: JSONObject.NULL)
            put("width", conn.status?.width ?: JSONObject.NULL)
            put("height", conn.status?.height ?: JSONObject.NULL)
        }
    }

    private fun handleModels(): List<JSONObject> =
        connection.state.value.catalog?.models.orEmpty().map { model ->
            JSONObject().apply {
                put("title", model.id)
                put("model_name", model.name)
                put("hash", "")
                put("sha256", "")
                put("filename", model.description.ifBlank { model.id })
                put("config", JSONObject.NULL)
            }
        }

    private fun handleSamplers(): List<JSONObject> = SAMPLERS.map { (id, name) ->
        JSONObject().apply {
            put("name", name)
            put("aliases", JSONArray().put(id))
            put("options", JSONObject())
        }
    }

    private fun handleUpscalers(): List<JSONObject> =
        connection.state.value.catalog?.upscalers.orEmpty().map { upscaler ->
            JSONObject().apply {
                put("name", upscaler.id)
                put("model_name", upscaler.id)
                put("model_path", upscaler.path)
                put("model_url", JSONObject.NULL)
                put("scale", 4)
            }
        }

    private fun handleProgress(): JSONObject {
        val progress = when {
            jobTotal > 0 -> jobStep.toDouble() / jobTotal
            jobActive -> 0.0
            else -> 1.0
        }
        return JSONObject().apply {
            put("progress", progress)
            put("eta_relative", 0)
            put(
                "state",
                JSONObject().apply {
                    put("skipped", false)
                    put("interrupted", jobInterrupted)
                    put("job", jobKind)
                    put("job_count", if (jobActive) 1 else 0)
                    put("job_no", 0)
                    put("sampling_step", jobStep)
                    put("sampling_steps", jobTotal)
                },
            )
            put("current_image", jobImage ?: JSONObject.NULL)
            put("textinfo", JSONObject.NULL)
        }
    }

    private fun handleSetOptions(body: String): JSONObject {
        val json = JSONObject(body)
        val requested = json.optString("sd_model_checkpoint")
        if (requested.isBlank()) return JSONObject()

        val catalog = connection.state.value.catalog
            ?: throw ApiException(503, "尚未获取到模型列表")
        val model = catalog.models.firstOrNull { it.id == requested }
            ?: catalog.models.firstOrNull { it.name == requested }
            ?: catalog.models.firstOrNull { requested.contains(it.name) || it.id.contains(requested) }
            ?: throw ApiException(404, "找不到模型：$requested")

        val current = connection.state.value.status
        val width = current?.width ?: 512
        val height = current?.height ?: 512

        connection.startModel(model.id, width, height)
        // WebUI clients expect this call to return once the model is usable.
        runBlocking { awaitModel(model.id) }
        return JSONObject()
    }

    private suspend fun awaitModel(modelId: String) {
        val deadline = System.currentTimeMillis() + MODEL_SWITCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = connection.state.value.status
            if (status?.servingModelId == modelId && status.state == RemoteProtocol.STATE_RUNNING) return
            if (status?.state == RemoteProtocol.STATE_ERROR) {
                throw ApiException(500, "模型加载失败：${status.message ?: "未知错误"}")
            }
            delay(1_000)
        }
        throw ApiException(504, "模型切换超时")
    }

    private fun handleGenerate(body: String, img2img: Boolean): JSONObject {
        val json = JSONObject(body)
        val host = connection.generationHost
        val phase = connection.state.value.phase
        if (host == null || phase != ConnPhase.CONNECTED) {
            throw ApiException(503, "尚未连接到 local-dream 主机")
        }
        val status = connection.state.value.status
        if (status?.state != RemoteProtocol.STATE_RUNNING || status.servingModelId == null) {
            throw ApiException(409, "模型未启动：请先在 App 的「模型」页启动模型")
        }

        val initImage = if (img2img) {
            json.optJSONArray("init_images")?.optString(0)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        val request = GenerateRequest(
            prompt = json.optString("prompt"),
            negativePrompt = json.optString("negative_prompt"),
            steps = json.optInt("steps", 20).coerceIn(1, 150),
            cfg = json.optDouble("cfg_scale", 7.0).toFloat(),
            width = status.width ?: json.optInt("width", 512),
            height = status.height ?: json.optInt("height", 512),
            scheduler = samplerIdOf(json.optString("sampler_name")),
            seed = json.optLong("seed", -1L).takeIf { it >= 0L },
            showDiffusionProcess = true,
            showDiffusionStride = 1,
            denoiseStrength = json.optDouble("denoising_strength", 0.6).toFloat(),
            image = initImage?.let(::stripDataUri),
        )

        val kind = if (img2img) "img2img" else "txt2img"
        val complete = runGeneration(host, request, kind)
        val bitmap = decodeResult(complete)
            ?: throw ApiException(500, "生成结果解码失败")

        return JSONObject().apply {
            put("images", JSONArray().put(toPngBase64(bitmap)))
            put("parameters", JSONObject().put("prompt", request.prompt))
            put("info", JSONObject().put("seed", complete.seed ?: -1L).toString())
        }
    }

    /** Runs one generation, streaming step progress into the shared job state. */
    private fun runGeneration(
        host: String,
        request: GenerateRequest,
        kind: String,
    ): GenerationEvent.Complete = generationLock.withLock {
        jobActive = true
        jobInterrupted = false
        jobKind = kind
        jobStep = 0
        jobTotal = request.steps
        jobImage = null

        try {
            val result = runBlocking {
                var complete: GenerationEvent.Complete? = null
                GenerationClient.generate(host, request).collect { event ->
                    when (event) {
                        is GenerationEvent.Progress -> {
                            jobStep = event.step
                            jobTotal = event.totalSteps
                            jobImage = event.imageBase64
                        }

                        is GenerationEvent.Complete -> complete = event
                    }
                    if (jobInterrupted) throw ApiException(499, "已中断")
                }
                complete
            }
            result ?: throw ApiException(500, "生成未返回结果")
        } finally {
            jobActive = false
            jobImage = null
        }
    }

    private fun handleUpscale(body: String): JSONObject {
        val json = JSONObject(body)
        val host = connection.generationHost ?: throw ApiException(503, "尚未连接到 local-dream 主机")
        val encoded = json.optString("image").takeIf { it.isNotBlank() }
            ?: throw ApiException(400, "缺少 image")
        val source = decodeBase64(stripDataUri(encoded)) ?: throw ApiException(400, "image 解码失败")

        val upscalers = connection.state.value.catalog?.upscalers.orEmpty()
        if (upscalers.isEmpty()) throw ApiException(409, "主机上没有可用的放大器")
        val requested = json.optString("upscaler_1")
        val upscaler = upscalers.firstOrNull { it.id == requested } ?: upscalers.first()

        val result = runBlocking {
            GenerationClient.upscale(host, source, upscaler.path)
        }.getOrElse { throw ApiException(500, "超分失败：${it.message}") }

        return JSONObject().apply {
            put("image", toPngBase64(result))
            put("html_info", "")
        }
    }

    // ---------------- helpers ----------------

    private fun decodeResult(event: GenerationEvent.Complete): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(event.imageBase64)
        if (event.format == "raw") {
            val width = event.width
            val height = event.height
            val channels = event.channels
            if (width <= 0 || height <= 0 || (channels != 3 && channels != 4)) return null
            val pixels = IntArray(width * height)
            var src = 0
            for (i in pixels.indices) {
                val r = bytes[src].toInt() and 0xFF
                val g = bytes[src + 1].toInt() and 0xFF
                val b = bytes[src + 2].toInt() and 0xFF
                val a = if (channels == 4) bytes[src + 3].toInt() and 0xFF else 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                src += channels
            }
            androidx.core.graphics.createBitmap(width, height).also {
                it.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    private fun decodeBase64(value: String): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(value)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun toPngBase64(bitmap: Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.getEncoder().encodeToString(stream.toByteArray())
    }

    private data class Request(
        val method: String,
        val path: String,
        val body: String,
    )

    private data class Response(val code: Int, val contentType: String, val bytes: ByteArray)

    private fun jsonResponse(code: Int, payload: Any): Response =
        Response(code, "application/json", payload.toString().toByteArray())

    private fun readRequest(input: BufferedInputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim().lowercase()
                if (name == "content-length") {
                    contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
                }
            }
        }
        if (contentLength > MAX_BODY_BYTES) throw ApiException(413, "请求体过大")

        val body = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buffer, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return Request(method, path, body)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (builder.isEmpty()) null else builder.toString()
            if (c == '\n'.code) return builder.toString()
            if (c != '\r'.code) builder.append(c.toChar())
            if (builder.length > MAX_LINE_LENGTH) throw ApiException(431, "请求行过长")
        }
    }

    private fun write(socket: Socket, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ${response.code} ${reasonOf(response.code)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.bytes.size}\r\n")
            // Anything on the LAN may drive this, including browser pages.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output = socket.getOutputStream()
        output.write(head.toByteArray())
        output.write(response.bytes)
        output.flush()
    }

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        499 -> "Client Closed Request"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Error"
    }

    private fun localAddresses(port: Int): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$port" }
            .distinct()
    }.getOrElse { emptyList() }

    /** Kept so the server can be restarted from settings without a new scope. */
    fun restart(port: Int) {
        scope.launch {
            stop()
            start(port)
        }
    }

    private companion object {
        const val TAG = "WebUiServer"
        const val WORKER_COUNT = 4
        const val SOCKET_TIMEOUT_MS = 30_000
        const val MAX_BODY_BYTES = 64 * 1024 * 1024
        const val MAX_LINE_LENGTH = 8 * 1024
        const val MODEL_SWITCH_TIMEOUT_MS = 180_000L

        /** Our samplers, published under the names A1111 clients expect. */
        val SAMPLERS = listOf(
            "dpm" to "DPM++ 2M",
            "dpm_karras" to "DPM++ 2M Karras",
            "dpm_sde" to "DPM++ 2M SDE",
            "dpm_sde_karras" to "DPM++ 2M SDE Karras",
            "euler_a" to "Euler a",
            "euler_a_karras" to "Euler a",
            "euler" to "Euler",
            "euler_karras" to "Euler",
            "lcm" to "LCM",
        )

        fun samplerIdOf(a1111Name: String): String =
            SAMPLERS.firstOrNull { it.second == a1111Name }?.first ?: "euler_a"

        fun stripDataUri(value: String): String =
            if (value.startsWith("data:")) value.substringAfter("base64,", value) else value
    }
}