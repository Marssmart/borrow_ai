package org.deer.borrowai.data.service.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.ModelSetupFailureReason
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupState

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadingModelSetupServiceTest {
    private val live = spec(
        displayName = "Test Live",
        role = ModelRole.LIVE_VOICE,
        fileName = ModelCatalog.LIVE_FILE_NAME,
        url = "https://example.test/live.bin",
        sizeBytes = 50_000L,
    )
    private val voice = spec(
        displayName = "Test Voice",
        role = ModelRole.VOICE,
        fileName = ModelCatalog.VOICE_FILE_NAME,
        url = "https://example.test/voice.bin",
        sizeBytes = 40_000L,
    )
    private val llm = spec(
        displayName = "Test LLM",
        role = ModelRole.LLM,
        fileName = ModelCatalog.LLM_FILE_NAME,
        url = "https://example.test/llm.gguf",
        sizeBytes = 90_000L,
    )
    private val tierModels = TierModels(ModelTier.LEGACY, listOf(live), listOf(voice), llm)
    private val modelDirectory: Path = "/app/models".toPath()

    private fun spec(
        displayName: String,
        role: ModelRole,
        fileName: String,
        url: String,
        sizeBytes: Long,
        sha256: String = zeroDigest(sizeBytes),
    ) = ModelSpec(
        displayName = displayName,
        role = role,
        fileName = fileName,
        downloadUrl = url,
        revision = "test-revision",
        sizeBytes = sizeBytes,
        sha256 = sha256,
    )

    private fun zeroDigest(size: Long): String = sha256Hex(ByteArray(size.toInt()))

    private fun sha256Hex(bytes: ByteArray): String = Buffer().write(bytes).readByteString().sha256().hex()

    private fun store(fileSystem: FileSystem) = ModelFileStore(modelDirectory, fileSystem)

    private fun service(
        fileSystem: FileSystem,
        downloader: ModelDownloader,
        scope: CoroutineScope,
        models: TierModels = tierModels,
        network: ModelNetworkPolicy = FixedNetworkPolicy(true),
    ) = DownloadingModelSetupService(
        totalRamBytes = 4L * ModelTierSelector.BYTES_PER_GIB,
        store = store(fileSystem),
        downloader = downloader,
        network = network,
        scope = scope,
        choosingHoldMillis = 0L,
        loadingHoldMillis = 0L,
        modelsForRam = { models },
    )

    @Test
    fun firstLaunchDownloadsBothModelsToStaticPathsAndNextLaunchSkipsThem() = runTest {
        val fileSystem = FakeFileSystem()
        val first = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val firstSetup = service(fileSystem, first, backgroundScope)

        assertEquals(Unit.right(), firstSetup.prepare())

        assertEquals(ModelSetupPhase.READY, firstSetup.state.value.phase)
        assertEquals(listOf(live.downloadUrl, voice.downloadUrl, llm.downloadUrl), first.requestedUrls)
        assertEquals(40_000L, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
        assertEquals(90_000L, fileSystem.metadata(modelDirectory / ModelCatalog.LLM_FILE_NAME).size)
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))

        val secondDownloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val secondSetup = service(fileSystem, secondDownloader, backgroundScope)
        assertEquals(Unit.right(), secondSetup.prepare())

        assertEquals(ModelSetupPhase.READY, secondSetup.state.value.phase)
        assertEquals(emptyList(), secondDownloader.requestedUrls)
    }

    @Test
    fun liveAndVoiceSharingOneFileDownloadItOnce() = runTest {
        val fileSystem = FakeFileSystem()
        val sharedLive = live.copy(
            fileName = voice.fileName,
            downloadUrl = voice.downloadUrl,
            sizeBytes = voice.sizeBytes,
            sha256 = voice.sha256,
        )
        val models = TierModels(ModelTier.LEGACY, listOf(sharedLive), listOf(voice), llm)
        val downloader = RecordingDownloader(fileSystem, listOf(voice, llm))

        assertEquals(Unit.right(), service(fileSystem, downloader, backgroundScope, models = models).prepare())

        assertEquals(listOf(voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
    }

    @Test
    fun alreadyPresentModelsReachReadyWithoutHolds() = runTest {
        val fileSystem = FakeFileSystem()
        val store = store(fileSystem)
        store.prepareDirectory()
        writeBytes(fileSystem, store.partialPath(live), ByteArray(live.sizeBytes.toInt()))
        check(store.commit(live, live.sha256).isRight())
        writeBytes(fileSystem, store.partialPath(voice), ByteArray(voice.sizeBytes.toInt()))
        check(store.commit(voice, voice.sha256).isRight())
        writeBytes(fileSystem, store.partialPath(llm), ByteArray(llm.sizeBytes.toInt()))
        check(store.commit(llm, llm.sha256).isRight())
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val setup = DownloadingModelSetupService(
            totalRamBytes = 4L * ModelTierSelector.BYTES_PER_GIB,
            store = store,
            downloader = downloader,
            network = FixedNetworkPolicy(true),
            scope = backgroundScope,
            choosingHoldMillis = 60_000L,
            loadingHoldMillis = 60_000L,
            modelsForRam = { tierModels },
        )
        val phases = mutableListOf<ModelSetupPhase>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            setup.state.collect { state ->
                if (phases.lastOrNull() != state.phase) phases += state.phase
            }
        }
        val prepare = launch { setup.prepare() }

        runCurrent()

        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
        assertEquals(emptyList(), downloader.requestedUrls)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(phases.last() == ModelSetupPhase.READY)
        assertFalse(prepare.isActive)
        collector.cancel()
    }

    @Test
    fun ramDecidesWhichTierModelsAreChosen() = runTest {
        val fileSystem = FakeFileSystem()
        val chosenRam = mutableListOf<Long>()
        val setup = DownloadingModelSetupService(
            totalRamBytes = 12L * ModelTierSelector.BYTES_PER_GIB,
            store = store(fileSystem),
            downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm)),
            network = FixedNetworkPolicy(true),
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
    fun interruptedPartialFileIsContinuedByTheDownloader() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(modelDirectory)
        store(fileSystem).preparePartial(voice)
        writeText(fileSystem, modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part"), "partial")
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))

        service(fileSystem, downloader, backgroundScope).prepare()

        assertEquals(40_000L, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))
    }

    @Test
    fun fullSizePartialWithValidDigestIsCommittedWithoutRangeRequest() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(modelDirectory)
        store(fileSystem).preparePartial(voice)
        writeBytes(fileSystem, modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part"), ByteArray(voice.sizeBytes.toInt()))
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope)

        setup.prepare()

        assertEquals(listOf(live.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
        assertEquals(voice.sizeBytes, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))
        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
    }

    @Test
    fun fullSizePartialWithInvalidDigestIsDiscardedAndDownloadedAgain() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(modelDirectory)
        store(fileSystem).preparePartial(voice)
        writeBytes(
            fileSystem,
            modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part"),
            ByteArray(voice.sizeBytes.toInt()) { 1 },
        )
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope)

        setup.prepare()

        assertEquals(listOf(live.downloadUrl, voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
        assertEquals(voice.sizeBytes, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))
        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
    }

    @Test
    fun finalFileWithWrongSizeIsReplaced() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(modelDirectory)
        writeText(fileSystem, modelDirectory / ModelCatalog.VOICE_FILE_NAME, "stale")
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))

        service(fileSystem, downloader, backgroundScope).prepare()

        assertEquals(listOf(live.downloadUrl, voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
        assertEquals(40_000L, fileSystem.metadata(modelDirectory / ModelCatalog.VOICE_FILE_NAME).size)
    }

    @Test
    fun finalFileWithCorrectSizeButWrongContentIsNotSkipped() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(modelDirectory)
        val voicePath = modelDirectory / ModelCatalog.VOICE_FILE_NAME
        writeBytes(fileSystem, voicePath, ByteArray(40_000) { 1 })
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        var voiceHashCount = 0
        val store = ModelFileStore(modelDirectory, fileSystem) { path ->
            if (path == voicePath) voiceHashCount++
            sha256File(fileSystem, path)
        }

        val setup = DownloadingModelSetupService(
            totalRamBytes = 4L * ModelTierSelector.BYTES_PER_GIB,
            store = store,
            downloader = downloader,
            network = FixedNetworkPolicy(true),
            scope = backgroundScope,
            choosingHoldMillis = 0L,
            loadingHoldMillis = 0L,
            modelsForRam = { tierModels },
        )
        setup.prepare()

        assertEquals(listOf(live.downloadUrl, voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
        assertEquals(1, voiceHashCount)
    }

    @Test
    fun downloadWithWrongDigestFailsWithoutLeavingAFinalOrPartialFile() = runTest {
        val fileSystem = FakeFileSystem()
        val badVoice = voice.copy(sha256 = "0".repeat(64))
        val setup = service(
            fileSystem,
            RecordingDownloader(fileSystem, listOf(live, badVoice, llm)),
            backgroundScope,
            models = TierModels(ModelTier.LEGACY, listOf(live), listOf(badVoice), llm),
        )

        setup.prepare()

        assertEquals(ModelSetupPhase.FAILED, setup.state.value.phase)
        assertEquals(ModelSetupFailureReason.MODEL, setup.state.value.failureReason)
        assertFalse(fileSystem.exists(modelDirectory / ModelCatalog.VOICE_FILE_NAME))
        assertFalse(fileSystem.exists(modelDirectory / (ModelCatalog.VOICE_FILE_NAME + ".part")))
    }

    @Test
    fun incompleteDownloadFailsAndKeepsThePartialFileForResume() = runTest {
        val fileSystem = FakeFileSystem()
        val setup = service(fileSystem, RecordingDownloader(fileSystem, listOf(live, voice, llm), shortBy = 1L), backgroundScope)

        setup.prepare()

        assertEquals(ModelSetupPhase.FAILED, setup.state.value.phase)
        assertEquals(ModelSetupFailureReason.NETWORK, setup.state.value.failureReason)
        assertFalse(fileSystem.exists(modelDirectory / ModelCatalog.LIVE_FILE_NAME))
        assertEquals(49_999L, fileSystem.metadata(modelDirectory / (ModelCatalog.LIVE_FILE_NAME + ".part")).size)
    }

    @Test
    fun httpFailureMovesSetupToFailed() = runTest {
        val fileSystem = FakeFileSystem()
        val downloader = object : ModelDownloader {
            override suspend fun download(
                url: String,
                destination: Path,
                expectedSizeBytes: Long,
                onProgress: (Long) -> Unit,
            ): Either<ModelDownloadError, String> {
                return Either.Left(ModelDownloadError.Network("Model download failed with HTTP 500."))
            }
        }
        val setup = service(fileSystem, downloader, backgroundScope)

        assertEquals(
            ModelDownloadError.Network("Model download failed with HTTP 500.").left(),
            setup.prepare(),
        )

        assertEquals(ModelSetupPhase.FAILED, setup.state.value.phase)
        assertEquals(ModelSetupFailureReason.NETWORK, setup.state.value.failureReason)
        assertNull(fileSystem.metadataOrNull(modelDirectory / ModelCatalog.VOICE_FILE_NAME))
    }

    @Test
    fun okioFileSystemFailureIsClassifiedAsStorage() = runTest {
        val fileSystem = FakeFileSystem()
        val downloader = object : ModelDownloader {
            override suspend fun download(
                url: String,
                destination: Path,
                expectedSizeBytes: Long,
                onProgress: (Long) -> Unit,
            ): Either<ModelDownloadError, String> {
                return Either.Left(ModelDownloadError.Storage(okio.IOException("Unable to access model storage.")))
            }
        }
        val setup = service(fileSystem, downloader, backgroundScope)

        val result = setup.prepare()

        val error = assertIs<Either.Left<ModelDownloadError>>(result).value
        assertIs<ModelDownloadError.Storage>(error)
        assertEquals(ModelSetupPhase.FAILED, setup.state.value.phase)
        assertEquals(ModelSetupFailureReason.STORAGE, setup.state.value.failureReason)
    }

    @Test
    fun cancelledWorkerCancelsAwaitingCaller() = runTest {
        val fileSystem = FakeFileSystem()
        val gate = CompletableDeferred<Unit>()
        val downloader = GatedDownloader(fileSystem, listOf(live, voice, llm), gate)
        val worker = CoroutineScope(coroutineContext + Job())
        val setup = service(fileSystem, downloader, worker)

        val caller = launch { setup.prepare() }
        runCurrent()
        assertEquals(ModelSetupPhase.DOWNLOADING_LIVE_VOICE, setup.state.value.phase)

        worker.cancel()
        runCurrent()
        caller.join()

        assertTrue(caller.isCancelled)
        assertFalse(setup.state.value.phase == ModelSetupPhase.READY)
    }

    @Test
    fun stagesFollowTheLoaderOrderAndProgressNeverDecreases() = runTest {
        val fileSystem = FakeFileSystem()
        val phases = mutableListOf<ModelSetupPhase>()
        val progress = mutableListOf<Float>()
        val setup = service(
            fileSystem,
            RecordingDownloader(fileSystem, listOf(live, voice, llm), chunkBytes = 10_000L),
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
                ModelSetupPhase.DOWNLOADING_LIVE_VOICE,
                ModelSetupPhase.DOWNLOADING_VOICE,
                ModelSetupPhase.LOADING_VOICE,
                ModelSetupPhase.DOWNLOADING_LLM,
                ModelSetupPhase.READY,
            ),
            phases,
        )
        assertTrue(progress.zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test
    fun activeDownloadReportsByteCountsAndClearsThemWhenItFinishes() = runTest {
        val fileSystem = FakeFileSystem()
        val observed = mutableListOf<ModelSetupState>()
        val setup = service(
            fileSystem,
            RecordingDownloader(fileSystem, listOf(live, voice, llm), chunkBytes = 10_000L),
            backgroundScope,
        )
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            setup.state.collect { observed += it }
        }

        setup.prepare()
        collector.cancel()

        val voiceDownloads = observed.filter { it.phase == ModelSetupPhase.DOWNLOADING_VOICE && it.bytesTotal > 0L }
        assertTrue(voiceDownloads.isNotEmpty())
        assertTrue(voiceDownloads.all { it.bytesTotal == 40_000L })
        assertEquals(40_000L, voiceDownloads.maxOf { it.bytesDownloaded })
        assertEquals(0L, observed.first { it.phase == ModelSetupPhase.LOADING_VOICE }.bytesTotal)
        assertEquals(0L, setup.state.value.bytesTotal)
    }

    @Test
    fun meteredNetworkHoldsTheTransferUntilMobileDataIsConsented() = runTest {
        val fileSystem = FakeFileSystem()
        val network = FixedNetworkPolicy(false)
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope, network = network)

        val prepare = launch { setup.prepare() }
        runCurrent()

        assertEquals(ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK, setup.state.value.phase)
        assertEquals(emptyList(), downloader.requestedUrls)

        setup.downloadOverMobileData()
        prepare.join()

        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
        assertEquals(listOf(live.downloadUrl, voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
    }

    @Test
    fun unmeteredNetworkReleasesTheHeldTransferWithoutConsent() = runTest {
        val fileSystem = FakeFileSystem()
        val network = FixedNetworkPolicy(false)
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope, network = network)

        val prepare = launch { setup.prepare() }
        runCurrent()
        assertEquals(ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK, setup.state.value.phase)

        network.unmetered.value = true
        prepare.join()

        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
        assertEquals(listOf(live.downloadUrl, voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
    }

    @Test
    fun verifiedLocalModelsReachReadyOnMeteredNetworkWithoutWaiting() = runTest {
        val fileSystem = FakeFileSystem()
        val store = store(fileSystem)
        store.prepareDirectory()
        writeBytes(fileSystem, store.partialPath(live), ByteArray(live.sizeBytes.toInt()))
        check(store.commit(live, live.sha256).isRight())
        writeBytes(fileSystem, store.partialPath(voice), ByteArray(voice.sizeBytes.toInt()))
        check(store.commit(voice, voice.sha256).isRight())
        writeBytes(fileSystem, store.partialPath(llm), ByteArray(llm.sizeBytes.toInt()))
        check(store.commit(llm, llm.sha256).isRight())
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope, network = FixedNetworkPolicy(false))

        setup.prepare()

        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
        assertEquals(emptyList(), downloader.requestedUrls)
    }

    @Test
    fun cancelledPrepareCallerLeavesTheSharedDownloadRunningOnce() = runTest {
        val fileSystem = FakeFileSystem()
        val gate = CompletableDeferred<Unit>()
        val downloader = GatedDownloader(fileSystem, listOf(live, voice, llm), gate)
        val setup = service(fileSystem, downloader, backgroundScope)

        val first = launch { setup.prepare() }
        val second = launch { setup.prepare() }
        runCurrent()
        first.cancel()
        runCurrent()

        assertEquals(ModelSetupPhase.DOWNLOADING_LIVE_VOICE, setup.state.value.phase)
        gate.complete(Unit)
        second.join()

        assertTrue(first.isCancelled)
        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
        assertEquals(mapOf(live.downloadUrl to 1, voice.downloadUrl to 1, llm.downloadUrl to 1), downloader.downloadCounts)
    }

    private fun writeText(fileSystem: FileSystem, path: Path, text: String) {
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.writeUtf8(text)
        } finally {
            sink.close()
        }
    }

    @Test
    fun obsoleteGgmlFilesAndTheirSidecarsAreRemovedOnLaunch() = runTest {
        val fileSystem = FakeFileSystem()
        val store = store(fileSystem)
        store.prepareDirectory()
        for (name in ModelCatalog.OBSOLETE_FILE_NAMES) {
            writeBytes(fileSystem, modelDirectory / name, ByteArray(8))
            writeBytes(fileSystem, modelDirectory / (name + ".part"), ByteArray(4))
            writeBytes(fileSystem, modelDirectory / (name + ".verified"), ByteArray(2))
        }
        val downloader = RecordingDownloader(fileSystem, listOf(live, voice, llm))

        assertEquals(Unit.right(), service(fileSystem, downloader, backgroundScope).prepare())

        for (name in ModelCatalog.OBSOLETE_FILE_NAMES) {
            assertFalse(fileSystem.exists(modelDirectory / name))
            assertFalse(fileSystem.exists(modelDirectory / (name + ".part")))
            assertFalse(fileSystem.exists(modelDirectory / (name + ".verified")))
        }
    }

    @Test
    fun progressIsWeightedBySizeAcrossUnequalFilesInOneStep() = runTest {
        val fileSystem = FakeFileSystem()
        val encoder = spec("Encoder", ModelRole.LIVE_VOICE, "live-encoder.int8.onnx", "https://example.test/enc.onnx", 30_000L)
        val tokens = spec("Tokens", ModelRole.LIVE_VOICE, "live-tokens.txt", "https://example.test/tokens.txt", 10_000L)
        val models = TierModels(ModelTier.LEGACY, listOf(encoder, tokens), listOf(voice), llm)
        val downloader = RecordingDownloader(fileSystem, listOf(encoder, tokens, voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope, models = models)
        val liveStates = mutableListOf<ModelSetupState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            setup.state.collect { state ->
                if (state.phase == ModelSetupPhase.DOWNLOADING_LIVE_VOICE) liveStates += state
            }
        }

        assertEquals(Unit.right(), setup.prepare())
        collector.cancel()

        val sized = liveStates.filter { it.bytesTotal > 0L }
        assertTrue(sized.isNotEmpty())
        assertTrue(sized.all { it.bytesTotal == 40_000L })
        val downloaded = sized.map { it.bytesDownloaded }
        assertEquals(downloaded.sorted(), downloaded, "bytes must never move backwards")
        assertEquals(40_000L, downloaded.max())
        assertTrue(downloaded.contains(30_000L), "encoder completion should land at 30k of 40k")
    }

    @Test
    fun missingComponentOfAPartlyCachedBundleIsDownloadedAlone() = runTest {
        val fileSystem = FakeFileSystem()
        val store = store(fileSystem)
        store.prepareDirectory()
        val encoder = spec("Encoder", ModelRole.LIVE_VOICE, "live-encoder.int8.onnx", "https://example.test/enc.onnx", 30_000L)
        val tokens = spec("Tokens", ModelRole.LIVE_VOICE, "live-tokens.txt", "https://example.test/tokens.txt", 10_000L)
        writeBytes(fileSystem, store.partialPath(tokens), ByteArray(10_000))
        check(store.commit(tokens, tokens.sha256).isRight())
        val models = TierModels(ModelTier.LEGACY, listOf(encoder, tokens), listOf(voice), llm)
        val downloader = RecordingDownloader(fileSystem, listOf(encoder, tokens, voice, llm))
        val setup = service(fileSystem, downloader, backgroundScope, models = models)

        assertEquals(Unit.right(), setup.prepare())

        assertEquals(listOf(encoder.downloadUrl, voice.downloadUrl, llm.downloadUrl), downloader.requestedUrls)
        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
    }

    @Test
    fun retryAfterLaterFailureDoesNotRedownloadCompletedFiles() = runTest {
        val fileSystem = FakeFileSystem()
        val encoder = spec("Encoder", ModelRole.LIVE_VOICE, "live-encoder.int8.onnx", "https://example.test/enc.onnx", 30_000L)
        val tokens = spec("Tokens", ModelRole.LIVE_VOICE, "live-tokens.txt", "https://example.test/tokens.txt", 10_000L)
        val models = TierModels(ModelTier.LEGACY, listOf(encoder, tokens), listOf(voice), llm)
        val inner = RecordingDownloader(fileSystem, listOf(encoder, tokens, voice, llm))
        val flaky = FailOnceDownloader(inner, failingUrl = voice.downloadUrl)
        val setup = service(fileSystem, flaky, backgroundScope, models = models)

        assertIs<Either.Left<ModelDownloadError>>(setup.prepare())
        assertEquals(ModelSetupPhase.FAILED, setup.state.value.phase)

        assertEquals(Unit.right(), setup.prepare())

        assertEquals(
            listOf(encoder.downloadUrl, tokens.downloadUrl, voice.downloadUrl, llm.downloadUrl),
            inner.requestedUrls,
        )
        assertEquals(ModelSetupPhase.READY, setup.state.value.phase)
    }

    private class FailOnceDownloader(
        private val delegate: ModelDownloader,
        private val failingUrl: String,
    ) : ModelDownloader {
        private var failed = false

        override suspend fun download(
            url: String,
            destination: Path,
            expectedSizeBytes: Long,
            onProgress: (Long) -> Unit,
        ): Either<ModelDownloadError, String> {
            if (url == failingUrl && !failed) {
                failed = true
                return ModelDownloadError.Network("Simulated drop").left()
            }
            return delegate.download(url, destination, expectedSizeBytes, onProgress)
        }
    }

    private fun writeBytes(fileSystem: FileSystem, path: Path, bytes: ByteArray) {
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
    }

    private fun sha256File(fileSystem: FileSystem, path: Path): String {
        val source = fileSystem.source(path).buffer()
        try {
            return sha256Hex(source.readByteArray())
        } finally {
            source.close()
        }
    }

    private class FixedNetworkPolicy(initiallyUnmetered: Boolean) : ModelNetworkPolicy {
        override val unmetered = MutableStateFlow(initiallyUnmetered)
    }

    private inner class GatedDownloader(
        private val fileSystem: FileSystem,
        private val specs: List<ModelSpec>,
        private val gate: CompletableDeferred<Unit>,
    ) : ModelDownloader {
        val downloadCounts = mutableMapOf<String, Int>()

        override suspend fun download(
            url: String,
            destination: Path,
            expectedSizeBytes: Long,
            onProgress: (Long) -> Unit,
        ): Either<ModelDownloadError, String> {
            downloadCounts[url] = (downloadCounts[url] ?: 0) + 1
            val model = specs.first { it.downloadUrl == url }
            gate.await()
            val bytes = ByteArray(model.sizeBytes.toInt())
            writeBytes(fileSystem, destination, bytes)
            onProgress(model.sizeBytes)
            return sha256Hex(bytes).right()
        }
    }

    private class RecordingDownloader(
        private val fileSystem: FileSystem,
        private val specs: List<ModelSpec>,
        private val shortBy: Long = 0L,
        private val chunkBytes: Long = 16_000L,
    ) : ModelDownloader {
        val requestedUrls = mutableListOf<String>()

        override suspend fun download(
            url: String,
            destination: Path,
            expectedSizeBytes: Long,
            onProgress: (Long) -> Unit,
        ): Either<ModelDownloadError, String> {
            requestedUrls += url
            val model = specs.first { it.downloadUrl == url }
            assertEquals(model.sizeBytes, expectedSizeBytes)
            val target = model.sizeBytes - shortBy
            val hashing = HashingSink.sha256(fileSystem.sink(destination))
            val sink = hashing.buffer()
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
            return hashing.hash.hex().right()
        }
    }
}
