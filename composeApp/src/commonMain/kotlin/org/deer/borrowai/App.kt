package org.deer.borrowai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppPage {
    ModelSetup,
    Capture,
    SavedMemories,
}

private val BorrowColors = lightColorScheme(
    primary = Color(0xFF286B53),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEFE4),
    onPrimaryContainer = Color(0xFF173E30),
    secondary = Color(0xFF55756A),
    onSecondary = Color.White,
    background = Color(0xFFF5F7F3),
    surface = Color.White,
    surfaceVariant = Color(0xFFECF1ED),
    onSurface = Color(0xFF19241E),
    onSurfaceVariant = Color(0xFF637169),
    outline = Color(0xFFD9E2DC),
    error = Color(0xFFD84949),
)

private val TranscriptSamples = listOf(
    "The important part is making a little progress every day, even when the plan changes.",
    "We could revisit the first idea tomorrow and compare it with the simpler option.",
    "Remember to send the notes to the team before the afternoon review.",
    "A clear summary will make it easier to pick this conversation back up later.",
)

@Composable
fun App() {
    var page by remember { mutableStateOf(AppPage.ModelSetup) }
    var selectedSpeechModel by remember { mutableStateOf<String?>(null) }
    var selectedLanguageModel by remember { mutableStateOf<String?>(null) }
    val downloadProgress = remember { mutableStateMapOf<String, Float>() }
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isHoldRecording by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var transcript by remember { mutableStateOf(InitialTranscript) }

    fun startMockDownload(modelId: String) {
        if (downloadProgress.containsKey(modelId)) return
        downloadProgress[modelId] = 0f
        scope.launch {
            repeat(DOWNLOAD_STEPS) { step ->
                delay(DOWNLOAD_STEP_MILLIS)
                downloadProgress[modelId] = (step + 1).toFloat() / DOWNLOAD_STEPS
            }
        }
    }

    fun startRecording(holdToRecord: Boolean) {
        isRecording = true
        isHoldRecording = holdToRecord
        elapsedSeconds = 0
        transcript = InitialTranscript
    }

    fun toggleRecording() {
        if (isRecording) {
            isRecording = false
            isHoldRecording = false
        } else {
            startRecording(holdToRecord = false)
        }
    }

    fun stopHoldRecording() {
        if (isRecording && isHoldRecording) {
            isRecording = false
            isHoldRecording = false
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            var sampleIndex = 0
            while (true) {
                delay(TRANSCRIPT_INTERVAL_MILLIS)
                elapsedSeconds += 1
                val nextTranscript = "$transcript ${TranscriptSamples[sampleIndex % TranscriptSamples.size]}"
                transcript = if (nextTranscript.length > MAX_TRANSCRIPT_LENGTH) {
                    "…${nextTranscript.takeLast(MAX_TRANSCRIPT_LENGTH - 1)}"
                } else {
                    nextTranscript
                }
                sampleIndex += 1
            }
        }
    }

    val selectedSpeech = MockModels.speech.firstOrNull { it.id == selectedSpeechModel }
    val selectedLanguage = MockModels.language.firstOrNull { it.id == selectedLanguageModel }
    val modelsReady = selectedSpeech?.let { downloadProgress[it.id] == 1f } == true &&
        selectedLanguage?.let { downloadProgress[it.id] == 1f } == true

    MaterialTheme(colorScheme = BorrowColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "app-page",
            ) { currentPage ->
                when (currentPage) {
                    AppPage.ModelSetup -> ModelOnboardingScreen(
                        selectedSpeechModel = selectedSpeechModel,
                        selectedLanguageModel = selectedLanguageModel,
                        downloadProgress = downloadProgress,
                        canContinue = modelsReady,
                        onSelectSpeechModel = { selectedSpeechModel = it },
                        onSelectLanguageModel = { selectedLanguageModel = it },
                        onDownload = ::startMockDownload,
                        onContinue = { page = AppPage.Capture },
                    )

                    AppPage.Capture -> BorrowCaptureScreen(
                        speechModelName = selectedSpeech?.name.orEmpty(),
                        languageModelName = selectedLanguage?.name.orEmpty(),
                        isRecording = isRecording,
                        isHoldRecording = isHoldRecording,
                        elapsedSeconds = elapsedSeconds,
                        transcript = transcript,
                        onToggleRecording = ::toggleRecording,
                        onStartHoldRecording = { startRecording(holdToRecord = true) },
                        onStopHoldRecording = ::stopHoldRecording,
                        onOpenMemories = { page = AppPage.SavedMemories },
                        onChangeModels = { page = AppPage.ModelSetup },
                    )

                    AppPage.SavedMemories -> SavedMemoriesScreen(
                        onReturnToCapture = { page = AppPage.Capture },
                    )
                }
            }
        }
    }
}

private const val DOWNLOAD_STEPS = 30
private const val DOWNLOAD_STEP_MILLIS = 100L
private const val TRANSCRIPT_INTERVAL_MILLIS = 1_100L
private const val MAX_TRANSCRIPT_LENGTH = 2_000

private const val InitialTranscript =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
        "We should keep the first version focused, make the important details easy to find, " +
        "and leave room to refine the idea after trying it."
