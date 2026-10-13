package org.deer.borrowai

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import okio.Path
import okio.Path.Companion.toPath
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.app.DeviceEnvironment
import org.deer.borrowai.data.app.RealModelSetup
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.demo.DemoModelSetupService
import org.deer.borrowai.data.service.model.ModelTierSelector

class AppServicesViewModelTest {
    @Test
    fun serviceGraphSurvivesProviderRecreationAndClosesWithItsStore() {
        val store = ViewModelStore()
        val epochClock = EpochClock { 1_800_000_000_000L }
        val device = object : DeviceEnvironment {
            override val totalMemoryBytes: Long = 8L * ModelTierSelector.BYTES_PER_GIB
            override val modelDirectory: Path = "/unused-models".toPath()
            override fun createRealModelSetup(scope: CoroutineScope): RealModelSetup =
                RealModelSetup(DemoModelSetupService())
        }
        val factory = AppServicesViewModelFactory(
            serviceVariantId = "demo",
            epochClock = epochClock,
            device = device,
        )
        val first = ViewModelProvider(store, factory).get(AppServicesViewModel::class.java)
        val services = first.services.value
        val recreatedActivityViewModel = ViewModelProvider(store, factory)
            .get(AppServicesViewModel::class.java)

        assertSame(first, recreatedActivityViewModel)
        assertSame(services, recreatedActivityViewModel.services.value)

        services.mediaSession.startRecording()
        val replacement = AppServices.createFromServiceVariant(
            serviceVariantId = "real-model-setup",
            epochClock = epochClock,
            device = device,
        )
        first.replaceServices(replacement)

        assertSame(replacement, first.services.value)
        assertEquals("real-model-setup", first.services.value.variant.id)
        assertEquals(MediaSessionState.Idle, services.mediaSession.state.value)
        assertFailsWith<IllegalStateException> {
            services.mediaSession.startRecording()
        }

        store.clear()

        assertEquals(MediaSessionState.Idle, replacement.mediaSession.state.value)
        assertFailsWith<IllegalStateException> {
            replacement.mediaSession.startRecording()
        }
    }
}
