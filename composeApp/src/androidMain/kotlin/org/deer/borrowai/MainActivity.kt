package org.deer.borrowai

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import org.deer.borrowai.data.app.ServiceVariants
import org.deer.borrowai.data.domain.EpochClock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home-screen launches cannot carry the variant extra from the IDE run configuration.
        val serviceVariantId = intent.getStringExtra(SERVICE_VARIANT_EXTRA)
            ?: if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                ServiceVariants.DEMO.id
            } else {
                null
            }
        val services = ViewModelProvider(
            this,
            AppServicesViewModelFactory(
                serviceVariantId = serviceVariantId,
                epochClock = EpochClock { System.currentTimeMillis() },
                device = AndroidDeviceEnvironment(applicationContext),
            ),
        ).get(AppServicesViewModel::class.java).services
        setContent {
            App(services)
        }
    }

    private companion object {
        const val SERVICE_VARIANT_EXTRA = "BORROW_AI_SERVICE_VARIANT"
    }
}
