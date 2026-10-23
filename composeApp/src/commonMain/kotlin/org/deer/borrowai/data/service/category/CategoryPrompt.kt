package org.deer.borrowai.data.service.category

import org.deer.borrowai.data.domain.CATEGORY_DESCRIPTION_MAX_LENGTH
import org.deer.borrowai.data.domain.CATEGORY_NAME_MAX_LENGTH
import org.deer.borrowai.data.domain.Category

/** A category the model proposed that is not stored yet. */
data class ProposedCategory(val name: String, val description: String)

/** Builds the categorization requests and parses the model's replies. */
object CategoryPrompt {
    const val MATCH_THRESHOLD = 75
    const val MAX_TRANSCRIPTION_CHARS = 8_000

    const val SCORING_SYSTEM_INSTRUCTION =
        "You score how well each category matches a personal voice note. " +
            "For every category listed, reply with one line in the form id|score, where score is an integer from 0 to 100 " +
            "for how well that category describes the note. Reply with only these lines, no preamble or markdown."

    const val BACKFILL_SYSTEM_INSTRUCTION =
        "You suggest new categories that best describe a personal voice note. " +
            "Reply with one line per category in the form name|description. " +
            "The name is at most $CATEGORY_NAME_MAX_LENGTH characters and the description is at most " +
            "$CATEGORY_DESCRIPTION_MAX_LENGTH characters. Do not repeat the existing categories. " +
            "Reply with only these lines, no preamble or markdown."

    private val scoreLine = Regex("^(\\d+)\\s*[|:=,]\\s*(\\d+)")
    private val listMarker = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s*")

    fun scoringMessage(transcription: String, batch: List<Category>): String =
        "Voice note:\n${transcription.trim().take(MAX_TRANSCRIPTION_CHARS)}\n\n" +
            "Categories:\n" + batch.joinToString("\n") { "${it.id}. ${it.name}: ${it.description}" }

    fun backfillMessage(transcription: String, existing: List<Category>, count: Int): String {
        val names = existing.joinToString(", ") { it.name }.ifBlank { "none" }
        return "Voice note:\n${transcription.trim().take(MAX_TRANSCRIPTION_CHARS)}\n\n" +
            "Existing categories: $names\n" +
            "Suggest up to $count new categories."
    }

    /** Reads `id|score` lines. Ignores ids outside [batchIds], scores outside 0..100, and repeated ids. */
    fun parseScores(reply: String, batchIds: Set<Int>): Map<Int, Int> {
        val scores = linkedMapOf<Int, Int>()
        for (line in reply.lineSequence()) {
            val match = scoreLine.find(line.replace(listMarker, "")) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val score = match.groupValues[2].toIntOrNull() ?: continue
            if (id in batchIds && score in 0..100 && id !in scores) scores[id] = score
        }
        return scores
    }

    /**
     * Reads `name|description` lines. Drops proposals with a blank part, a name or description over the
     * stored limits, or a name that already exists (case-insensitive) or repeats an earlier proposal.
     */
    fun parseProposals(reply: String, existingNames: Set<String>): List<ProposedCategory> {
        val seen = existingNames.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val proposals = mutableListOf<ProposedCategory>()
        for (line in reply.lineSequence()) {
            val parts = line.replace(listMarker, "").split('|', limit = 2)
            if (parts.size != 2) continue
            val name = parts[0].trim()
            val description = parts[1].trim()
            if (name.isEmpty() || description.isEmpty()) continue
            if (name.length > CATEGORY_NAME_MAX_LENGTH || description.length > CATEGORY_DESCRIPTION_MAX_LENGTH) continue
            if (!seen.add(name.lowercase())) continue
            proposals += ProposedCategory(name, description)
        }
        return proposals
    }
}
