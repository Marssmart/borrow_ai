package org.deer.borrowai

import android.app.ActivityManager
import android.content.Context
import okio.Path
import org.deer.borrowai.data.app.DeviceEnvironment
import org.deer.borrowai.data.service.model.modelDirectoryUnder

class AndroidDeviceEnvironment(context: Context) : DeviceEnvironment {
    override val totalMemoryBytes: Long = ActivityManager.MemoryInfo().also { info ->
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    }.totalMem

    override val modelDirectory: Path = modelDirectoryUnder(context.noBackupFilesDir.absolutePath)
}
