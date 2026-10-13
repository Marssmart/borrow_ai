package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.buffer

/** Streams [url] into [destination] and returns the lowercase hex SHA-256 of the bytes written. */
interface ModelDownloader {
    suspend fun download(url: String, destination: Path, onProgress: (bytesDownloaded: Long) -> Unit): String
}

class ModelDownloadException(message: String) : IllegalStateException(message)

class KtorModelDownloader(
    private val client: HttpClient,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ModelDownloader {
    override suspend fun download(
        url: String,
        destination: Path,
        onProgress: (bytesDownloaded: Long) -> Unit,
    ): String {
        return client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) {
                throw ModelDownloadException("Model download failed with HTTP ${response.status.value}.")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_BYTES)
            val hashing = HashingSink.sha256(fileSystem.sink(destination))
            val sink = hashing.buffer()
            try {
                var total = 0L
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            } finally {
                sink.close()
            }
            hashing.hash.hex()
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
