package org.deer.borrowai.data.service.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * Requests [Manifest.permission.RECORD_AUDIO] through an [ActivityResultLauncher].
 * [register] must run before the activity is started, which is why it lives in `onCreate`.
 */
class ActivityMicrophonePermission private constructor(
    private val context: Context,
    private val launcher: ActivityResultLauncher<String>,
) : MicrophonePermission {
    private var pending: CompletableDeferred<Boolean>? = null

    override suspend fun request(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        val deferred = CompletableDeferred<Boolean>()
        pending?.complete(false)
        pending = deferred
        launcher.launch(Manifest.permission.RECORD_AUDIO)
        return deferred.await()
    }

    internal fun onResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    companion object {
        fun register(activity: ComponentActivity): ActivityMicrophonePermission {
            lateinit var permission: ActivityMicrophonePermission
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> permission.onResult(granted) }
            permission = ActivityMicrophonePermission(activity, launcher)
            return permission
        }
    }
}
