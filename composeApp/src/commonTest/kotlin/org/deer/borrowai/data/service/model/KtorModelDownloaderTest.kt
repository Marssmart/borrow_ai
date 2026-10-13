package org.deer.borrowai.data.service.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KtorModelDownloaderTest {
    private val url = "https://example.test/model.bin"
    private val destination = "/app/models/voice-model.bin.part".toPath()

    private fun sha256Hex(bytes: ByteArray): String = Buffer().write(bytes).readByteString().sha256().hex()

    @Test
    fun streamsTheBodyToDestinationAndReportsCumulativeProgress() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(destination.parent!!)
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val client = HttpClient(MockEngine { respond(content = body, status = HttpStatusCode.OK) })
        val progress = mutableListOf<Long>()

        val digest = KtorModelDownloader(client, fileSystem).download(url, destination) { progress += it }

        assertEquals(body.size.toLong(), fileSystem.metadata(destination).size)
        assertEquals(sha256Hex(body), digest)
        assertEquals(body.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertFalse(progress.isEmpty())
        client.close()
    }

    @Test
    fun nonSuccessStatusFailsWithoutWritingTheDestination() = runTest {
        val fileSystem = FakeFileSystem()
        val client = HttpClient(MockEngine { respond(content = "nope", status = HttpStatusCode.NotFound) })

        assertFailsWith<ModelDownloadException> {
            KtorModelDownloader(client, fileSystem).download(url, destination) {}
        }

        assertFalse(fileSystem.exists(destination))
        client.close()
    }
}
