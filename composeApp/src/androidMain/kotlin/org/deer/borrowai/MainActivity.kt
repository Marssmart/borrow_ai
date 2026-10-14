package org.deer.borrowai

import android.Manifest
import android.app.ComponentCaller
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.app.ServiceVariant
import org.deer.borrowai.data.app.resolveServiceVariant
import org.deer.borrowai.data.app.resolveServiceVariantLaunch
import org.deer.borrowai.data.domain.EpochClock

class MainActivity : ComponentActivity() {
    private lateinit var appServicesViewModel: AppServicesViewModel

    // A denied request still lets the download run; only its notification is hidden.
    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceVariant = resolveLaunchVariant(intent, initialCallerUid())
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
        requestModelDownloadNotificationPermission()
        setContent {
            val services by appServicesViewModel.services.collectAsState()
            App(services)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Build.VERSION.SDK_INT >= 35) return
        setIntent(intent)
        applyServiceVariant(resolveLaunchVariant(intent, callerUid = null))
    }

    @RequiresApi(35)
    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        setIntent(intent, caller)
        applyServiceVariant(resolveLaunchVariant(intent, caller.uid))
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
        requestModelDownloadNotificationPermission()
    }

    private fun resolveLaunchVariant(launchIntent: Intent, callerUid: Int?): ServiceVariant {
        val hasExplicitId = launchIntent.hasExtra(ModelDownloadWork.SERVICE_VARIANT_EXTRA)
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val selection = resolveServiceVariantLaunch(
            hasExplicitId = hasExplicitId,
            explicitId = if (hasExplicitId) {
                launchIntent.getStringExtra(ModelDownloadWork.SERVICE_VARIANT_EXTRA)
            } else {
                null
            },
            callerUid = callerUid,
            trustedCallerUid = Process.SHELL_UID,
            savedId = preferences.getString(SERVICE_VARIANT_SELECTION, null),
            isDebuggable = isDebuggable(),
        )
        if (selection.persistExplicitSelection) {
            check(preferences.edit().putString(SERVICE_VARIANT_SELECTION, selection.variant.id).commit()) {
                "Unable to persist the selected data-service variant."
            }
        }
        return selection.variant
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun initialCallerUid(): Int? = when {
        Build.VERSION.SDK_INT >= 35 -> initialCaller?.uid
        Build.VERSION.SDK_INT >= 34 -> launchedFromUid
        else -> null
    }

    private fun createServices(serviceVariant: ServiceVariant): AppServices =
        AppServices.createFromServiceVariant(
            serviceVariantId = serviceVariant.id,
            epochClock = EpochClock { System.currentTimeMillis() },
            device = AndroidDeviceEnvironment(applicationContext),
        )

    private fun requestModelDownloadNotificationPermission() {
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
