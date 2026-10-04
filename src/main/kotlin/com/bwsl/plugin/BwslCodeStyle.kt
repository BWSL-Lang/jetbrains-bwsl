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
            else -> Unit
        }
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()
}

/** The **Code Style → BWSL** page. */
class BwslCodeStyleSettingsProvider : CodeStyleSettingsProvider() {

    override fun getConfigurableDisplayName(): String = "BWSL"

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
