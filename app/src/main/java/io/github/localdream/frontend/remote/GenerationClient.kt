package io.github.localdream.frontend.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Parameters of a text-to-image request sent to a host's /generate endpoint. */
data class GenerateRequest(
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val cfg: Float,
    val width: Int,
    val height: Int,
    val scheduler: String,
    val seed: Long? = null,
    val showDiffusionProcess: Boolean = true,
    val showDiffusionStride: Int = 4,
    val denoiseStrength: Float = 1.0f,
    val useOpencl: Boolean = false,
    val aspectRatio: String? = null,
    // Base image for img2img, PNG-compressed and base64 encoded. Lossless on
    // purpose: the backend pastes the unmasked region back verbatim.
    val image: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("prompt", prompt)
        put("negative_prompt", negativePrompt)
        put("steps", steps)
        put("cfg", cfg.toDouble())
        put("width", width)
        put("height", height)
        put("denoise_strength", denoiseStrength.toDouble())
        put("use_opencl", useOpencl)
        put("scheduler", scheduler)
        // Preview frames are tiny base64 JPEGs; the final image stays raw for
        // lossless loopback transport (matching the native client).
        put("preview_format", "jpeg")
        put("show_diffusion_process", showDiffusionProcess)
        put("show_diffusion_stride", showDiffusionStride)
        aspectRatio?.let { put("aspect_ratio", it) }
        seed?.let { put("seed", it) }
        image?.let { put("image", it) }
    }
}

/** One event decoded from the /generate server-sent-event stream. */
sealed interface GenerationEvent {
    data class Progress(
        val step: Int,
        val totalSteps: Int,
        val imageBase64: String?,
        val format: String,
    ) : GenerationEvent

    data class Complete(
        val imageBase64: String,
        val format: String,
        val seed: Long?,
        val width: Int,
        val height: Int,
        val channels: Int,
    ) : GenerationEvent
}

class GenerationException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class TokenizeResult(val count: Int, val maxLength: Int, val overflowOffset: Int)

/**
 * Client for a host's native inference backend (tokenize / generate / health).
 */
object GenerationClient {
    private val shortClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Upscaling a 4x-class image can take a while on device. */
    private val upscaleClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    suspend fun tokenize(backendHost: String, text: String): TokenizeResult? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply { put("prompt", text) }
                .toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("http://$backendHost/tokenize")
                .post(body)
                .build()
            shortClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val payload = response.body?.string() ?: return@withContext null
                val json = JSONObject(payload)
                TokenizeResult(
                    count = json.optInt("count", 0),
                    maxLength = json.optInt("max_length", 77),
                    overflowOffset = json.optInt("overflow_offset", -1),
                )
            }
        }.getOrNull()
    }

    suspend fun checkHealth(backendHost: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("http://$backendHost/health")
                .get()
                .build()
            shortClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Sends raw RGB pixels to the host's /upscale endpoint and decodes the JPEG
     * it returns. [upscalerPath] is the weight file's absolute path on the
     * host, taken from its catalog.
     *
     * The failure reason is kept in the returned [Result] rather than swallowed
     * so the UI can tell the user what actually went wrong.
     */
    suspend fun upscale(
        backendHost: String,
        bitmap: Bitmap,
        upscalerPath: String,
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val rgb = ByteArray(width * height * 3)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                rgb[i * 3] = ((pixel shr 16) and 0xFF).toByte()
                rgb[i * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
                rgb[i * 3 + 2] = (pixel and 0xFF).toByte()
            }

            val request = Request.Builder()
                .url("http://$backendHost/upscale")
                .header("X-Image-Width", width.toString())
                .header("X-Image-Height", height.toString())
                .header("X-Upscaler-Path", upscalerPath)
                .post(rgb.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .build()

            upscaleClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    throw GenerationException("HTTP ${response.code} $detail".trim())
                }
                val bytes = response.body?.bytes() ?: throw GenerationException("响应为空")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw GenerationException("结果解码失败")
            }
        }
    }

    /**
     * Streams a generation from the host backend. Cancelling the collector
     * cancels the underlying HTTP call.
     */
    fun generate(backendHost: String, request: GenerateRequest): Flow<GenerationEvent> = flow {
        val body = request.toJson().toString().toRequestBody("application/json".toMediaTypeOrNull())
        val httpRequest = Request.Builder()
            .url("http://$backendHost/generate")
            .post(body)
            .build()

        val call = Http.streamingClient.newCall(httpRequest)
        val ctx = currentCoroutineContext()
        val cancellationHandle = ctx[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw GenerationException("生成请求失败：HTTP ${response.code}")
                }
                val reader = response.body?.byteStream()?.bufferedReader()
                    ?: throw GenerationException("生成响应为空")
                while (ctx.isActive) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.substring(6).trim()
                    if (data == "[DONE]") break
                    val message = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (message.optString("type")) {
                        "progress" -> emit(
                            GenerationEvent.Progress(
                                step = message.optInt("step"),
                                totalSteps = message.optInt("total_steps"),
                                imageBase64 = message.optString("image").takeIf { it.isNotEmpty() },
                                format = message.optString("format", "raw"),
                            ),
                        )

                        "complete" -> {
                            val image = message.optString("image")
                            if (image.isEmpty()) throw GenerationException("生成结果为空")
                            emit(
                                GenerationEvent.Complete(
                                    imageBase64 = image,
                                    format = message.optString("format", "raw"),
                                    seed = message.optLong("seed", -1).takeIf { it != -1L },
                                    width = message.optInt("width", request.width),
                                    height = message.optInt("height", request.height),
                                    channels = message.optInt("channels", 3),
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }.flowOn(Dispatchers.IO)
}