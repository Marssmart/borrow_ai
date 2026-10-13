package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient

/** Each platform supplies its own HTTP engine for model downloads. */
expect fun createPlatformModelHttpClient(): HttpClient
