package org.deer.borrowai.data.service.model

import android.util.Log

private const val MODEL_SETUP_LOG_TAG = "BorrowAi.ModelSetup"

internal actual fun logModelSetupFailure(detail: String) {
    Log.w(MODEL_SETUP_LOG_TAG, "Model setup failed: $detail")
}
