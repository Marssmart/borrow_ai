package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createPlatformModelHttpClient(): HttpClient = HttpClient(Darwin)
