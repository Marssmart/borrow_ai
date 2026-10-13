package org.deer.borrowai.data.service.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.ModelSetupPhase

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadingModelSetupServiceTest {
    private val voice = ModelSpec(
        displayName = "Test Voice",
        role = ModelRole.VOICE,
        fileName = ModelCatalog.VOICE_FILE_NAME,
        downloadUrl = "https://example.test/voice.bin",
        sizeBytes = 40_000L,
    )
    private val llm = ModelSpec(
        displayName = "Test LLM",
        role = ModelRole.LLM,
        fileName = ModelCatalog.LLM_FILE_NAME,
        downloadUrl = "https://example.test/llm.gguf",
        sizeBytes = 90_000L,
    )
    private val tierModels = TierModels(ModelTier.LEGACY, voice, llm)
    private val modelDirectory: Path = "/app/models".toPath()

    private fun store(fileSystem: FileSystem) = ModelFileStore(modelDirectory, fileSystem)

    private fun service(
        fileSystem: FileSystem,
        downloader: ModelDownloader,
        scope: CoroutineScope,
    ) = DownloadingModelSetupService(
        totalRamBytes = 4L * ModelTierSelector.BYTES_PER_GIB,
        store = store(fileSystem),
        downloader = downloader,
        scope = scope,
        choosingHoldMillis = 0L,
        loadingHoldMillis = 0L,
        modelsForRam = { tierModels },
    )

    @Test
    fun firstLaunchDownloadsBothModelsToStaticPathsAndNextLaunchSkipsThem() = runTest {
        val fileSystem = FakeFileSystem()
        val first = RecordingDownloader(fileSystem, listOf(voice, llm))
        val firstSetup = service(fileSystem, first, backgroundScope)

        firstSetup.prepare()

        assertEquals(ModelSetupPhase.READY, firstSetup.state.value.phase)
        assertEquals(listOf(voice.downloadUrl, llm.downloadUrl), first.requestedUrls)
        assertEquals(40_000L, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
        assertEquals(90_000L, fileSystem.metadata(modelDirectory / ModelCatalog.LLM_FILE_NAME).size)
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))

        val secondDownloader = RecordingDownloader(fileSystem, listOf(voice, llm))
        val secondSetup = service(fileSystem, secondDownloader, backgroundScope)
        secondSetup.prepare()

        assertEquals(ModelSetupPhase.READY, secondSetup.state.value.phase)
        assertEquals(emptyList(), secondDownloader.requestedUrls)
    }

    @Test
    fun namesAreChosenFromTheDeviceTierAndPublishedWhenDownloadsStart() = runTest {
        val fileSystem = FakeFileSystem()
        val downloader = RecordingDownloader(fileSystem, listOf(voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope)

        setup.prepare()

        assertEquals("Test Voice", setup.state.value.voiceModelName)
        assertEquals("Test LLM", setup.state.value.llmModelName)
        assertEquals(1f, setup.state.value.progress)
    }

    @Test
    fun ramDecidesWhichTierModelsAreChosen() = runTest {
        val fileSystem = FakeFileSystem()
        val chosenRam = mutableListOf<Long>()
        val setup = DownloadingModelSetupService(
            totalRamBytes = 12L * ModelTierSelector.BYTES_PER_GIB,
            store = store(fileSystem),
            downloader = RecordingDownloader(fileSystem, listOf(voice, llm)),
            scope = backgroundScope,
            choosingHoldMillis = 0L,
            loadingHoldMillis = 0L,
            modelsForRam = { ram ->
                chosenRam += ram
                tierModels
            },
        )

        setup.prepare()

        assertEquals(listOf(12L * ModelTierSelector.BYTES_PER_GIB), chosenRam)
    }

    @Test
    fun interruptedPartialFileIsDiscardedAndRedownloaded() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(modelDirectory)
        writeText(fileSystem, modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part"), "partial")
        val downloader = RecordingDownloader(fileSystem, listOf(voice, llm))

        service(fileSystem, downloader, backgroundScope).prepare()

        assertEquals(40_000L, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))
    }

    @Test
    fun finalFileWithWrongSizeIsReplaced() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(modelDirectory)
        writeText(fileSystem, modelDirectory / ModelCatalog.VOICE_FILE_NAME, "stale")
        val downloader = RecordingDownloader(fileSystem, listOf(voice, llm))

        service(fileSystem, downloader, backgroundScope).prepare()

        assertEquals(listOf(voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
        assertEquals(40_000L, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
    }

    @Test
    fun incompleteDownloadFailsWithoutLeavingAFinalFile() = runTest {
        val fileSystem = FakeFileSystem()
        val setup = service(fileSystem, RecordingDownloader(fileSystem, listOf(voice, llm), shortBy = 1L), backgroundScope)

        setup.prepare()

        assertEquals(ModelSetupPhase.FAILED, setup.state.value.phase)
        assertEquals(ModelSetupPhase.DOWNLOADING_VOICE, setup.state.value.failedDuring)
        assertFalse(fileSystem.exists(modelDirectory / ModelCatalog.VOICE_FILE_NAME))
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))
    }

    @Test
    fun httpFailureMovesSetupToFailed() = runTest {
        val fileSystem = FakeFileSystem()
        val downloader = object : ModelDownloader {
            override suspend fun download(url: String, destination: Path, onProgress: (Long) -> Unit) {
                throw ModelDownloadException("Model download failed with HTTP 500.")
            }
        }
        val setup = service(fileSystem, downloader, backgroundScope)

        setup.prepare()

        assertEquals(ModelSetupPhase.FAILED, setup.state.value.phase)
        assertNull(fileSystem.metadataOrNull(modelDirectory / ModelCatalog.VOICE_FILE_NAME))
    }

    @Test
    fun stagesFollowTheLoaderOrderAndProgressNeverDecreases() = runTest {
        val fileSystem = FakeFileSystem()
        val phases = mutableListOf<ModelSetupPhase>()
        val progress = mutableListOf<Float>()
        val setup = service(
            fileSystem,
            RecordingDownloader(fileSystem, listOf(voice, llm), chunkBytes = 10_000L),
            backgroundScope,
        )
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            setup.state.collect { state ->
                if (phases.lastOrNull() != state.phase) phases += state.phase
                progress += state.progress
            }
        }

        setup.prepare()
        collector.cancel()

        assertEquals(
            listOf(
                ModelSetupPhase.NOT_STARTED,
                ModelSetupPhase.CHOOSING_MODEL,
                ModelSetupPhase.DOWNLOADING_VOICE,
                ModelSetupPhase.LOADING_VOICE,
                ModelSetupPhase.DOWNLOADING_LLM,
                ModelSetupPhase.READY,
            ),
            phases,
        )
        assertTrue(progress.zipWithNext().all { (previous, next) -> next >= previous })
    }

    private fun writeText(fileSystem: FileSystem, path: Path, text: String) {
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.writeUtf8(text)
        } finally {
            sink.close()
        }
    }

    private class RecordingDownloader(
        private val fileSystem: FileSystem,
        private val specs: List<ModelSpec>,
        private val shortBy: Long = 0L,
        private val chunkBytes: Long = 16_000L,
    ) : ModelDownloader {
        val requestedUrls = mutableListOf<String>()

        override suspend fun download(url: String, destination: Path, onProgress: (Long) -> Unit) {
            requestedUrls += url
            val target = specs.first { it.downloadUrl == url }.sizeBytes - shortBy
            val sink = fileSystem.sink(destination).buffer()
            try {
                var written = 0L
                while (written < target) {
                    val chunk = minOf(chunkBytes, target - written)
                    sink.write(ByteArray(chunk.toInt()))
                    written += chunk
                    onProgress(written)
                }
            } finally {
                sink.close()
            }
        }
    }
}
