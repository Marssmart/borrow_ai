package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertTrue

class KtorModelDownloaderTest {
    private val url = "https://example.test/model.bin"
    private val destination = "/app/models/voice-model.bin.part".toPath()
    private val body = ByteArray(200_000) { (it % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray): String = Buffer().write(bytes).readByteString().sha256().hex()

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

        val digest = KtorModelDownloader(client, fileSystem).download(url, destination) { progress += it }

        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        assertEquals(body.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        client.close()
    }

    @Test
    fun clientErrorStatusFailsPermanentlyWithoutWritingTheDestination() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(destination.parent!!)
        val client = HttpClient(MockEngine { respond(content = "nope", status = HttpStatusCode.NotFound) })

        val error = assertFailsWith<ModelDownloadException> {
            KtorModelDownloader(client, fileSystem).download(url, destination) {}
        }

        assertFalse(error.retryable)
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
            val error = assertFailsWith<ModelDownloadException> {
                KtorModelDownloader(client, fileSystem).download(url, destination) {}
            }
            assertTrue(error.retryable, "HTTP ${status.value} should be retried")
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
                )
            },
        )
        val progress = mutableListOf<Long>()

        val digest = KtorModelDownloader(client, fileSystem).download(url, destination) { progress += it }

        assertEquals("bytes=$prefixLength-", requestedRange)
        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        assertEquals(body.size.toLong(), progress.last())
        assertTrue(progress.first() > prefixLength)
        client.close()
    }

    @Test
    fun serverIgnoringTheRangeRestartsTheFileFromZero() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.writePartial(destination, body.copyOfRange(0, 1_000))
        val client = HttpClient(MockEngine { respond(content = body, status = HttpStatusCode.OK) })

        val digest = KtorModelDownloader(client, fileSystem).download(url, destination) {}

        assertContentEquals(body, fileSystem.readAll(destination))
        assertEquals(sha256Hex(body), digest)
        client.close()
    }

    @Test
    fun unsatisfiableRangeDiscardsThePartialFileAndFails() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.writePartial(destination, body.copyOfRange(0, 1_000))
        val client = HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.RequestedRangeNotSatisfiable) })

        assertFailsWith<ModelDownloadException> {
            KtorModelDownloader(client, fileSystem).download(url, destination) {}
        }

        assertFalse(fileSystem.exists(destination))
        client.close()
    }
}
