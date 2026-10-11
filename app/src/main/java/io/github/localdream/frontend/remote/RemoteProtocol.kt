package io.github.localdream.frontend.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire protocol of the local-dream "controlled mode" (host) backend.
 *
 * The host exposes two ports:
 *  - CONTROL_PORT: a small JSON API (model catalog, model activation, status).
 *  - GENERATION_PORT: the native inference backend (tokenize / generate / health).
 *
 * This app is a pure controller: it never starts a backend itself, it only
 * drives a host running on this device or another one on the LAN.
 */
object RemoteProtocol {
    const val CONTROL_PORT = 8808
    const val GENERATION_PORT = 8081
    const val PROTOCOL_VERSION = 1
    const val APP_ID = "localdream"

    const val PATH_INFO = "/info"
    const val PATH_MODELS = "/models"
    const val PATH_SELECT = "/select"
    const val PATH_STATUS = "/status"
    const val PATH_STOP = "/stop"

    // Pseudo model id accepted by /select: starts the host backend in standalone
    // upscaling mode (no diffusion model loaded).
    const val UPSCALER_MODEL_ID = "__upscaler__"

    // Backend states reported by /status.
    const val STATE_IDLE = "idle"
    const val STATE_STARTING = "starting"
    const val STATE_RUNNING = "running"
    const val STATE_ERROR = "error"
}

/** Identity block returned by GET /info (the only unauthenticated endpoint). */
data class RemoteHostInfo(
    val protocol: Int,
    val version: String,
    val deviceName: String,
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteHostInfo? {
            if (json.optString("app") != RemoteProtocol.APP_ID) return null
            return RemoteHostInfo(
                protocol = json.optInt("protocol", 0),
                version = json.optString("version"),
                deviceName = json.optString("device"),
            )
        }
    }
}

/** Per-model resolved default parameters shipped in the catalog. */
data class RemoteModelDefaults(
    val prompt: String,
    val negativePrompt: String,
    val steps: Float,
    val cfg: Float,
    val scheduler: String,
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteModelDefaults = RemoteModelDefaults(
            prompt = json.optString("prompt"),
            negativePrompt = json.optString("negative_prompt"),
            steps = json.optDouble("steps", 20.0).toFloat(),
            cfg = json.optDouble("cfg", 7.0).toFloat(),
            scheduler = json.optString("scheduler", "dpm"),
        )
    }
}

/** One installed model on the host, as listed by GET /models. */
data class RemoteModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val runOnCpu: Boolean,
    val isSdxl: Boolean,
    val isAnima: Boolean,
    val ditKind: String,
    val isCustom: Boolean,
    val generationSize: Int,
    val defaults: RemoteModelDefaults,
    // Width/height pairs usable by this model on the host (SD1.5 NPU patch
    // resolutions). Empty for models with a free or fixed canvas.
    val resolutions: List<Pair<Int, Int>>,
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteModelInfo? {
            val id = json.optString("id")
            if (id.isEmpty()) return null
            val resolutions = mutableListOf<Pair<Int, Int>>()
            val array = json.optJSONArray("resolutions") ?: JSONArray()
            for (i in 0 until array.length()) {
                val pair = array.optJSONArray(i) ?: continue
                val w = pair.optInt(0)
                val h = pair.optInt(1)
                if (w > 0 && h > 0) resolutions.add(Pair(w, h))
            }
            return RemoteModelInfo(
                id = id,
                name = json.optString("name", id),
                description = json.optString("description"),
                runOnCpu = json.optBoolean("run_on_cpu", false),
                isSdxl = json.optBoolean("is_sdxl", false),
                isAnima = json.optBoolean("is_anima", false),
                ditKind = json.optString("dit_kind"),
                isCustom = json.optBoolean("is_custom", false),
                generationSize = json.optInt("generation_size", 512),
                defaults = RemoteModelDefaults.fromJson(
                    json.optJSONObject("defaults") ?: JSONObject(),
                ),
                resolutions = resolutions,
            )
        }
    }
}

/** One installed upscaler on the host. */
data class RemoteUpscalerInfo(
    val id: String,
    val path: String,
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteUpscalerInfo? {
            val id = json.optString("id")
            val path = json.optString("path")
            if (id.isEmpty() || path.isEmpty()) return null
            return RemoteUpscalerInfo(id, path)
        }
    }
}

/** Full catalog returned by GET /models. */
data class RemoteCatalog(
    val useImg2img: Boolean,
    val models: List<RemoteModelInfo>,
    val upscalers: List<RemoteUpscalerInfo>,
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteCatalog {
            val models = mutableListOf<RemoteModelInfo>()
            val array = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                RemoteModelInfo.fromJson(obj)?.let { models.add(it) }
            }
            val upscalers = mutableListOf<RemoteUpscalerInfo>()
            val upscalerArray = json.optJSONArray("upscalers") ?: JSONArray()
            for (i in 0 until upscalerArray.length()) {
                val obj = upscalerArray.optJSONObject(i) ?: continue
                RemoteUpscalerInfo.fromJson(obj)?.let { upscalers.add(it) }
            }
            return RemoteCatalog(
                useImg2img = json.optBoolean("use_img2img", true),
                models = models,
                upscalers = upscalers,
            )
        }
    }
}

/** Backend state snapshot returned by GET /status. */
data class RemoteHostStatus(
    val servingModelId: String?,
    val state: String,
    val message: String?,
    val errorModelId: String?,
    // Resolution the backend was started for; a full (model, width, height)
    // match is required before the controller declares the backend Ready.
    val width: Int?,
    val height: Int?,
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteHostStatus = RemoteHostStatus(
            servingModelId = json.optString("serving_model_id").takeIf {
                it.isNotEmpty() && !json.isNull("serving_model_id")
            },
            state = json.optString("state", RemoteProtocol.STATE_IDLE),
            message = json.optString("message").takeIf {
                it.isNotEmpty() && !json.isNull("message")
            },
            errorModelId = json.optString("error_model_id").takeIf {
                it.isNotEmpty() && !json.isNull("error_model_id")
            },
            width = json.optInt("width").takeIf { it > 0 && !json.isNull("width") },
            height = json.optInt("height").takeIf { it > 0 && !json.isNull("height") },
        )
    }
}