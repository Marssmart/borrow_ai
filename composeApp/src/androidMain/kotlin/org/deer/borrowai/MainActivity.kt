package org.deer.borrowai

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.deer.borrowai.data.app.AppMode
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home-screen launches cannot carry the demo extra from the IDE run configuration.
        val startupModeFlag = intent.getStringExtra(DATA_MODE_EXTRA)
            ?: if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                AppMode.DEMO.name
            } else {
                null
            }
        val services = AppServices.createFromStartupFlag(
            startupModeFlag = startupModeFlag,
            epochClock = EpochClock { System.currentTimeMillis() },
        )
        setContent {
            App(services)
        }
    }

    private companion object {
        const val DATA_MODE_EXTRA = "BORROW_AI_DATA_MODE"
    }
}
