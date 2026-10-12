package org.deer.borrowai

import androidx.compose.ui.window.ComposeUIViewController
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

fun MainViewController(startupModeFlag: String?) = ComposeUIViewController {
    App(
        AppServices.createFromStartupFlag(
            startupModeFlag = startupModeFlag,
            epochClock = EpochClock {
                (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
            },
        ),
    )
}

private const val MILLIS_PER_SECOND = 1_000.0
