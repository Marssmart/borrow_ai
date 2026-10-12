package org.deer.borrowai

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionState

class AppServicesViewModelTest {
    @Test
    fun serviceGraphSurvivesProviderRecreationAndClosesWithItsStore() {
        val store = ViewModelStore()
        val factory = AppServicesViewModelFactory(
            startupModeFlag = "demo",
            epochClock = EpochClock { 1_800_000_000_000L },
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
