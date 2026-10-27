package org.deer.borrowai.data.service.category

import org.deer.borrowai.data.service.llm.LlmOutcome
import org.deer.borrowai.data.service.llm.LocalLlmRuntime

/** Creates a [CategoryModel] on [runtime]. The runtime is owned and closed by the caller. */
internal fun createCategoryModel(runtime: LocalLlmRuntime): CategoryModel = AndroidCategoryModel(runtime)

/**
 * Sends categorization prompts to the shared [LocalLlmRuntime]. Returns [CategoryModelReply.ModelMissing] while the
 * model file is missing, so the categorization job stops instead of counting attempts.
 */
internal class AndroidCategoryModel(
    private val runtime: LocalLlmRuntime,
    private val timeoutMillis: Long = CATEGORY_TIMEOUT_MILLIS,
) : CategoryModel {
    override suspend fun generate(systemInstruction: String, userMessage: String): CategoryModelReply =
        when (val outcome = runtime.generate(systemInstruction, userMessage, timeoutMillis)) {
            is LlmOutcome.Text -> CategoryModelReply.Text(outcome.text)
            LlmOutcome.ModelMissing -> CategoryModelReply.ModelMissing
            LlmOutcome.TimedOut -> CategoryModelReply.Failed(CategoryModelTimeoutException(timeoutMillis))
            is LlmOutcome.Failed -> CategoryModelReply.Failed(outcome.cause)
        }

    private companion object {
        const val CATEGORY_TIMEOUT_MILLIS = 60_000L
    }
}

/** Thrown when the model does not answer a categorization prompt within [timeoutMillis]. */
internal class CategoryModelTimeoutException(timeoutMillis: Long) :
    RuntimeException("Category model did not answer within $timeoutMillis ms.")
