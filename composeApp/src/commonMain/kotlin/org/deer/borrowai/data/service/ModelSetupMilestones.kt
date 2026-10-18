package org.deer.borrowai.data.service

/**
 * Overall progress milestones for the real model download's notification progress.
 */
object ModelSetupMilestones {
    const val CHOOSING_END = 0.2f
    const val LIVE_DOWNLOAD_END = 0.4f
    const val VOICE_DOWNLOAD_END = 0.6f
    const val VOICE_LOAD_END = 0.7f
    const val LLM_DOWNLOAD_END = 1f
}
