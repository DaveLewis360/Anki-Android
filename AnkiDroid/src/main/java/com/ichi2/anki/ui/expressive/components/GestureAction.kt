// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

/** Actions a study-screen gesture can trigger. */
enum class GestureAction(
    val key: String,
    val label: String,
) {
    NONE("none", "None"),
    SHOW_ANSWER("show_answer", "Show answer"),
    GOOD("good", "Good"),
    HARD("hard", "Hard"),
    AGAIN("again", "Again"),
    EASY("easy", "Easy"),
    UNDO("undo", "Undo"),
    MARK("mark", "Mark"),
    FLAG("flag", "Flag"),
    EDIT("edit", "Edit note"),
    BURY("bury", "Bury"),
    SUSPEND("suspend", "Suspend"),
    MENU("menu", "Action menu"),
    ;

    companion object {
        fun fromKey(key: String): GestureAction = entries.firstOrNull { it.key == key } ?: NONE
    }
}
