package org.deer.borrowai

internal data class MockMemory(
    val id: String,
    val createdAtOrder: Long,
    val dateLabel: String,
    val title: String,
    val summary: String,
    val originalText: String,
)

internal val MockMemories = listOf(
    MockMemory(
        id = "release-ideas",
        createdAtOrder = 202610111140L,
        dateLabel = "Today · 11:40 AM",
        title = "Ideas for the first release",
        summary = "Keep the initial experience focused on recording, a readable transcript, and a short useful summary.",
        originalText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. For the first release, keep the capture experience clear and direct. " +
            "Show the transcript while speaking, then make the finished note easy to scan. We can add more organization after the basic flow feels good.",
    ),
    MockMemory(
        id = "weekend-plan",
        createdAtOrder = 202610111005L,
        dateLabel = "Today · 10:05 AM",
        title = "Weekend trip checklist",
        summary = "Pack the charger, check the train time, and remember to bring a light jacket for the evening.",
        originalText = "Lorem ipsum dolor sit amet. Check the train departure before leaving home. Pack the phone charger, headphones, and the small notebook. " +
            "The weather may cool down later, so bring a light jacket too.",
    ),
    MockMemory(
        id = "reading-notes",
        createdAtOrder = 202610101825L,
        dateLabel = "Yesterday · 6:25 PM",
        title = "Reading notes: small habits",
        summary = "Small routines are easier to maintain when they are attached to an existing daily habit.",
        originalText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. A useful routine should be small enough to repeat on a busy day. " +
            "Attach a new action to something that already happens, then review it after a week instead of trying to change everything at once.",
    ),
    MockMemory(
        id = "team-review",
        createdAtOrder = 202610091410L,
        dateLabel = "October 9 · 2:10 PM",
        title = "Questions for the team review",
        summary = "Ask which part of the prototype needs clarification and what should be tested next.",
        originalText = "Lorem ipsum dolor sit amet. During the review, ask where the prototype feels unclear and which task should be tested next. " +
            "Write down any assumptions that need confirmation before making the next design pass.",
    ),
).sortedByDescending(MockMemory::createdAtOrder)
