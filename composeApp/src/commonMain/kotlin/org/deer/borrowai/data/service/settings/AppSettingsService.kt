package org.deer.borrowai.data.service.settings

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow

/** App-wide preferences set by the user. Each one has a default that applies until the user changes it. */
data class AppSettings(
    /** Shows the top indicator while background work runs. On by default. */
    val showBackgroundActivityIndicator: Boolean = true,
    /** Lets on-device models use the GPU where the device and model tier support it. On by default. */
    val useGpuAcceleration: Boolean = true,
)

sealed interface AppSettingsError {
    data class Persistence(val message: String) : AppSettingsError
}

interface AppSettingsService {
    /** The current settings. Starts with the stored values, or the defaults when nothing is stored. */
    val settings: StateFlow<AppSettings>

    /** Stores whether the background activity indicator is shown. [settings] changes only after the write succeeds. */
    suspend fun setShowBackgroundActivityIndicator(show: Boolean): Either<AppSettingsError, Unit>

    /** Stores whether GPU acceleration may be used. [settings] changes only after the write succeeds. */
    suspend fun setUseGpuAcceleration(use: Boolean): Either<AppSettingsError, Unit>
}
