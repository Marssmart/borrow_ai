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
import org.deer.borrowai.data.app.DataService
import org.deer.borrowai.data.app.ServiceMode
import org.deer.borrowai.data.app.ServiceVariant
import org.deer.borrowai.data.domain.EpochClock

class MainActivity : ComponentActivity() {
    // A denied request still lets the download run; only its notification is hidden.
    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home-screen launches cannot carry the variant extra from the IDE run configuration.
        val serviceVariantId = intent.getStringExtra(ModelDownloadWork.SERVICE_VARIANT_EXTRA)
            ?: if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                ServiceVariants.DEMO.id
            } else {
                null
            }
        val serviceVariant = ServiceVariants.fromId(serviceVariantId)
        val services = ViewModelProvider(
            this,
            AppServicesViewModelFactory(
                serviceVariantId = serviceVariant.id,
                epochClock = EpochClock { System.currentTimeMillis() },
                device = AndroidDeviceEnvironment(applicationContext),
            ),
        ).get(AppServicesViewModel::class.java).services
        requestModelDownloadNotificationPermission(serviceVariant)
        setContent {
            App(services)
        }
    }

    private fun requestModelDownloadNotificationPermission(serviceVariant: ServiceVariant) {
        if (serviceVariant.modeOf(DataService.MODEL_SETUP) != ServiceMode.REAL) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (preferences.getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)) return
        check(preferences.edit().putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).commit()) {
            "Unable to persist the notification permission request state."
        }
        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val PREFERENCES_NAME = "borrowai-permissions"
        const val NOTIFICATION_PERMISSION_REQUESTED = "model-download-notification-requested"
    }
}
