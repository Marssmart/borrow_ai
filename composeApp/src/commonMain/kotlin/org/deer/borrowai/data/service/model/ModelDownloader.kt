package org.deer.borrowai.data.service.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
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
import okio.IOException as OkioIOException
import okio.Path
import okio.blackholeSink
import okio.buffer

/**
 * Streams [url] into [destination] up to [expectedSizeBytes] and returns the lowercase hex SHA-256 of the whole
 * destination file. A response that exceeds the expected size is discarded and fails permanently.
 * An existing partial file is continued with an HTTP Range request; if the server ignores the range the
 * download restarts from zero.
 */
interface ModelDownloader {
    suspend fun download(
        url: String,
        destination: Path,
        expectedSizeBytes: Long,
        onProgress: (bytesDownloaded: Long) -> Unit,
    ): Either<ModelDownloadError, String>
}

/** Why a model download or commit failed. [Network] is retryable; [Model] and [Storage] are not. */
sealed interface ModelDownloadError {
    data class Network(val message: String, val cause: Throwable? = null) : ModelDownloadError

    data class Storage(val cause: Throwable) : ModelDownloadError

    data class Model(val message: String) : ModelDownloadError
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
        expectedSizeBytes: Long,
        onProgress: (bytesDownloaded: Long) -> Unit,
    ): Either<ModelDownloadError, String> = try {
        downloadBytes(url, destination, expectedSizeBytes, onProgress).right()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ModelDownloadException) {
        if (error.retryable) {
            ModelDownloadError.Network(error.message.orEmpty(), error).left()
        } else {
            ModelDownloadError.Model(error.message.orEmpty()).left()
        }
    } catch (error: ModelStorageException) {
        ModelDownloadError.Storage(error).left()
    }

    private suspend fun downloadBytes(
        url: String,
        destination: Path,
        expectedSizeBytes: Long,
        onProgress: (bytesDownloaded: Long) -> Unit,
    ): String {
        require(expectedSizeBytes >= 0L) { "The expected model size cannot be negative." }
        val existingBytes = fileSystem.metadataOrNull(destination)?.size ?: 0L
        return try {
            if (existingBytes > expectedSizeBytes) {
                discardPartial(destination)
                downloadFromStart(url, destination, expectedSizeBytes, onProgress)
            } else if (existingBytes <= 0L) {
                downloadFromStart(url, destination, expectedSizeBytes, onProgress)
            } else {
                val resumedDigest = client.prepareGet(url) {
                    header(HttpHeaders.Range, "bytes=$existingBytes-")
                }.execute { response ->
                    if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                        discardPartial(destination)
                        null
                    } else {
                        ensureSuccessfulResponse(response)
                        if (response.status == HttpStatusCode.PartialContent &&
                            contentRangeStart(response.headers[HttpHeaders.ContentRange]) != existingBytes
                        ) {
                            null
                        } else if (response.status == HttpStatusCode.PartialContent) {
                            writeBody(
                                response,
                                destination,
                                append = true,
                                base = existingBytes,
                                expectedSizeBytes = expectedSizeBytes,
                                onProgress = onProgress,
                            )
                            try {
                                digestOf(destination)
                            } catch (error: OkioIOException) {
                                throw ModelStorageException("Unable to verify the resumed model file.", error)
                            }
                        } else {
                            writeBody(
                                response,
                                destination,
                                append = false,
                                base = 0L,
                                expectedSizeBytes = expectedSizeBytes,
                                onProgress = onProgress,
                            )
                        }
                    }
                }
                resumedDigest ?: run {
                    discardPartial(destination)
                    downloadFromStart(url, destination, expectedSizeBytes, onProgress)
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

    private suspend fun downloadFromStart(
        url: String,
        destination: Path,
        expectedSizeBytes: Long,
        onProgress: (Long) -> Unit,
    ): String = client.prepareGet(url).execute { response ->
        ensureSuccessfulResponse(response)
        writeBody(
            response,
            destination,
            append = false,
            base = 0L,
            expectedSizeBytes = expectedSizeBytes,
            onProgress = onProgress,
        )
    }

    private fun ensureSuccessfulResponse(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val status = response.status.value
        throw ModelDownloadException(
            "Model download failed with HTTP $status.",
            retryable = status == HTTP_REQUEST_TIMEOUT ||
                status == HTTP_TOO_MANY_REQUESTS ||
                status in HTTP_SERVER_ERROR_RANGE,
        )
    }

    private fun contentRangeStart(value: String?): Long? {
        val match = value?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun discardPartial(destination: Path) {
        try {
            fileSystem.delete(destination, mustExist = false)
        } catch (error: OkioIOException) {
            throw ModelStorageException("Unable to discard the invalid partial model file.", error)
        }
    }

    /** Streams the body and returns the digest of the bytes written when the file was started from zero. */
    private suspend fun writeBody(
        response: HttpResponse,
        destination: Path,
        append: Boolean,
        base: Long,
        expectedSizeBytes: Long,
        onProgress: (Long) -> Unit,
    ): String {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_BYTES)
        val rawSink = try {
            if (append) fileSystem.appendingSink(destination) else fileSystem.sink(destination)
        } catch (error: OkioIOException) {
            throw ModelStorageException("Unable to open the partial model file for writing.", error)
        }
        val target = if (append) rawSink else HashingSink.sha256(rawSink)
        val sink = target.buffer()
        var oversized = false
        try {
            var total = base
            while (true) {
                val read = try {
                    channel.readAvailable(buffer)
                } catch (error: IOException) {
                    throw ModelDownloadException("Model download was interrupted while reading the network response.", retryable = true, cause = error)
                }
                if (read < 0) break
                if (read.toLong() > expectedSizeBytes - total) {
                    oversized = true
                    break
                }
                try {
                    sink.write(buffer, 0, read)
                } catch (error: OkioIOException) {
                    throw ModelStorageException("Unable to write the partial model file.", error)
                }
                total += read
                onProgress(total)
            }
        } finally {
            try {
                sink.close()
            } catch (error: OkioIOException) {
                if (oversized) discardPartial(destination)
                throw ModelStorageException("Unable to close the partial model file.", error)
            }
        }
        if (oversized) {
            discardPartial(destination)
            throw ModelDownloadException(
                "Model download exceeded its expected size of $expectedSizeBytes bytes.",
            )
        }
        return (target as? HashingSink)?.hash?.hex().orEmpty()
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
        val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-\d+/(?:\d+|\*)""", RegexOption.IGNORE_CASE)
    }
}
