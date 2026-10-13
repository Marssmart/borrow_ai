package org.deer.borrowai.data.service.model

import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer

/** Static model location: `<modelDirectory>/<spec.fileName>`, with `.part` used until a download is verified. */
class ModelFileStore(
    val modelDirectory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun finalPath(spec: ModelSpec): Path = modelDirectory / spec.fileName

    fun partialPath(spec: ModelSpec): Path = modelDirectory / (spec.fileName + PARTIAL_SUFFIX)

    /**
     * A model is present only when its final file has the catalog size and re-hashes to the catalog SHA-256,
     * so a skip never trusts a file that was not verified.
     */
    fun isPresent(spec: ModelSpec): Boolean {
        val path = finalPath(spec)
        if (fileSystem.metadataOrNull(path)?.size != spec.sizeBytes) return false
        return sha256Of(path) == spec.sha256
    }

    fun prepareDirectory() {
        fileSystem.createDirectories(modelDirectory, mustCreate = false)
    }

    fun discardPartial(spec: ModelSpec) {
        fileSystem.delete(partialPath(spec), mustExist = false)
    }

    /**
     * Moves the partial file into the static name once its size and digest match. A short partial file is kept so
     * the next attempt can resume it; a full-size file with the wrong digest is discarded.
     */
    fun commit(spec: ModelSpec, downloadedSha256: String) {
        val partialSize = fileSystem.metadataOrNull(partialPath(spec))?.size
        check(partialSize == spec.sizeBytes) {
            "The ${spec.displayName} download is incomplete: $partialSize of ${spec.sizeBytes} bytes."
        }
        if (downloadedSha256 != spec.sha256) {
            discardPartial(spec)
            error("The ${spec.displayName} download failed checksum verification.")
        }
        fileSystem.delete(finalPath(spec), mustExist = false)
        fileSystem.atomicMove(partialPath(spec), finalPath(spec))
    }

    private fun sha256Of(path: Path): String {
        val hashing = HashingSource.sha256(fileSystem.source(path))
        val source = hashing.buffer()
        try {
            source.readAll(blackholeSink())
        } finally {
            source.close()
        }
        return hashing.hash.hex()
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
    }
}

internal fun modelDirectoryUnder(appDirectory: String): Path =
    (appDirectory + "/" + ModelCatalog.MODEL_DIRECTORY_NAME).toPath()
