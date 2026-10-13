package org.deer.borrowai

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.app.DataService
import org.deer.borrowai.data.app.ServiceMode
import org.deer.borrowai.data.app.ServiceVariant
import org.deer.borrowai.data.app.resolveServiceVariant
import org.deer.borrowai.data.domain.EpochClock

class MainActivity : ComponentActivity() {
    private lateinit var appServicesViewModel: AppServicesViewModel

    // A denied request still lets the download run; only its notification is hidden.
    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceVariant = resolveLaunchVariant(intent)
        appServicesViewModel = ViewModelProvider(
            this,
            AppServicesViewModelFactory(
                serviceVariantId = serviceVariant.id,
                epochClock = EpochClock { System.currentTimeMillis() },
                device = AndroidDeviceEnvironment(applicationContext),
            ),
        ).get(AppServicesViewModel::class.java)
        if (appServicesViewModel.services.value.variant.id != serviceVariant.id) {
            appServicesViewModel.replaceServices(createServices(serviceVariant))
        }
        requestModelDownloadNotificationPermission(serviceVariant)
        setContent {
            val services by appServicesViewModel.services.collectAsState()
            App(services)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyServiceVariant(resolveLaunchVariant(intent))
    }

    override fun onResume() {
        super.onResume()
        if (!::appServicesViewModel.isInitialized) return
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val serviceVariant = resolveServiceVariant(
            hasExplicitId = false,
            explicitId = null,
            savedId = preferences.getString(SERVICE_VARIANT_SELECTION, null),
            isDebuggable = isDebuggable(),
        )
        applyServiceVariant(serviceVariant)
    }

    private fun applyServiceVariant(serviceVariant: ServiceVariant) {
        if (appServicesViewModel.services.value.variant.id != serviceVariant.id) {
            appServicesViewModel.replaceServices(createServices(serviceVariant))
        }
        requestModelDownloadNotificationPermission(serviceVariant)
    }

    private fun resolveLaunchVariant(launchIntent: Intent): ServiceVariant {
        val hasExplicitId = launchIntent.hasExtra(ModelDownloadWork.SERVICE_VARIANT_EXTRA)
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val serviceVariant = resolveServiceVariant(
            hasExplicitId = hasExplicitId,
            explicitId = if (hasExplicitId) {
                launchIntent.getStringExtra(ModelDownloadWork.SERVICE_VARIANT_EXTRA)
            } else {
                null
            },
            savedId = if (hasExplicitId) null else preferences.getString(SERVICE_VARIANT_SELECTION, null),
            isDebuggable = isDebuggable(),
        )
        if (hasExplicitId) {
            check(preferences.edit().putString(SERVICE_VARIANT_SELECTION, serviceVariant.id).commit()) {
                "Unable to persist the selected data-service variant."
            }
        }
        return serviceVariant
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun createServices(serviceVariant: ServiceVariant): AppServices =
        AppServices.createFromServiceVariant(
            serviceVariantId = serviceVariant.id,
            epochClock = EpochClock { System.currentTimeMillis() },
            device = AndroidDeviceEnvironment(applicationContext),
        )

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
        const val SERVICE_VARIANT_SELECTION = "service-variant-id"
    }
}
