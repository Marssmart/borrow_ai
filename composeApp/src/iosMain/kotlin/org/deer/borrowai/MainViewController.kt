package org.deer.borrowai

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController

fun MainViewController(startupModeFlag: String?): UIViewController {
    val services = AppServices.createFromStartupFlag(
        startupModeFlag = startupModeFlag,
        epochClock = EpochClock {
            (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
        },
    )
    return ComposeUIViewController {
        DisposableEffect(services) {
            onDispose { services.close() }
        }
        App(services)
    }
}

private const val MILLIS_PER_SECOND = 1_000.0
