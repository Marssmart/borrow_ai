package org.deer.borrowai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.app.DeviceEnvironment
import org.deer.borrowai.data.domain.EpochClock

internal class AppServicesViewModel(
    services: AppServices,
) : ViewModel() {
    private val mutableServices = MutableStateFlow(services)

    val services: StateFlow<AppServices> = mutableServices.asStateFlow()

    fun replaceServices(services: AppServices) {
        val previous = mutableServices.value
        mutableServices.value = services
        previous.close()
    }

    override fun onCleared() {
        mutableServices.value.close()
        super.onCleared()
    }
}

internal class AppServicesViewModelFactory(
    private val serviceVariantId: String?,
    private val epochClock: EpochClock,
    private val device: DeviceEnvironment,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass != AppServicesViewModel::class.java) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return modelClass.cast(
            AppServicesViewModel(
                AppServices.createFromServiceVariant(
                    serviceVariantId = serviceVariantId,
                    epochClock = epochClock,
                    device = device,
                ),
            ),
        )
    }
}
