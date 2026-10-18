package org.deer.borrowai.data.service.model

/** Writes a model setup failure to the platform log so it can be diagnosed without the UI. */
internal expect fun logModelSetupFailure(detail: String)
