package org.deer.borrowai.data.service.model

import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelFileStoreTest {
    private val directory: Path = "/app/models".toPath()
    private val contents = ByteArray(4_096) { (it % 239).toByte() }
    private val model = ModelSpec(
        displayName = "Test Voice",
        role = ModelRole.VOICE,
        fileName = ModelCatalog.VOICE_FILE_NAME,
        downloadUrl = "https://example.test/voice.bin",
        revision = "test-revision",
        sizeBytes = contents.size.toLong(),
        sha256 = Buffer().write(contents).readByteString().sha256().hex(),
    )

    @Test
    fun missingVerificationRecordRehashesAndWritesARecord() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(directory)
        writeFile(fileSystem, directory / model.fileName, contents)
        var hashes = 0
        val store = ModelFileStore(directory, fileSystem) { path ->
            hashes++
            hash(fileSystem, path)
        }

        assertTrue(store.isPresent(model))
        assertEquals(1, hashes)
        assertTrue(fileSystem.exists(store.verifiedRecordPath(model)))
    }

    @Test
    fun staleVerificationRecordRehashesAndRefreshesTheRecord() {
        val fileSystem = FakeFileSystem()
        val store = ModelFileStore(directory, fileSystem)
        installVerifiedFile(fileSystem, store)
        val fileMetadata = assertNotNull(fileSystem.metadataOrNull(store.finalPath(model)))
        val actualModifiedAt = assertNotNull(fileMetadata.lastModifiedAtMillis)
        writeText(
            fileSystem,
            store.verifiedRecordPath(model),
            "$RECORD_VERSION\n${model.sizeBytes}\n${actualModifiedAt + 1}\n${model.sha256}\n",
        )
        var hashes = 0
        val checkingStore = ModelFileStore(directory, fileSystem) { path ->
            hashes++
            hash(fileSystem, path)
        }

        assertTrue(checkingStore.isPresent(model))
        assertEquals(1, hashes)
        assertEquals(
            "$RECORD_VERSION\n${model.sizeBytes}\n$actualModifiedAt\n${model.sha256}\n",
            readText(fileSystem, checkingStore.verifiedRecordPath(model)),
        )
    }

    @Test
    fun matchingVerificationRecordSkipsRehashing() {
        val fileSystem = FakeFileSystem()
        val store = ModelFileStore(directory, fileSystem)
        installVerifiedFile(fileSystem, store)
        var hashes = 0
        val checkingStore = ModelFileStore(directory, fileSystem) { path ->
            hashes++
            hash(fileSystem, path)
        }

        assertTrue(checkingStore.isPresent(model))
        assertEquals(0, hashes)
    }

    @Test
    fun matchingPartialIdentityPreservesTheResumePrefix() {
        val fileSystem = FakeFileSystem()
        val store = ModelFileStore(directory, fileSystem)
        store.prepareDirectory()
        store.preparePartial(model)
        writeFile(fileSystem, store.partialPath(model), contents.copyOfRange(0, 512))

        store.preparePartial(model)

        assertEquals(512L, fileSystem.metadataOrNull(store.partialPath(model))?.size)
    }

    @Test
    fun partialFromDifferentRevisionIsDiscardedAndIdentityIsUpdated() {
        val fileSystem = FakeFileSystem()
        val store = ModelFileStore(directory, fileSystem)
        store.prepareDirectory()
        val priorModel = model.copy(
            downloadUrl = "https://example.test/old-voice.bin",
            revision = "old-revision",
        )
        store.preparePartial(priorModel)
        writeFile(fileSystem, store.partialPath(priorModel), contents.copyOfRange(0, 512))

        store.preparePartial(model)

        assertFalse(fileSystem.exists(store.partialPath(model)))
        assertEquals(
            "1\n${model.downloadUrl}\n${model.revision}\n${model.sizeBytes}\n${model.sha256}\n",
            readText(fileSystem, store.partialIdentityPath(model)),
        )
    }

    @Test
    fun unidentifiedLegacyPartialIsDiscardedBeforeResume() {
        val fileSystem = FakeFileSystem()
        val store = ModelFileStore(directory, fileSystem)
        fileSystem.createDirectories(directory)
        writeFile(fileSystem, store.partialPath(model), contents.copyOfRange(0, 512))

        store.preparePartial(model)

        assertFalse(fileSystem.exists(store.partialPath(model)))
        assertTrue(fileSystem.exists(store.partialIdentityPath(model)))
    }

    @Test
    fun staleRecordDoesNotTrustCorruptedFile() {
        val fileSystem = FakeFileSystem()
        val store = ModelFileStore(directory, fileSystem)
        installVerifiedFile(fileSystem, store)
        writeFile(fileSystem, store.finalPath(model), ByteArray(contents.size) { 1 })
        val metadata = assertNotNull(fileSystem.metadataOrNull(store.finalPath(model)))
        val modifiedAt = assertNotNull(metadata.lastModifiedAtMillis)
        writeText(
            fileSystem,
            store.verifiedRecordPath(model),
            "$RECORD_VERSION\n${model.sizeBytes}\n${modifiedAt + 1}\n${model.sha256}\n",
        )
        var hashes = 0
        val checkingStore = ModelFileStore(directory, fileSystem) { path ->
            hashes++
            hash(fileSystem, path)
        }

        assertFalse(checkingStore.isPresent(model))
        assertEquals(1, hashes)
        assertFalse(fileSystem.exists(checkingStore.verifiedRecordPath(model)))
    }

    private fun installVerifiedFile(fileSystem: FakeFileSystem, store: ModelFileStore) {
        fileSystem.createDirectories(directory)
        writeFile(fileSystem, store.partialPath(model), contents)
        store.commit(model, model.sha256)
    }

    private fun writeFile(fileSystem: FileSystem, path: Path, bytes: ByteArray) {
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
    }

    private fun writeText(fileSystem: FileSystem, path: Path, text: String) {
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.writeUtf8(text)
        } finally {
            sink.close()
        }
    }

    private fun readText(fileSystem: FileSystem, path: Path): String {
        val source = fileSystem.source(path).buffer()
        try {
            return source.readUtf8()
        } finally {
            source.close()
        }
    }

    private fun hash(fileSystem: FileSystem, path: Path): String {
        val source = fileSystem.source(path).buffer()
        try {
            return source.readByteString().sha256().hex()
        } finally {
            source.close()
        }
    }

    private companion object {
        const val RECORD_VERSION = "1"
    }
}
