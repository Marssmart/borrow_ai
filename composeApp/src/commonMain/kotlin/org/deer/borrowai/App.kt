package org.deer.borrowai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.deer.borrowai.data.app.AppServices
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

private object BorrowBackground {
    val gradientColors = listOf(
        Color(0xFFEAF5F4),
        Color(0xFFDCECEF),
        Color(0xFFDDE8F6),
    )
    val topGlowColor = Color(0xFF58B5AE)
    val bottomGlowColor = Color(0xFF8589E0)
    val topGlowSize = 380.dp
    val bottomGlowSize = 440.dp

    const val TOP_GLOW_ALPHA = 0.38f
    const val BOTTOM_GLOW_ALPHA = 0.25f
}

private const val MAIN_PAGE_COUNT = 2
private const val CAPTURE_PAGE = 0
private const val MEMORIES_PAGE = 1

@Composable
fun App(services: AppServices) {
    val mainPagerState = rememberPagerState(
        pageCount = { MAIN_PAGE_COUNT },
    )
    val scope = rememberCoroutineScope()
    var isMemoryDetailVisible by remember { mutableStateOf(false) }
    LaunchedEffect(services.modelSetup) {
        services.modelSetup.prepare()
    }
    MaterialTheme(colorScheme = BorrowColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(BorrowBackground.gradientColors),
                ),
            ) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd)
                        .size(BorrowBackground.topGlowSize)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    BorrowBackground.topGlowColor.copy(
                                        alpha = BorrowBackground.TOP_GLOW_ALPHA,
                                    ),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .size(BorrowBackground.bottomGlowSize)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    BorrowBackground.bottomGlowColor.copy(
                                        alpha = BorrowBackground.BOTTOM_GLOW_ALPHA,
                                    ),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                HorizontalPager(
                    state = mainPagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isMemoryDetailVisible,
                ) { page ->
                    when (page) {
                        CAPTURE_PAGE -> BorrowCaptureScreen(
                            mediaSessionService = services.mediaSession,
                            modelSetupService = services.modelSetup,
                            onDownloadOverMobileData = {
                                scope.launch { services.modelSetup.downloadOverMobileData() }
                            },
                        )

                        MEMORIES_PAGE -> SavedMemoriesScreen(
                            services = services,
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
