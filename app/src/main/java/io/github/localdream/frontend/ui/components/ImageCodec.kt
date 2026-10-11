package io.github.localdream.frontend.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import java.util.Base64
import kotlin.math.max

/**
 * Decodes the images returned by the host backend.
 *
 * The backend sends either a compressed image (jpeg/png, already handled by
 * [BitmapFactory]) or a "raw" packed byte buffer: 3 bytes per pixel (RGB) or
 * 4 bytes (RGBA), row-major, top-to-bottom. Intermediate previews are always
 * jpeg because that is what the request asks for.
 */
object ImageCodec {
    fun decode(
        base64: String,
        format: String,
        width: Int,
        height: Int,
        channels: Int = 3,
    ): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(base64)
        when (format) {
            "raw" -> decodeRaw(bytes, width, height, channels)
            else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    /**
     * PNG-compresses the bitmap and base64-encodes it for upload as an img2img
     * base image. Lossless because the backend re-uses unmasked pixels verbatim.
     */
    fun toBase64Png(bitmap: Bitmap, maxSide: Int = 2048): String {
        val scaled = if (max(bitmap.width, bitmap.height) > maxSide) {
            val ratio = maxSide.toFloat() / max(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.getEncoder().encodeToString(stream.toByteArray())
    }

    private fun decodeRaw(bytes: ByteArray, width: Int, height: Int, channels: Int): Bitmap? {
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
        return createBitmap(width, height).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}