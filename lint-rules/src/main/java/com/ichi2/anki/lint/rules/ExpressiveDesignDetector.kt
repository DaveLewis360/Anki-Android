// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors

package com.ichi2.anki.lint.rules

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.google.common.annotations.VisibleForTesting
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.USwitchClauseExpressionWithBody
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UYieldExpression

/**
 * Keeps the Material 3 Expressive client, the `com.ichi2.anki.ui.expressive` package, on its
 * design system: colors from the theme's roles, motion from its motion scheme, the Expressive
 * loading indicator, translatable text, and controls that show they are pressed or focused and
 * tell TalkBack what they are.
 *
 * Code elsewhere, Classic's included, is not checked.
 */
class ExpressiveDesignDetector :
    Detector(),
    SourceCodeScanner {
    companion object {
        /** The Material 3 Expressive client: only its files are checked. */
        @VisibleForTesting
        const val EXPRESSIVE_PACKAGE = "com.ichi2.anki.ui.expressive"

        /** The theme: its color literals are the palettes the roles come from. */
        @VisibleForTesting
        const val THEME_PACKAGE = "$EXPRESSIVE_PACKAGE.theme"

        private const val MATERIAL3 = "androidx.compose.material3"
        private const val FOUNDATION = "androidx.compose.foundation"
        private const val SELECTION = "androidx.compose.foundation.selection"
        private const val ANIMATION_CORE = "androidx.compose.animation.core"
        private const val UI_GRAPHICS = "androidx.compose.ui.graphics"

        private val CATEGORY: Category = Category.create(Category.USABILITY, "Material3Expressive", 10)
        private const val PRIORITY = 6
        private val implementation = Implementation(ExpressiveDesignDetector::class.java, Scope.JAVA_FILE_SCOPE)

        private fun issue(
            id: String,
            description: String,
            explanation: String,
        ): Issue = Issue.create(id, description, explanation, CATEGORY, PRIORITY, Severity.ERROR, implementation)

        @VisibleForTesting
        const val INDICATION_NULL_MESSAGE =
            "A control without an indication shows neither press nor focus: keep the default indication, or use a Material component"
        val INDICATION_NULL: Issue =
            issue(
                "ExpressiveIndicationNull",
                "Control without an indication",
                "`indication = null` removes the ripple and the focus highlight, so the control looks the same pressed, " +
                    "focused or not. Material 3 Expressive components show both; keep the default indication.",
            )

        @VisibleForTesting
        const val COLOR_LITERAL_MESSAGE =
            "Colors come from the theme: use a `MaterialTheme.colorScheme` role, or add the color to the theme"
        val COLOR_LITERAL: Issue =
            issue(
                "ExpressiveColorLiteral",
                "Color literal outside the theme",
                "A color written into a screen ignores the palette, dark mode, dynamic color, and contrast. " +
                    "Use a color role of `MaterialTheme.colorScheme`; colors of the app's own, such as the answer colors, " +
                    "belong to the theme package ($THEME_PACKAGE).",
            )

        @VisibleForTesting
        const val HARDCODED_TEXT_MESSAGE =
            "Text shown to the learner must be translatable: use `stringResource`, or Anki's own translations through `TR`"
        val HARDCODED_TEXT: Issue =
            issue(
                "ExpressiveHardcodedText",
                "Text that cannot be translated",
                "A string literal shown by `Text`, told by TalkBack as a content description or click label, or shown in " +
                    "a snackbar, is in English in every language. Use a string resource, or the backend's `TR` strings where " +
                    "Anki already translates the text. Literals without letters, such as a separator, are allowed. Strings " +
                    "built outside composables, as in view models, are not checked.",
            )

        @VisibleForTesting
        const val CIRCULAR_PROGRESS_MESSAGE =
            "Material 3 Expressive waits with `LoadingIndicator` or `ContainedLoadingIndicator`, " +
                "and shows determinate progress with `CircularWavyProgressIndicator`"
        val CIRCULAR_PROGRESS: Issue =
            issue(
                "ExpressiveCircularProgress",
                "Circular progress indicator in the Expressive client",
                "The Expressive client waits with the shape-morphing `LoadingIndicator`, and shows determinate progress " +
                    "with the wavy progress indicators.",
            )

        @VisibleForTesting
        const val RAW_TWEEN_MESSAGE =
            "Motion comes from `MaterialTheme.motionScheme`, whose springs follow the theme and the system's animation scale"
        val RAW_TWEEN: Issue =
            issue(
                "ExpressiveRawTween",
                "Tween instead of the motion scheme",
                "Component motion uses the springs of `MaterialTheme.motionScheme` (spatial for movement, effects for " +
                    "color and opacity), so that all of the app moves alike. Suppress with a reason where a fixed duration " +
                    "is the point.",
            )

        @VisibleForTesting
        const val CLICKABLE_WITHOUT_ROLE_MESSAGE =
            "Tell TalkBack what the element is with `role`, or use a Material component that sets one"
        val CLICKABLE_WITHOUT_ROLE: Issue =
            issue(
                "ExpressiveClickableWithoutRole",
                "Clickable element without a role",
                "Without a role, TalkBack reads a clickable element's text but not what it is, a button, a tab, or a " +
                    "checkbox. Give `clickable` a `role`, or use the Material component for the control.",
            )

        val ISSUES: List<Issue> =
            listOf(INDICATION_NULL, COLOR_LITERAL, HARDCODED_TEXT, CIRCULAR_PROGRESS, RAW_TWEEN, CLICKABLE_WITHOUT_ROLE)
    }

    /**
     * The calls checked, by the name they are called with. Compose functions that take value
     * classes, such as `Color` or `Role`, have mangled JVM names (`clickable-XHw0xAI`), which
     * [getApplicableMethodNames] would not match: every call is visited instead.
     */
    private val checkedCalls =
        setOf(
            "Text",
            "Icon",
            "Image",
            "showSnackbar",
            "CircularProgressIndicator",
            "tween",
            "Color",
            "clickable",
            "combinedClickable",
            "selectable",
            "toggleable",
            "triStateToggleable",
            "indication",
        )

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        // Tests show sample cards and fakes: their words and colors are not the app's.
        if (context.isTestSource) return null
        val filePackage = context.uastFile?.packageName ?: return null
        if (!filePackage.isIn(EXPRESSIVE_PACKAGE)) return null
        return object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                val name = node.methodName ?: return
                if (name !in checkedCalls) return
                val method = node.resolve() ?: return
                check(context, filePackage, name, node, method)
            }
        }
    }

    private fun check(
        context: JavaContext,
        filePackage: String,
        name: String,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        val methodPackage = method.containingClass?.qualifiedName?.substringBeforeLast('.') ?: return
        when (name) {
            "Text" -> if (methodPackage == MATERIAL3) checkText(context, node, method, "text")
            "Icon" -> if (methodPackage == MATERIAL3) checkText(context, node, method, "contentDescription")
            "Image" -> if (methodPackage == FOUNDATION) checkText(context, node, method, "contentDescription")
            "showSnackbar" ->
                if (methodPackage == MATERIAL3) {
                    checkText(context, node, method, "message")
                    checkText(context, node, method, "actionLabel")
                }
            "CircularProgressIndicator" ->
                if (methodPackage == MATERIAL3) report(context, CIRCULAR_PROGRESS, node, CIRCULAR_PROGRESS_MESSAGE)
            "tween" -> if (methodPackage == ANIMATION_CORE) report(context, RAW_TWEEN, node, RAW_TWEEN_MESSAGE)
            "Color" -> if (methodPackage == UI_GRAPHICS && !filePackage.isIn(THEME_PACKAGE)) checkColor(context, node)
            "clickable", "combinedClickable" ->
                if (methodPackage == FOUNDATION) {
                    checkIndication(context, node, method)
                    checkRole(context, node, method)
                    checkText(context, node, method, "onClickLabel")
                    checkText(context, node, method, "onLongClickLabel")
                }
            "indication" -> if (methodPackage == FOUNDATION) checkIndication(context, node, method)
            "selectable", "toggleable", "triStateToggleable" ->
                if (methodPackage == SELECTION) checkIndication(context, node, method)
        }
    }

    /** Words in the argument given for [parameter], a text the learner reads or hears. */
    private fun checkText(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
        parameter: String,
    ) {
        val text = argument(context, node, method, parameter) ?: return
        if (text.hasLiteralWords()) report(context, HARDCODED_TEXT, text, HARDCODED_TEXT_MESSAGE)
    }

    /** A color made of literal values: any `Color(...)` whose arguments are all literals. */
    private fun checkColor(
        context: JavaContext,
        node: UCallExpression,
    ) {
        val arguments = node.valueArguments
        if (arguments.isNotEmpty() && arguments.all { it.unwrapped() is ULiteralExpression }) {
            report(context, COLOR_LITERAL, node, COLOR_LITERAL_MESSAGE)
        }
    }

    private fun checkIndication(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        val indication = argument(context, node, method, "indication")?.unwrapped() ?: return
        if (indication is ULiteralExpression && indication.isNull) {
            report(context, INDICATION_NULL, indication, INDICATION_NULL_MESSAGE)
        }
    }

    private fun checkRole(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        val role = argument(context, node, method, "role")?.unwrapped()
        if (role == null || (role is ULiteralExpression && role.isNull)) {
            report(context, CLICKABLE_WITHOUT_ROLE, node, CLICKABLE_WITHOUT_ROLE_MESSAGE)
        }
    }

    /**
     * The argument given for [parameter], by name or by position; null if the default applies.
     *
     * Kotlin's elvis operator is a temporary and an `if` on it, and the mapping gives the
     * temporary's declaration: the whole operator is returned instead.
     */
    private fun argument(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
        parameter: String,
    ): UExpression? {
        val mapped =
            context.evaluator
                .computeArgumentMapping(node, method)
                .entries
                .firstOrNull { (_, value) -> value.name == parameter }
                ?.key ?: return null
        return if (mapped is UDeclarationsExpression) (mapped.uastParent as? UExpressionList) ?: mapped else mapped
    }

    private fun report(
        context: JavaContext,
        issue: Issue,
        element: UExpression,
        message: String,
    ) {
        context.report(issue, element, context.getLocation(element), message)
    }
}

