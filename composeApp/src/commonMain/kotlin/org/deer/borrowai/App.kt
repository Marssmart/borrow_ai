package org.deer.borrowai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.deer.borrowai.data.app.AppServices

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

    const val TOP_GLOW_ALPHA = 0.38f
    const val BOTTOM_GLOW_ALPHA = 0.25f
}

@Composable
fun App(services: AppServices) {
    val mainPagerState = rememberPagerState(
        pageCount = { AppShellStateHolder.MAIN_PAGE_COUNT },
    )
    val scope = rememberCoroutineScope()
    val appStateHolder = remember(mainPagerState, scope) {
        AppShellStateHolder(mainPagerState, scope)
    }
    val appUiState by appStateHolder.uiState.collectAsState()
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
                        .size(AppShellStateHolder.BACKGROUND_TOP_GLOW_SIZE)
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
                        .size(AppShellStateHolder.BACKGROUND_BOTTOM_GLOW_SIZE)
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
                val navBarInset = WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding()
                HorizontalPager(
                    state = appStateHolder.mainPagerState,
                    modifier = Modifier.fillMaxSize().padding(
                        bottom = BottomNavDockReserve + navBarInset,
                    ),
                    userScrollEnabled = !appUiState.isMemoryDetailVisible,
                ) { page ->
                    when (page) {
                        AppShellStateHolder.CAPTURE_PAGE -> BorrowCaptureScreen(
                            mediaSessionService = services.mediaSession,
                            modelSetupService = services.modelSetup,
                            onDownloadOverMobileData = {
                                scope.launch { services.modelSetup.downloadOverMobileData() }
                            },
                        )

                        AppShellStateHolder.MEMORIES_PAGE -> SavedMemoriesScreen(
                            services = services,
                            onDetailBackRegistered = appStateHolder::registerMemoryDetailBack,
                            onDetailVisibilityChanged = appStateHolder::setMemoryDetailVisible,
                        )

                        AppShellStateHolder.SETTINGS_PAGE -> SettingsScreen()
                    }
                }
                if (appUiState.isMemoryDetailVisible) {
                    MemoryDetailNavBar(
                        onBack = appStateHolder::backFromMemoryDetail,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                } else {
                    BottomNavBar(
                        selectedPage = appStateHolder.mainPagerState.targetPage,
                        onSelect = appStateHolder::navigateToPage,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}
