// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors

package com.ichi2.anki.lint.rules

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.gradle
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.detector.api.Issue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The Expressive client stays on its design system; other code is not checked. */
@RunWith(JUnit4::class)
class ExpressiveDesignDetectorTest {
    /**
     * Just enough of Compose for the calls to resolve. `Color` and `Role` are value classes, as in
     * Compose: functions taking them have mangled JVM names, as the real ones do.
     */
    private val composeStubs: Array<TestFile> =
        arrayOf(
            kotlin(
                """
                package androidx.compose.ui
                interface Modifier { companion object : Modifier }
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.ui.semantics
                @JvmInline value class Role(val value: Int) { companion object { val Button = Role(0) } }
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.ui.graphics
                @JvmInline value class Color(val value: ULong)
                fun Color(color: Long): Color = TODO()
                fun Color(red: Float, green: Float, blue: Float): Color = TODO()
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.material3
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.graphics.Color
                fun Text(text: String, modifier: Modifier = Modifier, color: Color = Color(0L)) {}
                fun Icon(painter: Any, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color(0L)) {}
                fun CircularProgressIndicator(modifier: Modifier = Modifier, color: Color = Color(0L)) {}
                class SnackbarHostState { suspend fun showSnackbar(message: String, actionLabel: String? = null): Any = TODO() }
                fun LoadingIndicator(modifier: Modifier = Modifier) {}
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.animation.core
                fun <T> tween(durationMillis: Int = 300): Any = TODO()
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.foundation
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.semantics.Role
                fun Modifier.clickable(
                    enabled: Boolean = true,
                    onClickLabel: String? = null,
                    role: Role? = null,
                    onClick: () -> Unit,
                ): Modifier = this
                fun Image(painter: Any, contentDescription: String?, modifier: Modifier = Modifier) {}
                fun Modifier.clickable(interactionSource: Any?, indication: Any?, role: Role? = null, onClick: () -> Unit): Modifier = this
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.foundation.selection
                import androidx.compose.ui.Modifier
                fun Modifier.selectable(selected: Boolean, interactionSource: Any?, indication: Any?, onClick: () -> Unit): Modifier = this
                """,
            ).indented(),
            kotlin(
                """
                package com.ichi2.anki
                fun stringResource(id: Int): String = TODO()
                """,
            ).indented(),
        )

    private fun check(source: String): TestLintResult =
        lint()
            .allowMissingSdk()
            .files(*composeStubs, kotlin(source).indented())
            .issues(*ExpressiveDesignDetector.ISSUES.toTypedArray())
            .run()

    /**
     * How many errors of each issue the result holds. Should a test mode report differently, lint
     * describes every mode's result instead: the counts add up, and the test fails.
     */
    private fun TestLintResult.counts(): Map<String, Int> {
        var counted: Map<String, Int> = emptyMap()
        check({ output ->
            counted =
                ExpressiveDesignDetector.ISSUES
                    .associate { issue: Issue -> issue.id to Regex("\\[${issue.id}]").findAll(output).count() }
                    .filterValues { it > 0 }
        })
        return counted
    }

    @Test
    fun `hardcoded words, circular progress, tweens and color literals are errors in the Expressive client`() {
        val result =
            check(
                """
                package com.ichi2.anki.ui.expressive.screens

                import androidx.compose.animation.core.tween
                import androidx.compose.material3.CircularProgressIndicator
                import androidx.compose.material3.LoadingIndicator
                import androidx.compose.material3.Text
                import androidx.compose.ui.graphics.Color
                import com.ichi2.anki.stringResource

                fun Screen(name: String) {
                    Text("Well done!")
                    Text("Hello, ${'$'}name")
                    Text(text = "Back to decks")
                    Text("•")
                    Text("")
                    Text(stringResource(1))
                    Text(name)
                    CircularProgressIndicator()
                    LoadingIndicator()
                    tween<Float>(300)
                    Color(0xFF6750A4)
                    Color(red = 0.2f, green = 0.3f, blue = 0.4f)
                    Color(red = 0.2f, green = 0.3f, blue = name.length.toFloat())
                }
                """,
            )

        assertEquals(
            mapOf(
                "ExpressiveHardcodedText" to 3,
                "ExpressiveCircularProgress" to 1,
                "ExpressiveRawTween" to 1,
                "ExpressiveColorLiteral" to 2,
            ),
            result.counts(),
        )
        // Lint prints the message as text: its markdown backticks are gone.
        result.expectContains(ExpressiveDesignDetector.HARDCODED_TEXT_MESSAGE.replace("`", ""))
    }

