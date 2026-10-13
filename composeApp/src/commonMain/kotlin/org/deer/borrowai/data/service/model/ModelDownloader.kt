package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import okio.FileSystem
import okio.Path
import okio.buffer

/** Streams [url] into [destination], reporting the cumulative byte count. */
interface ModelDownloader {
    suspend fun download(url: String, destination: Path, onProgress: (bytesDownloaded: Long) -> Unit)
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
    ) {
        client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) {
                throw ModelDownloadException("Model download failed with HTTP ${response.status.value}.")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_BYTES)
            val sink = fileSystem.sink(destination).buffer()
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
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
