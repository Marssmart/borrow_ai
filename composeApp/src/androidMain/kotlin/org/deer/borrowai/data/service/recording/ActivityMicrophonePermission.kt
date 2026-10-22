package org.deer.borrowai.data.service.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Requests [Manifest.permission.RECORD_AUDIO] through an [ActivityResultLauncher].
 *
 * The instance outlives activity recreation (see [MicrophonePermissionViewModel]), so every
 * activity must call [attach] from `onCreate` before a request can start. A request launched by a
 * previous activity stays pending: the activity result registry redelivers its result to the
 * callback registered under the same key by the new activity, which completes the request.
 */
class ActivityMicrophonePermission : MicrophonePermission {
    private var context: Context? = null

    // Weak so the ViewModel that outlives activities does not keep a destroyed activity alive.
    private var activity: WeakReference<ComponentActivity>? = null
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null
    private val mutableDenial = MutableStateFlow<MicrophoneDenial?>(null)

    override val denial: StateFlow<MicrophoneDenial?> = mutableDenial.asStateFlow()

    fun attach(activity: ComponentActivity) {
        context = activity.applicationContext
        this.activity = WeakReference(activity)
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> onResult(granted) }
    }

    override suspend fun request(): Boolean = withContext(Dispatchers.Main.immediate) {
        val appContext = context ?: return@withContext false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext true
        }
        val activeLauncher = launcher ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        pending?.complete(false)
        pending = deferred
        activeLauncher.launch(Manifest.permission.RECORD_AUDIO)
        deferred.await()
    }

    private fun onResult(granted: Boolean) {
        mutableDenial.value = if (granted) {
            null
        } else if (activity?.get()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true) {
            // Only meaningful after a result: before a request, Android reports false for both cases.
            MicrophoneDenial.RATIONALE_REQUIRED
        } else {
            MicrophoneDenial.PERMANENTLY_DENIED
        }
        pending?.complete(granted)
        pending = null
    }

    /** Clears a denial when the user has granted access in Settings. Call from the activity's onResume. */
    fun refreshDenial() {
        val appContext = context ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            mutableDenial.value = null
        }
    }

    override fun openSettings() {
        val appContext = context ?: return
        // Application context has no task of its own, so the Settings screen needs NEW_TASK.
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }
}

/** Keeps one [ActivityMicrophonePermission] alive across activity recreation. */
internal class MicrophonePermissionViewModel : ViewModel() {
    val permission = ActivityMicrophonePermission()
}
