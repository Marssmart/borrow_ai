package org.deer.borrowai.data.service.kotbase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotbase.Collection
import kotbase.Database
import kotbase.MutableDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deer.borrowai.data.service.settings.AppSettings
import org.deer.borrowai.data.service.settings.AppSettingsError
import org.deer.borrowai.data.service.settings.AppSettingsService

/**
 * [AppSettingsService] backed by Couchbase Lite through Kotbase. All settings live in one document in their own
 * collection of the borrow-ai database, so they stay apart from memories and speaker data.
 */
class KotbaseAppSettingsService private constructor(
    private val collection: Collection,
) : AppSettingsService {
    private val mutex = Mutex()
    private val mutableSettings = MutableStateFlow(readSettings())

    override val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    override suspend fun setShowBackgroundActivityIndicator(show: Boolean): Either<AppSettingsError, Unit> =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                persisting {
                    val document = collection.getDocument(SETTINGS_ID)?.toMutable() ?: MutableDocument(SETTINGS_ID)
                    document.setBoolean(KEY_SHOW_BACKGROUND_ACTIVITY_INDICATOR, show)
                    collection.save(document)
                    mutableSettings.value = mutableSettings.value.copy(showBackgroundActivityIndicator = show)
                }
            }
        }

    /** Reads the stored settings. A key that is missing keeps its default, so a newer build can add settings safely. */
    private fun readSettings(): AppSettings {
        val defaults = AppSettings()
        val document = collection.getDocument(SETTINGS_ID) ?: return defaults
        return defaults.copy(
            showBackgroundActivityIndicator = if (document.contains(KEY_SHOW_BACKGROUND_ACTIVITY_INDICATOR)) {
                document.getBoolean(KEY_SHOW_BACKGROUND_ACTIVITY_INDICATOR)
            } else {
                defaults.showBackgroundActivityIndicator
            },
        )
    }

    companion object {
        private const val COLLECTION_NAME = "app-settings"
        private const val SETTINGS_ID = "app-settings"
        private const val KEY_SHOW_BACKGROUND_ACTIVITY_INDICATOR = "showBackgroundActivityIndicator"

        /** Opens the settings store on [database], creating its collection on first use. */
        suspend fun open(database: Database): Either<AppSettingsError, KotbaseAppSettingsService> =
            withContext(Dispatchers.Default) {
                persisting { database.createCollection(COLLECTION_NAME) }
                    .map { collection -> KotbaseAppSettingsService(collection) }
            }
    }
}

/** Runs [block], mapping any failure except cancellation to a [AppSettingsError.Persistence]. */
private fun <T> persisting(block: () -> T): Either<AppSettingsError, T> = try {
    block().right()
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
    AppSettingsError.Persistence(exception.message ?: exception::class.simpleName.orEmpty()).left()
}
