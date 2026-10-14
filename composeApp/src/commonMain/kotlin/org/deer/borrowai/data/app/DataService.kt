package org.deer.borrowai.data.app

/** Switchable data services. Model setup always uses the real download service and is not listed here. */
enum class DataService {
    MEMORIES,
    MEDIA_SESSION,
}

enum class ServiceMode {
    DEMO,
    REAL,
}

/** A named selection of demo or real mode for every [DataService]. */
class ServiceVariant(
    val id: String,
    val modes: Map<DataService, ServiceMode>,
) {
    init {
        require(id.isNotBlank()) { "A service variant needs an id." }
        require(modes.keys == DataService.entries.toSet()) {
            "Service variant '$id' must declare a mode for every data service."
        }
    }

    fun modeOf(service: DataService): ServiceMode = modes.getValue(service)

    val hasDemoServices: Boolean
        get() = modes.values.any { it == ServiceMode.DEMO }
}

class UnknownServiceVariantException(
    requested: String?,
) : IllegalArgumentException(
    "Unknown or missing data service variant '${requested.orEmpty()}'. " +
        "Valid variants: ${ServiceVariants.ids.joinToString()}.",
)

object ServiceVariants {
    /** Every service runs against in-memory demo providers. Listed first and used as the debug default. */
    val DEMO = ServiceVariant(
        id = "demo",
        modes = DataService.entries.associateWith { ServiceMode.DEMO },
    )

    /** Memories and media stay demo; model setup is always the real download service. */
    val REAL_MODEL_SETUP = ServiceVariant(
        id = "real-model-setup",
        modes = DataService.entries.associateWith { ServiceMode.DEMO },
    )

    val all: List<ServiceVariant> = listOf(DEMO, REAL_MODEL_SETUP)

    val ids: List<String>
        get() = all.map { it.id }

    init {
        require(all.map { it.id }.distinct().size == all.size) { "Service variant ids must be unique." }
    }

    /** Resolves an exact variant id. Missing, blank, or unknown ids fail fast. */
    fun fromId(id: String?): ServiceVariant {
        val normalized = id?.trim()
        return all.firstOrNull { it.id == normalized } ?: throw UnknownServiceVariantException(id)
    }
}

/** Resolves startup selection without silently replacing an explicit or persisted invalid id. */
fun resolveServiceVariant(
    hasExplicitId: Boolean,
    explicitId: String?,
    savedId: String?,
    isDebuggable: Boolean,
): ServiceVariant {
    val requestedId = when {
        hasExplicitId -> explicitId
        savedId != null -> savedId
        isDebuggable -> ServiceVariants.DEMO.id
        else -> null
    }
    return ServiceVariants.fromId(requestedId)
}

data class ServiceVariantLaunchSelection(
    val variant: ServiceVariant,
    val persistExplicitSelection: Boolean,
)

/**
 * A known caller must be the shell/IDE path. Legacy Android and older onNewIntent callbacks may not expose a caller;
 * those extras are accepted only in debuggable builds, never in release.
 */
fun resolveServiceVariantLaunch(
    hasExplicitId: Boolean,
    explicitId: String?,
    callerUid: Int?,
    trustedCallerUid: Int,
    savedId: String?,
    isDebuggable: Boolean,
): ServiceVariantLaunchSelection {
    val trustedExplicitId = hasExplicitId &&
        isDebuggable &&
        (callerUid == null || callerUid == trustedCallerUid)
    return ServiceVariantLaunchSelection(
        variant = resolveServiceVariant(
            hasExplicitId = trustedExplicitId,
            explicitId = if (trustedExplicitId) explicitId else null,
            savedId = savedId,
            isDebuggable = isDebuggable,
        ),
        persistExplicitSelection = trustedExplicitId,
    )
}
