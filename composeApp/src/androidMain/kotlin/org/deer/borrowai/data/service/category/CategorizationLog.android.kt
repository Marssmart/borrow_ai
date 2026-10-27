package org.deer.borrowai.data.service.category

import android.util.Log

private const val CATEGORIZATION_LOG_TAG = "MemoryCategorization"

internal actual fun logCategorization(message: String, cause: Throwable?) {
    if (cause == null) {
        Log.i(CATEGORIZATION_LOG_TAG, message)
    } else {
        Log.w(CATEGORIZATION_LOG_TAG, message, cause)
    }
}
