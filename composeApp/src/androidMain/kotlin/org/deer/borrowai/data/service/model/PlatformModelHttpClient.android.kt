package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createPlatformModelHttpClient(): HttpClient = HttpClient(OkHttp)
