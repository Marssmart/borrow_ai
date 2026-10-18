package org.deer.borrowai.data.service.model

import platform.Foundation.NSLog

internal actual fun logModelSetupFailure(detail: String) {
    NSLog("Model setup failed: %@", detail)
}
