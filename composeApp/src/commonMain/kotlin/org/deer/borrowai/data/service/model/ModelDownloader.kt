package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer

/**
 * Streams [url] into [destination] and returns the lowercase hex SHA-256 of the whole destination file.
 * An existing partial file is continued with an HTTP Range request; if the server ignores the range the
 * download restarts from zero.
 */
interface ModelDownloader {
    suspend fun download(url: String, destination: Path, onProgress: (bytesDownloaded: Long) -> Unit): String
}

class ModelDownloadException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ModelStorageException(message: String, cause: Throwable) : IllegalStateException(message, cause)

class KtorModelDownloader(
    private val client: HttpClient,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ModelDownloader {
    override suspend fun download(
        url: String,
        destination: Path,
        onProgress: (bytesDownloaded: Long) -> Unit,
    ): String {
        val existingBytes = fileSystem.metadataOrNull(destination)?.size ?: 0L
        return try {
            client.prepareGet(url) {
                if (existingBytes > 0L) header(HttpHeaders.Range, "bytes=$existingBytes-")
            }.execute { response ->
                if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable && existingBytes > 0L) {
                    try {
                        fileSystem.delete(destination, mustExist = false)
                    } catch (error: IOException) {
                        throw ModelStorageException("Unable to discard the invalid partial model file.", error)
                    }
                    throw ModelDownloadException("The partial model file was invalid and will restart on the next attempt.")
                }
                if (!response.status.isSuccess()) {
                    val status = response.status.value
                    throw ModelDownloadException(
                        "Model download failed with HTTP $status.",
                        retryable = status == HTTP_REQUEST_TIMEOUT ||
                            status == HTTP_TOO_MANY_REQUESTS ||
                            status in HTTP_SERVER_ERROR_RANGE,
                    )
                }
                val resumed = existingBytes > 0L && response.status == HttpStatusCode.PartialContent
                val base = if (resumed) existingBytes else 0L
                if (resumed) {
                    writeBody(response, destination, append = true, base = base, onProgress = onProgress)
                    try {
                        digestOf(destination)
                    } catch (error: IOException) {
                        throw ModelStorageException("Unable to verify the resumed model file.", error)
                    }
                } else {
                    writeBody(response, destination, append = false, base = 0L, onProgress = onProgress)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ModelDownloadException) {
            throw error
        } catch (error: ModelStorageException) {
            throw error
        } catch (error: IOException) {
            throw ModelDownloadException("Model download was interrupted by a network I/O error.", retryable = true, cause = error)
        }
    }

    /** Streams the body and returns the digest of the bytes written when the file was started from zero. */
    private suspend fun writeBody(
        response: HttpResponse,
        destination: Path,
        append: Boolean,
        base: Long,
        onProgress: (Long) -> Unit,
    ): String {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_BYTES)
        val rawSink = try {
            if (append) fileSystem.appendingSink(destination) else fileSystem.sink(destination)
        } catch (error: IOException) {
            throw ModelStorageException("Unable to open the partial model file for writing.", error)
        }
        val hashing = HashingSink.sha256(rawSink)
        val sink = hashing.buffer()
        try {
            var total = base
            while (true) {
                val read = try {
                    channel.readAvailable(buffer)
                } catch (error: IOException) {
                    throw ModelDownloadException("Model download was interrupted while reading the network response.", retryable = true, cause = error)
                }
                if (read < 0) break
                try {
                    sink.write(buffer, 0, read)
                } catch (error: IOException) {
                    throw ModelStorageException("Unable to write the partial model file.", error)
                }
                total += read
                onProgress(total)
            }
        } finally {
            try {
                sink.close()
            } catch (error: IOException) {
                throw ModelStorageException("Unable to close the partial model file.", error)
            }
        }
        return hashing.hash.hex()
    }

    /** Re-reads the whole file so a resumed download is verified over the existing prefix and new bytes. */
    private fun digestOf(path: Path): String {
        val hashing = HashingSource.sha256(fileSystem.source(path))
        val source = hashing.buffer()
        try {
            source.readAll(blackholeSink())
        } finally {
            source.close()
        }
        return hashing.hash.hex()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}
