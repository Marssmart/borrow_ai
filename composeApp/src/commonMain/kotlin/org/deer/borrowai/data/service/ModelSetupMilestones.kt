package org.deer.borrowai.data.service

/**
 * Overall progress milestones for the real model download's notification progress.
 */
object ModelSetupMilestones {
    const val CHOOSING_END = 0.25f
    const val VOICE_DOWNLOAD_END = 0.5f
    const val VOICE_LOAD_END = 0.75f
    const val LLM_DOWNLOAD_END = 1f
}
