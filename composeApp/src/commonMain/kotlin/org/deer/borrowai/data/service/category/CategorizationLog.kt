package org.deer.borrowai.data.service.category

/** Writes a categorization outcome or failure to the platform log so it can be diagnosed from logcat. */
internal expect fun logCategorization(message: String, cause: Throwable? = null)
