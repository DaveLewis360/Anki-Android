// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

/** Mock data for the expressive UI shell. Real collection data is wired in a later phase. */

data class SampleDeck(
    val id: Long,
    val name: String,
    val depth: Int = 0,
    val filtered: Boolean = false,
    val newCount: Int,
    val learnCount: Int,
    val dueCount: Int,
    val children: List<SampleDeck> = emptyList(),
    val expanded: Boolean = true,
)

data class SampleCard(
    val id: Long,
    val question: String,
    val answer: String,
    val deckName: String,
    val dueIn: String,
    val tag: String,
)

data class SampleStat(
    val title: String,
    val value: String,
    val subtitle: String,
)

val sampleDecks =
    listOf(
        SampleDeck(1, "Anatomy", newCount = 4, learnCount = 2, dueCount = 12),
        SampleDeck(
            2,
            "Languages",
            newCount = 0,
            learnCount = 0,
            dueCount = 0,
            children =
                listOf(
                    SampleDeck(3, "Japanese", depth = 1, newCount = 6, learnCount = 3, dueCount = 8),
                    SampleDeck(4, "English", depth = 1, newCount = 2, learnCount = 0, dueCount = 5),
                ),
        ),
        SampleDeck(5, "History", filtered = true, newCount = 0, learnCount = 1, dueCount = 3),
        SampleDeck(6, "Programming", newCount = 10, learnCount = 0, dueCount = 21),
    )

val sampleCards =
    listOf(
        SampleCard(1, "What is the superior-most cavity of the heart?", "Right atrium", "Anatomy", "in 3 days", "Cardiovascular"),
        SampleCard(2, "How many vertebrae are in the human spine?", "33", "Anatomy", "in 10 days", "Skeletal"),
        SampleCard(3, "What does 「ありがとう」 mean?", "Thank you", "Japanese", "in 1 day", "Politeness"),
        SampleCard(
            4,
            "Define abstraction (OOP)",
            "Hiding implementation details behind an interface",
            "Programming",
            "in 25 days",
            "Paradigms",
        ),
        SampleCard(5, "When was the Treaty of Trianon signed?", "June 4, 1920", "History", "today", "20th century"),
        SampleCard(6, "Which chamber pumps blood to the lungs?", "Right ventricle", "Anatomy", "in 6 days", "Cardiovascular"),
    )

val sampleStats =
    listOf(
        SampleStat("Reviewed today", "84", "cards"),
        SampleStat("Streak", "12", "days"),
        SampleStat("Correct", "91%", "last 7 days"),
        SampleStat("Average", "38s", "per card"),
    )

val weekActivity = listOf(0.35f, 0.6f, 0.45f, 0.8f, 0.55f, 0.95f, 0.7f)

val studyQuestions =
    mapOf(
        "question" to "What is the superior-most cavity of the heart?",
        "answer" to "The right atrium",
    )
