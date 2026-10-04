package com.bwsl.plugin

import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel

private val SAMPLE = """
    module Lighting {
        const float PI = 3.14159;

        attenuate :: (float distance, float radius) -> float {
            float t = saturate(1.0 - distance / radius);
            if (t <= 0.0)
                return 0.0;
            return t * t;
        }

        shade :: (float3 n, float3 l, float3 albedo) -> float3 {
            float d = max(dot(n, l), 0.0);
            float3 lit = albedo * d +
                float3(0.02);
            return lit;
        }
    }
""".trimIndent()

/** How BWSL's formatter places a multi-line block's `{`. */
object BraceStyle {
    /** Leave each `{` where it is written. */
    const val KEEP_AS_WRITTEN = 0

    /** `{` ends the line of its header, and `} else {` stays on one line. */
    const val END_OF_LINE = 1

    /** `{` is on a line of its own, and `else` starts a line. */
    const val NEXT_LINE = 2
}

/**
 * What the BWSL formatter offers beyond indentation and spacing. All of it is off by default, so
 * formatting does not move a token to another line unless asked to. Wrapping long lines uses the
 * standard right margin and **Wrap on typing**-style option of the code style.
 */
class BwslCodeStyleSettings(container: CodeStyleSettings) : CustomCodeStyleSettings("BwslCodeStyleSettings", container) {

    /** One of [BraceStyle]. Only blocks that span lines are moved; `{ x }` stays as it is. */
    @JvmField
    var BRACE_STYLE: Int = BraceStyle.KEEP_AS_WRITTEN

    /**
     * Arguments and parameters that run past the right margin go one per line. This is not the common
     * "wrap long lines" flag: that one also makes the platform cut a line wherever it passes the margin,
     * through a token if that is where it falls.
     */
    @JvmField
    var WRAP_CALL_ARGUMENTS: Boolean = false

    /** A line that continues an expression lines up with where the expression began, instead of being indented. */
    @JvmField
    var ALIGN_CONTINUED_EXPRESSIONS: Boolean = false
}

/** Indentation defaults for BWSL (four spaces, and four for a continued line), and what the code style page offers. */
class BwslLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = BwslLanguage

    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        when (settingsType) {
            SettingsType.INDENT_SETTINGS ->
                consumer.showStandardOptions("INDENT_SIZE", "CONTINUATION_INDENT_SIZE", "TAB_SIZE", "USE_TAB_CHARACTER")
            SettingsType.BLANK_LINES_SETTINGS -> consumer.showStandardOptions("KEEP_BLANK_LINES_IN_CODE")
            SettingsType.WRAPPING_AND_BRACES_SETTINGS -> {
                consumer.showStandardOptions("RIGHT_MARGIN")
                consumer.showCustomOption(BwslCodeStyleSettings::class.java, "WRAP_CALL_ARGUMENTS", "Wrap call arguments that pass the right margin", "Wrapping")
                consumer.showCustomOption(
                    BwslCodeStyleSettings::class.java, "BRACE_STYLE", "Brace placement", "Braces",
                    arrayOf("Keep as written", "End of line", "Next line"),
                    intArrayOf(BraceStyle.KEEP_AS_WRITTEN, BraceStyle.END_OF_LINE, BraceStyle.NEXT_LINE)
                )
                consumer.showCustomOption(
                    BwslCodeStyleSettings::class.java, "ALIGN_CONTINUED_EXPRESSIONS", "Align continued expressions", "Wrapping"
                )
            }
            else -> Unit
        }
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()
}

/** The **Code Style → BWSL** page. */
class BwslCodeStyleSettingsProvider : CodeStyleSettingsProvider() {

    override fun getConfigurableDisplayName(): String = "BWSL"

    override fun createCustomSettings(settings: CodeStyleSettings): CustomCodeStyleSettings = BwslCodeStyleSettings(settings)

    override fun createConfigurable(settings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(settings, modelSettings, "BWSL") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(BwslLanguage, currentSettings, settings) {}
        }
}

/** Typing a `}` at the start of a line moves it to the indent its block's opening line has. */
class BwslTypedHandler : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '}' || file.language != BwslLanguage) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset - 1
        if (offset < 0 || document.charsSequence[offset] != '}') return Result.CONTINUE
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        if (document.getText(TextRange(lineStart, offset)).isNotBlank()) return Result.CONTINUE

        PsiDocumentManager.getInstance(project).commitDocument(document)
        CodeStyleManager.getInstance(project).adjustLineIndent(file, offset)
        return Result.STOP
    }
}
