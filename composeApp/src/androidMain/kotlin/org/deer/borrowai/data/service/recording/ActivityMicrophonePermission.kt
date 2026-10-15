package org.deer.borrowai.data.service.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Requests [Manifest.permission.RECORD_AUDIO] through an [ActivityResultLauncher].
 *
 * The instance outlives activity recreation (see [MicrophonePermissionViewModel]), so every
 * activity must call [attach] from `onCreate` before a request can start. [attach] drops a request
 * left pending by the previous activity (it is reported as denied); the user asks again.
 */
class ActivityMicrophonePermission : MicrophonePermission {
    private var context: Context? = null
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    fun attach(activity: ComponentActivity) {
        pending?.complete(false)
        pending = null
        context = activity.applicationContext
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
        pending?.complete(granted)
        pending = null
    }
}

/** Keeps one [ActivityMicrophonePermission] alive across activity recreation. */
internal class MicrophonePermissionViewModel : ViewModel() {
    val permission = ActivityMicrophonePermission()
}
