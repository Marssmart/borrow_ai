package org.deer.borrowai

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import okio.Path
import okio.Path.Companion.toPath
import org.deer.borrowai.data.app.DeviceEnvironment
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.model.ModelTierSelector

class AppServicesViewModelTest {
    @Test
    fun serviceGraphSurvivesProviderRecreationAndClosesWithItsStore() {
        val store = ViewModelStore()
        val factory = AppServicesViewModelFactory(
            serviceVariantId = "demo",
            epochClock = EpochClock { 1_800_000_000_000L },
            device = object : DeviceEnvironment {
                override val totalMemoryBytes: Long = 8L * ModelTierSelector.BYTES_PER_GIB
                override val modelDirectory: Path = "/unused-models".toPath()
            },
        )
        val first = ViewModelProvider(store, factory).get(AppServicesViewModel::class.java)
        val services = first.services
        val recreatedActivityViewModel = ViewModelProvider(store, factory)
            .get(AppServicesViewModel::class.java)

        assertSame(first, recreatedActivityViewModel)
        assertSame(services, recreatedActivityViewModel.services)

        services.mediaSession.startRecording()
        store.clear()

        assertEquals(MediaSessionState.Idle, services.mediaSession.state.value)
        assertFailsWith<IllegalStateException> {
            services.mediaSession.startRecording()
        }
    }
}
