package org.deer.borrowai.data.service

/**
 * Overall progress milestones shared by the demo and real model setup services.
 * Each setup stage ends at a quarter of the total, which matches the loader's connector segments.
 */
object ModelSetupMilestones {
    const val CHOOSING_END = 0.25f
    const val VOICE_DOWNLOAD_END = 0.5f
    const val VOICE_LOAD_END = 0.75f
    const val LLM_DOWNLOAD_END = 1f
}
