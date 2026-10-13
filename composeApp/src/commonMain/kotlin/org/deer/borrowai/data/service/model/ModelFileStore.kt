package org.deer.borrowai.data.service.model

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Static model location: `<modelDirectory>/<spec.fileName>`, with `.part` used until a download is complete. */
class ModelFileStore(
    val modelDirectory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun finalPath(spec: ModelSpec): Path = modelDirectory / spec.fileName

    fun partialPath(spec: ModelSpec): Path = modelDirectory / (spec.fileName + PARTIAL_SUFFIX)

    /** A model is present only when its final file exists with the catalog size. */
    fun isPresent(spec: ModelSpec): Boolean =
        fileSystem.metadataOrNull(finalPath(spec))?.size == spec.sizeBytes

    fun prepareDirectory() {
        fileSystem.createDirectories(modelDirectory, mustCreate = false)
    }

    fun discardPartial(spec: ModelSpec) {
        fileSystem.delete(partialPath(spec), mustExist = false)
    }

    /** Verifies the partial file has the catalog size, then moves it into place. */
    fun commit(spec: ModelSpec) {
        val partialSize = fileSystem.metadataOrNull(partialPath(spec))?.size
        check(partialSize == spec.sizeBytes) {
            "The ${spec.displayName} download is incomplete: $partialSize of ${spec.sizeBytes} bytes."
        }
        fileSystem.delete(finalPath(spec), mustExist = false)
        fileSystem.atomicMove(partialPath(spec), finalPath(spec))
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
    }
}

internal fun modelDirectoryUnder(appDirectory: String): Path =
    (appDirectory + "/" + ModelCatalog.MODEL_DIRECTORY_NAME).toPath()
