package org.deer.borrowai.data.service.category

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryPromptTest {
    @Test
    fun `parses id and score lines`() {
        assertEquals(
            mapOf(1 to 80, 2 to 10),
            CategoryPrompt.parseScores("1|80\n2|10", setOf(1, 2)),
        )
    }

    @Test
    fun `strips list markers before parsing scores`() {
        assertEquals(
            mapOf(5 to 80, 6 to 20, 7 to 90),
            CategoryPrompt.parseScores("1. 5|80\n- 6|20\n3) 7: 90", setOf(5, 6, 7)),
        )
    }

    @Test
    fun `ignores ids outside the batch and scores outside 0 to 100`() {
        assertEquals(
            mapOf(1 to 100),
            CategoryPrompt.parseScores("1|100\n2|101\n9|50\n3|-5", setOf(1, 2, 3)),
        )
    }

    @Test
    fun `keeps the first score for a repeated id`() {
        assertEquals(mapOf(1 to 90), CategoryPrompt.parseScores("1|90\n1|10", setOf(1)))
    }

    @Test
    fun `reads no scores from prose`() {
        assertTrue(CategoryPrompt.parseScores("Sorry, I cannot score these.", setOf(1)).isEmpty())
    }

    @Test
    fun `does not read digits inside prose lines as scores`() {
        assertEquals(
            mapOf(1 to 80),
            CategoryPrompt.parseScores("Note 2: 3 things to mention\n1|80", setOf(1, 2)),
        )
    }

    @Test
    fun `parses name and description and keeps pipes in the description`() {
        assertEquals(
            listOf(ProposedCategory("Ocean", "Waves | sea")),
            CategoryPrompt.parseProposals("Ocean|Waves | sea", emptySet()),
        )
    }

    @Test
    fun `drops blank, over-limit, existing, and repeated proposals`() {
        val reply = listOf(
            "Ocean|Waves",
            "Work|Job things",
            "Cafe|",
            "|No name",
            "ABCDEFGHIJKLMNOP|Sixteen char name",
            "Valid|" + "x".repeat(101),
            "ocean|Duplicate of an earlier proposal",
            "ABCDEFGHIJKLMNO|Exactly fifteen chars",
            "Hike|Mountain trails",
        ).joinToString("\n")

        assertEquals(
            listOf(
                ProposedCategory("Ocean", "Waves"),
                ProposedCategory("ABCDEFGHIJKLMNO", "Exactly fifteen chars"),
                ProposedCategory("Hike", "Mountain trails"),
            ),
            CategoryPrompt.parseProposals(reply, setOf("Work")),
        )
    }
}
