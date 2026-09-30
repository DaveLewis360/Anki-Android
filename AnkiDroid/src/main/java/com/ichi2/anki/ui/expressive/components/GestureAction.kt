// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive.components

import android.content.Context
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import com.ichi2.anki.ui.internationalization.SentenceCase

/** Actions a study-screen gesture can trigger. */
enum class GestureAction(
    val key: String,
) {
    NONE("none"),
    SHOW_ANSWER("show_answer"),
    GOOD("good"),
    HARD("hard"),
    AGAIN("again"),
    EASY("easy"),
    UNDO("undo"),
    MARK("mark"),
    FLAG("flag"),
    EDIT("edit"),
    BURY("bury"),
    SUSPEND("suspend"),
    MENU("menu"),
    ;

    /** The action's name, in AnkiDroid's and Anki's own translations where they have one. */
    fun label(context: Context): String =
        with(context) {
            when (this@GestureAction) {
                NONE -> getString(R.string.expressive_gesture_none)
                SHOW_ANSWER -> getString(R.string.show_answer)
                GOOD -> getString(R.string.ease_button_good)
                HARD -> getString(R.string.ease_button_hard)
                AGAIN -> getString(R.string.ease_button_again)
                EASY -> getString(R.string.ease_button_easy)
                UNDO -> getString(R.string.undo)
                MARK -> SentenceCase.markNote
                FLAG -> TR.browsingFlag()
                EDIT -> getString(R.string.cardeditor_title_edit_card)
                BURY -> SentenceCase.buryCard
                SUSPEND -> SentenceCase.suspendCard
                MENU -> getString(R.string.expressive_gesture_action_menu)
            }
        }

    companion object {
        fun fromKey(key: String): GestureAction = entries.firstOrNull { it.key == key } ?: NONE
    }
}
