package org.deer.borrowai

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import okio.Path
import org.deer.borrowai.data.app.DeviceEnvironment
import org.deer.borrowai.data.app.RealModelSetup
import org.deer.borrowai.data.service.model.modelDirectoryUnder

class AndroidDeviceEnvironment(context: Context) : DeviceEnvironment {
    private val appContext = context.applicationContext

    override val totalMemoryBytes: Long = ActivityManager.MemoryInfo().also { info ->
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    }.totalMem

    override val modelDirectory: Path = modelDirectoryUnder(appContext.noBackupFilesDir.absolutePath)

    override fun createRealModelSetup(scope: CoroutineScope): RealModelSetup =
        RealModelSetup(WorkManagerModelSetupService(appContext, scope))
}
