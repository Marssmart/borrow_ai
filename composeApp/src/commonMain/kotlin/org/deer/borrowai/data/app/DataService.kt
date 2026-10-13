package org.deer.borrowai.data.app

/** Every data service the app graph builds. Adding an entry requires each variant to declare it. */
enum class DataService {
    MEMORIES,
    MEDIA_SESSION,
    MODEL_SETUP,
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

    /** Only model setup uses the real download service; memories and media stay demo. */
    val REAL_MODEL_SETUP = ServiceVariant(
        id = "real-model-setup",
        modes = mapOf(
            DataService.MEMORIES to ServiceMode.DEMO,
            DataService.MEDIA_SESSION to ServiceMode.DEMO,
            DataService.MODEL_SETUP to ServiceMode.REAL,
        ),
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
