package org.deer.borrowai

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import org.deer.borrowai.data.app.ServiceVariants
import org.deer.borrowai.data.domain.EpochClock

class MainActivity : ComponentActivity() {
    // A denied request still lets the download run; only its notification is hidden.
    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
