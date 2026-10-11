package io.github.localdream.frontend.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Controller-side client for a local-dream host's control API.
 *
 * Only covers the small JSON control endpoints; generation, tokenize and
 * health-check traffic goes straight to the host's native backend port via
 * [generationHost].
 */
class RemoteApiClient(
    val host: String,
    val port: Int = RemoteProtocol.CONTROL_PORT,
) {
    private val baseUrl = "http://$host:$port"

    /** host:port of the native backend on the host device. */
    val generationHost: String = "$host:${RemoteProtocol.GENERATION_PORT}"

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Status polling gets its own short timeout. The host serves its control
     * API from a single worker thread on purpose, so a poll that lingers is not
     * just useless — it occupies the queue that select/stop need. Failing fast
     * and retrying later keeps that queue free.
     */
    private val statusClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(STATUS_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(STATUS_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchInfo(): RemoteHostInfo? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_INFO)?.let { RemoteHostInfo.fromJson(it) }
        }.getOrNull()
    }

    suspend fun fetchCatalog(): RemoteCatalog? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_MODELS)?.let { RemoteCatalog.fromJson(it) }
        }.getOrNull()
    }

    suspend fun selectModel(modelId: String, width: Int, height: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("model_id", modelId)
                put("width", width)
                put("height", height)
            }
            post(RemoteProtocol.PATH_SELECT, body) != null
        }.getOrDefault(false)
    }

    suspend fun fetchStatus(): RemoteHostStatus? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_STATUS, statusClient)?.let { RemoteHostStatus.fromJson(it) }
        }.getOrNull()
    }

    /**
     * Asks the host to stop its backend, but only if [modelId] is still the
     * host's current selection; a stop that arrives after a newer /select is
     * ignored host-side.
     */
    suspend fun stopBackend(modelId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            post(RemoteProtocol.PATH_STOP, JSONObject().put("model_id", modelId)) != null
        }.getOrDefault(false)
    }

    /** Direct health probe against the native backend on the host. */
    suspend fun checkGenerationHealth(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("http://$generationHost/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun get(path: String, httpClient: OkHttpClient = client): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .get()
            .build()
        return execute(request, httpClient)
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        return execute(request, client)
    }

    private fun execute(request: Request, httpClient: OkHttpClient): JSONObject? =
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val payload = response.body?.string() ?: return null
            runCatching { JSONObject(payload) }.getOrNull()
        }

    companion object {
        private const val CONNECT_TIMEOUT_S = 4L
        // select/stop legitimately take a while: they start or reap a backend
        // process, and the host serialises every route through one worker.
        private const val READ_TIMEOUT_S = 30L

        private const val STATUS_CONNECT_TIMEOUT_S = 3L
        private const val STATUS_READ_TIMEOUT_S = 8L
    }
}