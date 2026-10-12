package org.deer.borrowai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BorrowColors = lightColorScheme(
    primary = Color(0xFF14757B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9E5E7),
    onPrimaryContainer = Color(0xFF123B43),
    secondary = Color(0xFF47636A),
    onSecondary = Color.White,
    background = Color(0xFFDCECEF),
    surface = Color.White.copy(alpha = 0.92f),
    surfaceVariant = Color(0xFFE7F3F4),
    onSurface = Color(0xFF123B43),
    onSurfaceVariant = Color(0xFF47636A),
    outline = Color(0xFFA9CBD0),
    error = Color(0xFFC93458),
)

private val TranscriptSamples = listOf(
    "The important part is making a little progress every day, even when the plan changes.",
    "We could revisit the first idea tomorrow and compare it with the simpler option.",
    "Remember to send the notes to the team before the afternoon review.",
    "A clear summary will make it easier to pick this conversation back up later.",
)

@Composable
fun App() {
    var modelSetupComplete by rememberSaveable { mutableStateOf(false) }
    var setupProgress by remember { mutableFloatStateOf(0f) }
    var isMemoryDetailVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mainPagerState = rememberPagerState(pageCount = { MAIN_PAGE_COUNT })

    var isRecording by remember { mutableStateOf(false) }
    var isHoldRecording by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var transcript by remember { mutableStateOf(InitialTranscript) }

    LaunchedEffect(modelSetupComplete) {
        if (!modelSetupComplete) {
            repeat(SETUP_PROGRESS_STEPS) { step ->
                delay(MODEL_SETUP_DURATION_MILLIS / SETUP_PROGRESS_STEPS)
                setupProgress = (step + 1).toFloat() / SETUP_PROGRESS_STEPS
            }
            modelSetupComplete = true
        }
    }

    fun startRecording(holdToRecord: Boolean) {
        isRecording = true
        isHoldRecording = holdToRecord
        elapsedSeconds = 0
        transcript = ""
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
            while (true) {
                delay(TRANSCRIPT_CLOCK_INTERVAL_MILLIS)
                elapsedSeconds += 1
            }
        }
    }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            var sampleIndex = 0
            while (true) {
                val sample = "${TranscriptSamples[sampleIndex % TranscriptSamples.size]} "
                for (character in sample) {
                    delay(TRANSCRIPT_CHARACTER_INTERVAL_MILLIS)
                    val nextTranscript = "$transcript$character"
                    transcript = if (nextTranscript.length > MAX_TRANSCRIPT_LENGTH) {
                        "…${nextTranscript.takeLast(MAX_TRANSCRIPT_LENGTH - 1)}"
                    } else {
                        nextTranscript
                    }
                }
                sampleIndex += 1
            }
        }
    }

    val showLoading = !modelSetupComplete

    MaterialTheme(colorScheme = BorrowColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFFEAF5F4),
                            Color(0xFFDCECEF),
                            Color(0xFFDDE8F6),
                        ),
                    ),
                ),
            ) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).size(380.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF58B5AE).copy(alpha = 0.38f), Color.Transparent),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).size(440.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF8589E0).copy(alpha = 0.25f), Color.Transparent),
                            ),
                        ),
                )
                AnimatedContent(
                    targetState = showLoading,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (initialState && !targetState) {
                            slideInHorizontally(tween(320)) { width -> width } + fadeIn(tween(220)) togetherWith
                                slideOutHorizontally(tween(320)) { width -> -width } + fadeOut(tween(220))
                        } else {
                            slideInHorizontally(tween(320)) { width -> -width } + fadeIn(tween(220)) togetherWith
                                slideOutHorizontally(tween(320)) { width -> width } + fadeOut(tween(220))
                        }
                    },
                    label = "onboarding-transition",
                ) { currentPage ->
                    if (currentPage) {
                        ModelLoadingScreen(progress = setupProgress)
                    } else {
                        HorizontalPager(
                            state = mainPagerState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = !isMemoryDetailVisible,
                        ) { page ->
                            when (page) {
                                CAPTURE_PAGE -> BorrowCaptureScreen(
                                    isRecording = isRecording,
                                    isHoldRecording = isHoldRecording,
                                    elapsedSeconds = elapsedSeconds,
                                    transcript = transcript,
                                    onToggleRecording = ::toggleRecording,
                                    onStartHoldRecording = { startRecording(holdToRecord = true) },
                                    onStopHoldRecording = ::stopHoldRecording,
                                )

                                MEMORIES_PAGE -> SavedMemoriesScreen(
                                    onReturnToCapture = {
                                        scope.launch { mainPagerState.animateScrollToPage(CAPTURE_PAGE) }
                                    },
                                    onDetailVisibilityChanged = { isMemoryDetailVisible = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MODEL_SETUP_DURATION_MILLIS = 5_000L
private const val SETUP_PROGRESS_STEPS = 50
private const val TRANSCRIPT_CLOCK_INTERVAL_MILLIS = 1_000L
private const val TRANSCRIPT_CHARACTER_INTERVAL_MILLIS = 45L
private const val MAX_TRANSCRIPT_LENGTH = 2_000
private const val MAIN_PAGE_COUNT = 2
private const val CAPTURE_PAGE = 0
private const val MEMORIES_PAGE = 1

private const val InitialTranscript =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
        "We should keep the first version focused, make the important details easy to find, " +
        "and leave room to refine the idea after trying it."