private fun String.isIn(packageName: String) = this == packageName || startsWith("$packageName.")

private fun UExpression.unwrapped(): UExpression {
    var expression = this
    while (expression is UParenthesizedExpression) expression = expression.expression
    return expression
}

/**
 * Whether this is a string literal, or a template or concatenation, with letters of its own, or
 * picks one with `if`, `when` or `?:`.
 */
private fun UExpression.hasLiteralWords(): Boolean =
    when (val expression = unwrapped()) {
        is ULiteralExpression -> (expression.value as? String)?.any(Char::isLetter) == true
        is UPolyadicExpression -> expression.operands.any { it.hasLiteralWords() }
        is UIfExpression -> listOfNotNull(expression.thenExpression, expression.elseExpression).any { it.hasLiteralWords() }
        // A branch's value is its block's last expression; in a `when`, that expression is yielded.
        is UBlockExpression -> expression.expressions.lastOrNull()?.hasLiteralWords() == true
        is USwitchExpression ->
            expression.body.expressions.any { clause ->
                val value = (clause as? USwitchClauseExpressionWithBody)?.body?.expressions?.lastOrNull()
                value?.hasLiteralWords() == true
            }
        is UYieldExpression -> expression.expression?.hasLiteralWords() == true
        // Kotlin's elvis operator: a temporary, then an `if` on it.
        is UExpressionList -> expression.expressions.any { it is UIfExpression && it.hasLiteralWords() }
        else -> false
    }
