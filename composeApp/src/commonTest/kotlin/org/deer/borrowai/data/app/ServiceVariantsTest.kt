package org.deer.borrowai.data.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceVariantsTest {
    @Test
    fun demoVariantIsFirstAndDeclaresEveryService() {
        assertEquals("demo", ServiceVariants.all.first().id)
        DataService.entries.forEach { service ->
            assertEquals(ServiceMode.DEMO, ServiceVariants.DEMO.modeOf(service))
        }
    }

    @Test
    fun variantIdsAreUnique() {
        assertEquals(ServiceVariants.all.size, ServiceVariants.all.map { it.id }.distinct().size)
    }

    @Test
    fun variantIdLookupIsExactAfterTrimming() {
        assertEquals(ServiceVariants.DEMO, ServiceVariants.fromId(" demo "))
        assertEquals(ServiceVariants.REAL_MODEL_SETUP, ServiceVariants.fromId("real-model-setup"))
        assertFailsWith<UnknownServiceVariantException> { ServiceVariants.fromId("Demo") }
        assertFailsWith<UnknownServiceVariantException> { ServiceVariants.fromId("real") }
        assertFailsWith<UnknownServiceVariantException> { ServiceVariants.fromId("   ") }
        assertFailsWith<UnknownServiceVariantException> { ServiceVariants.fromId(null) }
    }

    @Test
    fun explicitLaunchVariantWinsOverSavedSelection() {
        assertEquals(
            ServiceVariants.REAL_MODEL_SETUP,
            resolveServiceVariant(
                hasExplicitId = true,
                explicitId = ServiceVariants.REAL_MODEL_SETUP.id,
                savedId = ServiceVariants.DEMO.id,
                isDebuggable = true,
            ),
        )
    }

    @Test
    fun savedSelectionWinsOverDebugDefaultAndCanRemainDemo() {
        assertEquals(
            ServiceVariants.REAL_MODEL_SETUP,
            resolveServiceVariant(
                hasExplicitId = false,
                explicitId = null,
                savedId = ServiceVariants.REAL_MODEL_SETUP.id,
                isDebuggable = true,
            ),
        )
        assertEquals(
            ServiceVariants.DEMO,
            resolveServiceVariant(
                hasExplicitId = false,
                explicitId = null,
                savedId = ServiceVariants.DEMO.id,
                isDebuggable = true,
            ),
        )
    }

    @Test
    fun onlyFreshDebugInstallDefaultsToDemo() {
        assertEquals(
            ServiceVariants.DEMO,
            resolveServiceVariant(
                hasExplicitId = false,
                explicitId = null,
                savedId = null,
                isDebuggable = true,
            ),
        )
        assertFailsWith<UnknownServiceVariantException> {
            resolveServiceVariant(
                hasExplicitId = false,
                explicitId = null,
                savedId = null,
                isDebuggable = false,
            )
        }
    }

    @Test
    fun invalidExplicitOrSavedSelectionFailsFast() {
        assertFailsWith<UnknownServiceVariantException> {
            resolveServiceVariant(
                hasExplicitId = true,
                explicitId = "invalid",
                savedId = ServiceVariants.DEMO.id,
                isDebuggable = true,
            )
        }
        assertFailsWith<UnknownServiceVariantException> {
            resolveServiceVariant(
                hasExplicitId = false,
                explicitId = null,
                savedId = "invalid",
                isDebuggable = true,
            )
        }
    }

    @Test
    fun realModelSetupSwitchesOnlyModelSetup() {
        val variant = ServiceVariants.REAL_MODEL_SETUP
        assertEquals(ServiceMode.REAL, variant.modeOf(DataService.MODEL_SETUP))
        assertEquals(ServiceMode.DEMO, variant.modeOf(DataService.MEMORIES))
        assertEquals(ServiceMode.DEMO, variant.modeOf(DataService.MEDIA_SESSION))
        assertTrue(variant.hasDemoServices)
    }

    @Test
    fun variantMustDeclareEveryService() {
        assertFailsWith<IllegalArgumentException> {
            ServiceVariant(
                id = "partial",
                modes = mapOf(DataService.MEMORIES to ServiceMode.DEMO),
            )
        }
    }

    @Test
    fun variantWithoutDemoServicesReportsNoDemo() {
        val allReal = ServiceVariant(
            id = "all-real",
            modes = DataService.entries.associateWith { ServiceMode.REAL },
        )
        assertFalse(allReal.hasDemoServices)
    }
}
