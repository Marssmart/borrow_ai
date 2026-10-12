package org.deer.borrowai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock

internal class AppServicesViewModel(
    val services: AppServices,
) : ViewModel() {
    override fun onCleared() {
        services.close()
        super.onCleared()
    }
}

internal class AppServicesViewModelFactory(
    private val startupModeFlag: String?,
    private val epochClock: EpochClock,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass != AppServicesViewModel::class.java) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return modelClass.cast(
            AppServicesViewModel(
                AppServices.createFromStartupFlag(
                    startupModeFlag = startupModeFlag,
                    epochClock = epochClock,
                ),
            ),
        )
    }
}
