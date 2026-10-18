package org.deer.borrowai.data.service.model

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorModelDownloaderTest {
    private val url = "https://example.test/model.bin"
    private val destination = "/app/models/voice-model.bin.part".toPath()
    private val body = ByteArray(200_000) { (it % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray): String = Buffer().write(bytes).readByteString().sha256().hex()

    private suspend fun KtorModelDownloader.downloadOrFail(
        url: String,
        destination: Path,
        expectedSizeBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): String = when (val result = download(url, destination, expectedSizeBytes, onProgress)) {
        is Either.Left -> fail("Expected a successful download but got ${result.value}")
        is Either.Right -> result.value
    }

    private suspend fun KtorModelDownloader.downloadError(
        url: String,
        destination: Path,
        expectedSizeBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): ModelDownloadError = when (val result = download(url, destination, expectedSizeBytes, onProgress)) {
        is Either.Left -> result.value
        is Either.Right -> fail("Expected a failed download but got digest ${result.value}")
    }

    private fun FakeFileSystem.writePartial(path: Path, bytes: ByteArray) {
        createDirectories(path.parent!!)
        val sink = sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
    }

    private fun FakeFileSystem.readAll(path: Path): ByteArray {
        val source = source(path).buffer()
        try {
            return source.readByteArray()
        } finally {
            source.close()
        }
    }

    @Test
    fun streamsTheBodyToDestinationAndReturnsItsDigest() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(destination.parent!!)
        val client = HttpClient(MockEngine { respond(content = body, status = HttpStatusCode.OK) })
        val progress = mutableListOf<Long>()

        val digest = KtorModelDownloader(client, fileSystem).downloadOrFail(url, destination, body.size.toLong()) { progress += it }

        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        assertEquals(body.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        client.close()
    }

    @Test
    fun oversizedFreshResponseIsStoppedAndItsPartialIsDiscarded() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(destination.parent!!)
        val client = HttpClient(MockEngine { respond(content = body, status = HttpStatusCode.OK) })
        val progress = mutableListOf<Long>()

        val error = KtorModelDownloader(client, fileSystem).downloadError(
            url,
            destination,
            expectedSizeBytes = body.size.toLong() - 1L,
        ) { progress += it }

        assertIs<ModelDownloadError.Model>(error)
        assertFalse(fileSystem.exists(destination))
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.last() < body.size.toLong())
        client.close()
    }

    @Test
    fun oversizedResumedResponseDiscardsTheExistingPrefix() = runTest {
        val fileSystem = FakeFileSystem()
        val prefixLength = 80_000
        fileSystem.writePartial(destination, body.copyOfRange(0, prefixLength))
        val oversizedSuffix = body.copyOfRange(prefixLength, body.size) + byteArrayOf(42)
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("bytes=$prefixLength-", request.headers[HttpHeaders.Range])
                respond(
                    content = oversizedSuffix,
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentRange,
                        "bytes $prefixLength-${body.lastIndex}/${body.size}",
                    ),
                )
            },
        )

        val error = KtorModelDownloader(client, fileSystem).downloadError(url, destination, body.size.toLong())

        assertIs<ModelDownloadError.Model>(error)
        assertFalse(fileSystem.exists(destination))
        client.close()
    }

    @Test
    fun clientErrorStatusFailsPermanentlyWithoutWritingTheDestination() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(destination.parent!!)
        val client = HttpClient(MockEngine { respond(content = "nope", status = HttpStatusCode.NotFound) })

        val error = KtorModelDownloader(client, fileSystem).downloadError(url, destination, body.size.toLong())

        assertIs<ModelDownloadError.Model>(error)
        assertFalse(fileSystem.exists(destination))
        client.close()
    }

    @Test
    fun timeoutRateLimitAndServerErrorsAreRetryable() = runTest {
        listOf(
            HttpStatusCode.RequestTimeout,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.InternalServerError,
        ).forEach { status ->
            val fileSystem = FakeFileSystem()
            fileSystem.createDirectories(destination.parent!!)
            val client = HttpClient(MockEngine { respond(content = "error", status = status) })
            val error = KtorModelDownloader(client, fileSystem).downloadError(url, destination, body.size.toLong())
            assertIs<ModelDownloadError.Network>(error, "HTTP ${status.value} should be retried")
            client.close()
        }
    }

    @Test
    fun resumesFromTheExistingPrefixWithARangeRequestAndVerifiesTheWholeFile() = runTest {
        val fileSystem = FakeFileSystem()
        val prefixLength = 80_000
        fileSystem.writePartial(destination, body.copyOfRange(0, prefixLength))
        var requestedRange: String? = null
        val client = HttpClient(
            MockEngine { request ->
                requestedRange = request.headers[HttpHeaders.Range]
                respond(
                    content = body.copyOfRange(prefixLength, body.size),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentRange,
                        "bytes $prefixLength-${body.lastIndex}/${body.size}",
                    ),
                )
            },
        )
        val progress = mutableListOf<Long>()

        val digest = KtorModelDownloader(client, fileSystem).downloadOrFail(url, destination, body.size.toLong()) { progress += it }

        assertEquals("bytes=$prefixLength-", requestedRange)
        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        assertEquals(body.size.toLong(), progress.last())
        assertTrue(progress.first() > prefixLength)
        client.close()
    }

    @Test
    fun missingContentRangeRestartsFromZero() = runTest {
        assertInvalidContentRangeRestarts(contentRange = null)
    }

    @Test
    fun mismatchedContentRangeRestartsFromZero() = runTest {
        assertInvalidContentRangeRestarts(contentRange = "bytes 79-199999/200000")
    }

    private suspend fun assertInvalidContentRangeRestarts(contentRange: String?) {
        val fileSystem = FakeFileSystem()
        val prefixLength = 80_000
        fileSystem.writePartial(destination, body.copyOfRange(0, prefixLength))
        var requests = 0
        val client = HttpClient(
            MockEngine { request ->
                requests++
                if (requests == 1) {
                    respond(
                        content = body.copyOfRange(prefixLength, body.size),
                        status = HttpStatusCode.PartialContent,
                        headers = contentRange?.let { headersOf(HttpHeaders.ContentRange, it) }
                            ?: io.ktor.http.Headers.Empty,
                    )
                } else {
                    assertNull(request.headers[HttpHeaders.Range])
                    respond(content = body, status = HttpStatusCode.OK)
                }
            },
        )

        val digest = KtorModelDownloader(client, fileSystem).downloadOrFail(url, destination, body.size.toLong())

        assertEquals(2, requests)
        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        client.close()
    }

    @Test
    fun cancellationPropagatesInsteadOfReturningLeft() = runTest {
        val client = HttpClient(MockEngine { awaitCancellation() })
        val downloader = KtorModelDownloader(client, FakeFileSystem())

        val job = launch { downloader.download(url, destination, body.size.toLong()) {} }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        client.close()
    }

    @Test
    fun serverIgnoringTheRangeRestartsTheFileFromZero() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.writePartial(destination, body.copyOfRange(0, 1_000))
        val client = HttpClient(MockEngine { respond(content = body, status = HttpStatusCode.OK) })

        val digest = KtorModelDownloader(client, fileSystem).downloadOrFail(url, destination, body.size.toLong())

        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        client.close()
    }

    @Test
    fun unsatisfiableRangeDiscardsPartialAndImmediatelyRestartsFromZero() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.writePartial(destination, body.copyOfRange(0, 1_000))
        var requests = 0
        val client = HttpClient(
            MockEngine { request ->
                requests++
                if (requests == 1) {
                    respond(content = "", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                } else {
                    assertNull(request.headers[HttpHeaders.Range])
                    respond(content = body, status = HttpStatusCode.OK)
                }
            },
        )

        val digest = KtorModelDownloader(client, fileSystem).downloadOrFail(url, destination, body.size.toLong())

        assertEquals(2, requests)
        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        client.close()
    }
}
