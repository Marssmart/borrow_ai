package org.deer.borrowai.data.service.category

import platform.Foundation.NSLog

internal actual fun logCategorization(message: String, cause: Throwable?) {
    if (cause == null) {
        NSLog("MemoryCategorization: %@", message)
    } else {
        NSLog("MemoryCategorization: %@ (%@)", message, cause.message ?: cause::class.simpleName.orEmpty())
    }
}
