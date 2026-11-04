package org.deer.borrowai.data.service.settings

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [AppSettingsService] for tests that need no database. */
class FakeAppSettingsService(
    initial: AppSettings = AppSettings(),
) : AppSettingsService {
    private val mutableSettings = MutableStateFlow(initial)

    override val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    override suspend fun setShowBackgroundActivityIndicator(show: Boolean): Either<AppSettingsError, Unit> {
        mutableSettings.value = mutableSettings.value.copy(showBackgroundActivityIndicator = show)
        return Unit.right()
    }

    override suspend fun setUseGpuAcceleration(use: Boolean): Either<AppSettingsError, Unit> {
        mutableSettings.value = mutableSettings.value.copy(useGpuAcceleration = use)
        return Unit.right()
    }
}
