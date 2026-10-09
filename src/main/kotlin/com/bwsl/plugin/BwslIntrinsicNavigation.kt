package com.bwsl.plugin

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.ide.BrowserUtil
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/** How a link is opened; a test replaces it, as it must not start a browser. */
object BwslBrowser {
    var open: (String) -> Unit = { url -> BrowserUtil.browse(url) }
}

/**
 * What Ctrl+click on an intrinsic or a keyword goes to: its page in the official documentation. Neither has
 * a source to open, so the target is a stand-in element that opens [url] in the browser when navigated to.
 */
class BwslDocumentationTarget(private val file: PsiElement, val subject: String, val url: String) : FakePsiElement() {
    override fun getParent(): PsiElement = file
    override fun getName(): String = subject
    override fun getPresentableText(): String = subject
    override fun getLocationString(): String = "BWSL documentation"
    override fun getIcon(open: Boolean): Icon? = null
    override fun getPresentation(): ItemPresentation = this
    override fun navigate(requestFocus: Boolean) = BwslBrowser.open(url)
    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true

    override fun equals(other: Any?): Boolean = other is BwslDocumentationTarget && other.url == url
    override fun hashCode(): Int = url.hashCode()
}

/**
 * The documentation page to go to from the token [name], or null: a call of an intrinsic that has a page (not
 * `fmod` or the barriers, which have none) or the `discard` keyword, and not the `length()` of an array,
 * which is not the vector `length` intrinsic; or one of the keywords that have a page (see [findKeywordPageFor]).
 */
internal fun findDocumentationTargetFor(name: PsiElement): BwslDocumentationTarget? {
    val type = name.node?.elementType
    findKeywordPageFor(name)?.let { return BwslDocumentationTarget(name.containingFile, name.text, "$DOCS_BASE_URL/$it") }
    if (type != BwslTokenTypes.INTRINSIC_CALL && type != BwslTokenTypes.KW_DISCARD) return null
    // The token is wrapped (reference, call), so the `.` before it is a sibling of one of its ancestors.
    val before = generateSequence(name) { it.parent }.takeWhile { it !is PsiFile }.firstNotNullOfOrNull { it.prevSibling }
    val isMethodOfValue = generateSequence(before) { it.prevSibling }
        .firstOrNull { it.node?.elementType != TokenType.WHITE_SPACE }
        ?.node?.elementType == BwslTokenTypes.DOT
    if (isMethodOfValue && name.text == "length") return null
    if (!BwslIntrinsicDocs.isDocumented(name.text)) return null
    return BwslDocumentationTarget(name.containingFile, name.text, BwslIntrinsicDocs.buildPageUrl(name.text))
}

/** Ctrl+click (and Ctrl+hover) on an intrinsic or a keyword: go to its documentation. */
class BwslIntrinsicGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (element.language != BwslLanguage) return null
        return findDocumentationTargetFor(element)?.let { arrayOf<PsiElement>(it) }
    }

    override fun getActionText(context: DataContext): String? = null
}
