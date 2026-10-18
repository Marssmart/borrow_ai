package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * Model files are hundreds of MB over mobile networks. OkHttp's default 10 s read timeout drops
 * downloads that stall briefly, so allow longer socket stalls and no overall request deadline.
 */
actual fun createPlatformModelHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        requestTimeoutMillis = null
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 30_000L
private const val SOCKET_TIMEOUT_MILLIS = 60_000L
