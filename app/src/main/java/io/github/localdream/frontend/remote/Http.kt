package io.github.localdream.frontend.remote

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Single OkHttp instance shared across the app. Per-use-case clients are
 * derived with [OkHttpClient.newBuilder] so they share the dispatcher and
 * connection pool instead of each spawning their own thread pools.
 */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }

    /** Client for the streaming /generate endpoint: no read timeout. */
    val streamingClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}