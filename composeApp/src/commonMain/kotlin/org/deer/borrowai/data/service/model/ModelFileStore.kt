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
    private val digestFile: (Path) -> String = { path -> sha256File(fileSystem, path) },
) {
    fun finalPath(spec: ModelSpec): Path = modelDirectory / spec.fileName

    fun partialPath(spec: ModelSpec): Path = modelDirectory / (spec.fileName + PARTIAL_SUFFIX)

    fun partialIdentityPath(spec: ModelSpec): Path =
        modelDirectory / (spec.fileName + PARTIAL_IDENTITY_SUFFIX)

    fun verifiedRecordPath(spec: ModelSpec): Path = modelDirectory / (spec.fileName + VERIFIED_SUFFIX)

    /**
     * A verified metadata record avoids re-hashing multi-gigabyte files on each launch. Missing or stale records
     * trigger a full digest check before the model is trusted.
     */
    fun isPresent(spec: ModelSpec): Boolean {
        val path = finalPath(spec)
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        if (metadata.size != spec.sizeBytes) {
            fileSystem.delete(verifiedRecordPath(spec), mustExist = false)
            return false
        }
        val lastModifiedAtMillis = metadata.lastModifiedAtMillis ?: return verifyAndCache(spec, path, null)
        val record = readVerificationRecord(spec)
        if (
            record != null &&
            record.sizeBytes == spec.sizeBytes &&
            record.lastModifiedAtMillis == lastModifiedAtMillis &&
            record.sha256 == spec.sha256
        ) {
            return true
        }
        return verifyAndCache(spec, path, lastModifiedAtMillis)
    }

    fun prepareDirectory() {
        fileSystem.createDirectories(modelDirectory, mustCreate = false)
    }

    fun discardPartial(spec: ModelSpec) {
        fileSystem.delete(partialPath(spec), mustExist = false)
        fileSystem.delete(partialIdentityPath(spec), mustExist = false)
        fileSystem.delete(partialIdentityTempPath(spec), mustExist = false)
    }

    /**
     * Persists which catalog model owns the static partial path. An unidentified or differently identified partial
     * is discarded before it can be resumed or committed.
     */
    fun preparePartial(spec: ModelSpec) {
        val expected = PartialIdentity.from(spec)
        val partialExists = fileSystem.metadataOrNull(partialPath(spec)) != null
        val recorded = readPartialIdentity(spec)
        if (partialExists && recorded != expected) {
            fileSystem.delete(partialPath(spec), mustExist = false)
        }
        if (recorded != expected) writePartialIdentity(spec, expected)
    }

    /**
     * Completes an interrupted download when its partial file already has the full catalog size.
     * A matching digest is committed; a full-size mismatch is discarded so a later request starts over.
     */
    fun commitCompletePartial(spec: ModelSpec): Boolean {
        val partialPath = partialPath(spec)
        if (fileSystem.metadataOrNull(partialPath)?.size != spec.sizeBytes) return false
        val digest = digestFile(partialPath)
        if (digest != spec.sha256) {
            discardPartial(spec)
            writePartialIdentity(spec, PartialIdentity.from(spec))
            return false
        }
        commit(spec, digest)
        return true
    }

    /**
     * Moves the partial file into the static name once its size and digest match. A short partial file is kept so
     * the next attempt can resume it; a full-size file with the wrong digest is discarded.
     */
    fun commit(spec: ModelSpec, downloadedSha256: String) {
        val partialSize = fileSystem.metadataOrNull(partialPath(spec))?.size
        if (partialSize != spec.sizeBytes) {
            throw ModelDownloadException(
                "The ${spec.displayName} download was interrupted: $partialSize of ${spec.sizeBytes} bytes received.",
                retryable = true,
            )
        }
        if (downloadedSha256 != spec.sha256) {
            discardPartial(spec)
            error("The ${spec.displayName} download failed checksum verification.")
        }
        fileSystem.delete(finalPath(spec), mustExist = false)
        fileSystem.atomicMove(partialPath(spec), finalPath(spec))
        fileSystem.delete(partialIdentityPath(spec), mustExist = false)
        fileSystem.delete(partialIdentityTempPath(spec), mustExist = false)
        val lastModifiedAtMillis = fileSystem.metadataOrNull(finalPath(spec))?.lastModifiedAtMillis
        writeVerificationRecord(spec, lastModifiedAtMillis)
    }

    private fun verifyAndCache(spec: ModelSpec, path: Path, lastModifiedAtMillis: Long?): Boolean {
        if (digestFile(path) != spec.sha256) {
            fileSystem.delete(verifiedRecordPath(spec), mustExist = false)
            return false
        }
        writeVerificationRecord(spec, lastModifiedAtMillis)
        return true
    }

    private fun readVerificationRecord(spec: ModelSpec): VerificationRecord? {
        val recordPath = verifiedRecordPath(spec)
        if (fileSystem.metadataOrNull(recordPath) == null) return null
        val source = fileSystem.source(recordPath).buffer()
        try {
            val fields = source.readUtf8().trimEnd('\n').split('\n')
            if (fields.size != RECORD_FIELD_COUNT || fields[0] != RECORD_VERSION) return null
            val sizeBytes = fields[1].toLongOrNull() ?: return null
            val lastModifiedAtMillis = fields[2].toLongOrNull() ?: return null
            if (!DIGEST_PATTERN.matches(fields[3])) return null
            return VerificationRecord(sizeBytes, lastModifiedAtMillis, fields[3])
        } finally {
            source.close()
        }
    }

    private fun readPartialIdentity(spec: ModelSpec): PartialIdentity? {
        val identityPath = partialIdentityPath(spec)
        if (fileSystem.metadataOrNull(identityPath) == null) return null
        val source = fileSystem.source(identityPath).buffer()
        try {
            val fields = source.readUtf8().trimEnd('\n').split('\n')
            if (fields.size != PARTIAL_IDENTITY_FIELD_COUNT || fields[0] != PARTIAL_IDENTITY_VERSION) {
                return null
            }
            val sizeBytes = fields[3].toLongOrNull() ?: return null
            if (!DIGEST_PATTERN.matches(fields[4])) return null
            return PartialIdentity(
                downloadUrl = fields[1],
                revision = fields[2],
                sizeBytes = sizeBytes,
                sha256 = fields[4],
            )
        } finally {
            source.close()
        }
    }

    private fun writePartialIdentity(spec: ModelSpec, identity: PartialIdentity) {
        val identityPath = partialIdentityPath(spec)
        val temporaryPath = partialIdentityTempPath(spec)
        fileSystem.delete(temporaryPath, mustExist = false)
        val sink = fileSystem.sink(temporaryPath).buffer()
        try {
            sink.writeUtf8(
                "$PARTIAL_IDENTITY_VERSION\n${identity.downloadUrl}\n${identity.revision}\n" +
                    "${identity.sizeBytes}\n${identity.sha256}\n",
            )
        } finally {
            sink.close()
        }
        fileSystem.delete(identityPath, mustExist = false)
        fileSystem.atomicMove(temporaryPath, identityPath)
    }

    private fun partialIdentityTempPath(spec: ModelSpec): Path =
        modelDirectory / (spec.fileName + PARTIAL_IDENTITY_TEMP_SUFFIX)

    private fun writeVerificationRecord(spec: ModelSpec, lastModifiedAtMillis: Long?) {
        if (lastModifiedAtMillis == null) {
            fileSystem.delete(verifiedRecordPath(spec), mustExist = false)
            return
        }
        val recordPath = verifiedRecordPath(spec)
        val temporaryPath = modelDirectory / (spec.fileName + VERIFIED_TEMP_SUFFIX)
        fileSystem.delete(temporaryPath, mustExist = false)
        val sink = fileSystem.sink(temporaryPath).buffer()
        try {
            sink.writeUtf8(
                "$RECORD_VERSION\n${spec.sizeBytes}\n$lastModifiedAtMillis\n${spec.sha256}\n",
            )
        } finally {
            sink.close()
        }
        fileSystem.delete(recordPath, mustExist = false)
        fileSystem.atomicMove(temporaryPath, recordPath)
    }

    private data class VerificationRecord(
        val sizeBytes: Long,
        val lastModifiedAtMillis: Long,
        val sha256: String,
    )

    private data class PartialIdentity(
        val downloadUrl: String,
        val revision: String,
        val sizeBytes: Long,
        val sha256: String,
    ) {
        companion object {
            fun from(spec: ModelSpec) = PartialIdentity(
                downloadUrl = spec.downloadUrl,
                revision = spec.revision,
                sizeBytes = spec.sizeBytes,
                sha256 = spec.sha256,
            )
        }
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
        const val PARTIAL_IDENTITY_SUFFIX = ".part.identity"
        const val PARTIAL_IDENTITY_TEMP_SUFFIX = ".part.identity.tmp"
        const val VERIFIED_SUFFIX = ".verified"
        const val VERIFIED_TEMP_SUFFIX = ".verified.tmp"
        const val RECORD_VERSION = "1"
        const val RECORD_FIELD_COUNT = 4
        const val PARTIAL_IDENTITY_VERSION = "1"
        const val PARTIAL_IDENTITY_FIELD_COUNT = 5
        val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private fun sha256File(fileSystem: FileSystem, path: Path): String {
    val hashing = HashingSource.sha256(fileSystem.source(path))
    val source = hashing.buffer()
    try {
        source.readAll(blackholeSink())
    } finally {
        source.close()
    }
    return hashing.hash.hex()
}

internal fun modelDirectoryUnder(appDirectory: String): Path =
    (appDirectory + "/" + ModelCatalog.MODEL_DIRECTORY_NAME).toPath()