    @Test
    fun `words told by TalkBack or shown in a snackbar are checked too, in whichever branch`() {
        val result =
            check(
                """
                package com.ichi2.anki.ui.expressive.screens

                import androidx.compose.foundation.Image
                import androidx.compose.foundation.clickable
                import androidx.compose.material3.Icon
                import androidx.compose.material3.SnackbarHostState
                import androidx.compose.material3.Text
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.semantics.Role
                import com.ichi2.anki.stringResource

                suspend fun Screen(snackbars: SnackbarHostState, done: Boolean, name: String?) {
                    Icon(Any(), contentDescription = "Back")
                    Icon(Any(), contentDescription = null)
                    Image(Any(), contentDescription = stringResource(2))
                    snackbars.showSnackbar("Deck deleted", actionLabel = "Undo")
                    snackbars.showSnackbar(stringResource(3))
                    Modifier.clickable(onClickLabel = "Open", role = Role.Button) {}
                    Text(if (done) "Done" else stringResource(4))
                    Text(name ?: "Unnamed")
                    Text(name ?: stringResource(5))
                    Text(
                        when {
                            done -> stringResource(6)
                            else -> "Later"
                        },
                    )
                    Text(
                        when {
                            done -> stringResource(7)
                            else -> name ?: ""
                        },
                    )
                }
                """,
            )

        assertEquals(mapOf("ExpressiveHardcodedText" to 7), result.counts())
    }

    @Test
    fun `controls keep their indication and tell their role`() {
        val result =
            check(
                """
                package com.ichi2.anki.ui.expressive.components

                import androidx.compose.foundation.clickable
                import androidx.compose.foundation.selection.selectable
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.semantics.Role

                fun rows(onClick: () -> Unit): List<Modifier> =
                    listOf(
                        Modifier.clickable { onClick() },
                        Modifier.clickable(role = Role.Button) { onClick() },
                        Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) { onClick() },
                        Modifier.clickable(null, null, Role.Button, onClick),
                        Modifier.selectable(selected = true, interactionSource = null, indication = null, onClick = onClick),
                    )
                """,
            )

        assertEquals(mapOf("ExpressiveClickableWithoutRole" to 1, "ExpressiveIndicationNull" to 3), result.counts())
    }

    @Test
    fun `the theme may define colors`() {
        check(
            """
            package com.ichi2.anki.ui.expressive.theme

            import androidx.compose.ui.graphics.Color

            val Seed = Color(0xFF6750A4)
            """,
        ).expectClean()
    }

    @Test
    fun `code outside the Expressive client is not checked`() {
        check(
            """
            package com.ichi2.anki.classic

            import androidx.compose.animation.core.tween
            import androidx.compose.foundation.clickable
            import androidx.compose.material3.CircularProgressIndicator
            import androidx.compose.material3.Text
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color

            fun Classic() {
                Text("Hello")
                CircularProgressIndicator()
                tween<Float>()
                Color(0xFF000000)
                Modifier.clickable {}
            }
            """,
        ).expectClean()
    }

    @Test
    fun `tests may show sample words and colors`() {
        lint()
            .allowMissingSdk()
            .files(
                // A Gradle project, so that lint knows src/test as the unit tests' sources.
                gradle("apply plugin: 'com.android.application'"),
                *composeStubs.map { it.within("src/main/java") }.toTypedArray(),
                kotlin(
                    "src/test/java/com/ichi2/anki/ui/expressive/screens/SampleTest.kt",
                    """
                    package com.ichi2.anki.ui.expressive.screens

                    import androidx.compose.material3.Text
                    import androidx.compose.ui.graphics.Color

                    fun SampleCard() {
                        Text("le chien")
                        Color(0xFF000000)
                    }
                    """,
                ).indented(),
            ).issues(*ExpressiveDesignDetector.ISSUES.toTypedArray())
            .run()
            .expectClean()
    }

    @Test
    fun `a reasoned suppression is respected`() {
        check(
            """
            package com.ichi2.anki.ui.expressive

            import androidx.compose.animation.core.tween

            // The wave's fill follows the progress at a constant speed: a spring would overshoot it.
            @Suppress("ExpressiveRawTween")
            fun fill() = tween<Float>(1_000)
            """,
        ).expectClean()
    }
}
