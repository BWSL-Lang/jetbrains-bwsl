package com.bwsl.plugin
import com.bwsl.plugin.references.previousNonWhitespace

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType

/** Finds the AstFunction (and its qualified path, e.g. ["LengthMethodTest", "testStruct"]) whose
 *  declaration site matches the given 1-based line/column. */
private fun findFunctionAndPath(root: AstRoot, line: Int, column: Int): Pair<AstFunction, List<String>>? {
    fun matches(fn: AstFunction) = fn.line == line && fn.column == column

    for (module in root.modules) {
        module.functions.firstOrNull(::matches)?.let { return it to listOf(module.name) }
        for (struct in module.structs) {
            struct.methods.firstOrNull(::matches)?.let { return it to listOf(module.name, struct.name) }
        }
    }
    root.root?.let { rootNode ->
        rootNode.functions.firstOrNull(::matches)?.let { return it to emptyList() }
        for (struct in rootNode.structs) {
            struct.methods.firstOrNull(::matches)?.let { return it to listOf(struct.name) }
        }
        for (pass in rootNode.passes) {
            pass.functions.firstOrNull(::matches)?.let { return it to listOf(pass.name) }
        }
    }
    return null
}

private fun signatureHtml(returnType: String, name: String, params: List<String>): String =
    "${returnType.ifBlank { "void" }} ${name}(${params.joinToString(", ")})"

private fun renderDoc(qualifiedName: String?, signature: String, description: String?): String = buildString {
    append(DocumentationMarkup.DEFINITION_START)
    append(signature)
    append(DocumentationMarkup.DEFINITION_END)
    if (qualifiedName != null || description != null) {
        append(DocumentationMarkup.CONTENT_START)
        if (qualifiedName != null) append(qualifiedName)
        if (qualifiedName != null && description != null) append("<br/>")
        if (description != null) append(description)
        append(DocumentationMarkup.CONTENT_END)
    }
}

private fun intrinsicDoc(name: String, hasReceiver: Boolean): String? {
    if (hasReceiver && name == "length") {
        return renderDoc(null, signatureHtml("int", "length", emptyList()), "Number of elements in the array")
    }
    val fn = BwslIntrinsics.ALL.firstOrNull { it.name == name } ?: return null
    val signature = signatureHtml(fn.returnType, fn.name, fn.params.map { "${it.type} ${it.name}" })
    return renderDoc(null, signature, fn.description.takeIf { it.isNotBlank() })
}

/**
 * Formats an interpolation qualifier for display ("DEFAULT" → null, "FLAT" → "@flat", etc.).
 */
private fun interpolationLabel(interp: String): String? = when (interp) {
    "FLAT"           -> "@flat"
    "NO_PERSPECTIVE" -> "@noperspective"
    else             -> null
}

private fun outputListHtml(outputs: Map<String, VertexOutput>): String {
    if (outputs.isEmpty()) return "(none)"
    return outputs.values.joinToString("<br/>") { vo ->
        val type = vo.type ?: "?"
        val interp = interpolationLabel(vo.interpolation)?.let { " &nbsp;<i>$it</i>" } ?: ""
        "<code><b>$type</b> ${vo.member}</code>$interp"
    }
}

/** Tooltip for the `input` or `output` qualifier identifier, explaining its role in the shader pipeline. */
private fun shaderQualifierDoc(element: PsiElement): String? {
    val name = element.text
    if (name != "input" && name != "output") return null
    val file = element.containingFile
    val filePath = file.virtualFile?.path ?: return null
    val root = BwslAstCache.getRoot(filePath) ?: return null
    val (line, column) = lineColumnAt(file, element.textOffset) ?: return null
    val scope = findScope(root, line, column)
    val pass = scope.pass ?: return null
    val attrs = scope.pipeline?.attributes ?: emptyList()

    return when (name) {
        "input" if isInsideFragmentStage(pass, line, column) -> {
            val outputs = vertexOutputAssignments(pass, attrs)
            renderDoc("input", "Built-in fragment stage qualifier",
                "Provides access to values written to <code>output.*</code> in the vertex stage, " +
                        "interpolated across the triangle.<br/><br/>" +
                        "Vertex outputs available here:<br/>${outputListHtml(outputs)}")
        }
        "input" -> renderDoc("input", "Built-in stage qualifier",
            "In a vertex stage: provides per-vertex built-in values such as <code>vertex_id</code>, <code>instance_id</code>.<br/>" +
                    "In a compute stage: provides dispatch-grid built-ins such as <code>global_id</code>, <code>local_id</code>.")
        "output" if isInsideVertexStage(pass, line, column) -> {
            val outputs = vertexOutputAssignments(pass, attrs)
            renderDoc("output", "Built-in vertex stage qualifier",
                "Writes per-vertex output attributes passed to the fragment stage as <code>input.*</code>.<br/><br/>" +
                        "Outputs declared in this vertex block:<br/>${outputListHtml(outputs)}")
        }
        else -> renderDoc("output", "Built-in stage qualifier",
            "Writes values to render targets or depth. " +
                    "In a fragment stage: <code>output.color</code>, <code>output.depth</code>, etc.")
    }
}

private fun isInsideVertexStage(pass: AstPass, line: Int, column: Int): Boolean {
    val vs = pass.vertexShader ?: return false
    return astContains(line, column, vs.line, vs.column, vs.endLine, vs.endColumn)
}

private fun isInsideFragmentStage( pass: AstPass, line: Int, column: Int): Boolean {
    val fs = pass.fragmentShader ?: return false
    return astContains(line, column, fs.line, fs.column, fs.endLine, fs.endColumn)
}

/** Tooltip for a member identifier accessed via `input.<member>` or `output.<member>`. */
private fun shaderMemberDoc(element: PsiElement): String? {
    val file = element.containingFile
    val filePath = file.virtualFile?.path ?: return null
    val root = BwslAstCache.getRoot(filePath) ?: return null
    val (line, column) = lineColumnAt(file, element.textOffset) ?: return null
    val scope = findScope(root, line, column)
    val pass = scope.pass ?: return null
    val attrs = scope.pipeline?.attributes ?: emptyList()

    val memberName = element.text
    val parentRef = element.parent ?: return null
    val prev = previousNonWhitespace(parentRef)
    val beforeDot = if (prev?.elementType == BwslTokenTypes.DOT) previousNonWhitespace(prev) else null
    val qualifier = beforeDot?.text ?: return null

    if (qualifier != "input" && qualifier != "output") return null

    val vo = vertexOutputAssignments(pass, attrs)[memberName] ?: return null
    val typePart = vo.type ?: "?"
    val interpLabel = interpolationLabel(vo.interpolation)
    val details = buildString {
        append("Vertex output attribute")
        if (interpLabel != null) append(", interpolated as <code>$interpLabel</code>")
    }
    return when (qualifier) {
        "input"  -> renderDoc("input.$memberName", "$typePart $memberName", details)
        "output" -> renderDoc("output.$memberName", "$typePart $memberName", details)
        else     -> null
    }
}

/** Shows the declared type of a variable/parameter usage or declaration, via the AST. */
private fun variableTypeDoc(element: PsiElement): String? {
    val file = element.containingFile
    val filePath = file.virtualFile?.path ?: return null
    val root = BwslAstCache.getRoot(filePath) ?: return null
    val (line, column) = lineColumnAt(file, element.textOffset) ?: return null
    val scope = findScope(root, line, column)
    val fn = findEnclosingFunction(root, scope, line, column) ?: return null
    val name = element.text

    fn.parameters.firstOrNull { it.name == name }?.let {
        return renderDoc(null, "${it.type} ${it.name}", "parameter")
    }

    val decl = collectVariableDecls(fn.body)
        .filter { it.name == name && (it.line < line || (it.line == line && it.column <= column)) }
        .maxWithOrNull(compareBy({ it.line }, { it.column }))
    val declaredType = decl?.declaredType?.takeIf { it.isNotBlank() } ?: return null
    return renderDoc(null, "$declaredType $name", "local variable")
}

private fun customFunctionDoc(element: PsiElement): String? {
    val file = element.containingFile
    val filePath = file.virtualFile?.path ?: return null
    val root = BwslAstCache.getRoot(filePath) ?: return null
    val (line, column) = lineColumnAt(file, element.textOffset) ?: return null
    val (fn, path) = findFunctionAndPath(root, line, column) ?: return null

    val signature = signatureHtml(fn.returnType, fn.name, fn.parameters.map { "${it.type} ${it.name}" })
    val qualifiedName = (path + fn.name).joinToString("::") + "()"
    return renderDoc(qualifiedName, signature, null)
}

class BwslDocumentationProvider : AbstractDocumentationProvider() {

    // INTRINSIC_CALL elements have no PsiReference, so the default target-element search finds
    // nothing to generate docs for. Return the element itself so generateDoc gets invoked.
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        val element = contextElement ?: return null
        if (element.elementType == BwslTokenTypes.INTRINSIC_CALL) return element
        if (element.parent?.elementType == BwslTokenTypes.REFERENCE && element.parent?.firstChild?.elementType == BwslTokenTypes.INTRINSIC_CALL) {
            return element.parent
        }
        // Variable/parameter identifiers resolve to their declaration via BwslVariableReference,
        // but the type lookup works identically for the usage and the declaration itself, so
        // skip reference resolution entirely and document the hovered element directly.
        if (element.elementType == BwslTokenTypes.IDENTIFIER) return element
        return null
    }

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val type = element.elementType ?: element.firstChild?.elementType
        return when (type) {
            BwslTokenTypes.INTRINSIC_CALL -> {
                val callElement = if (element.elementType == BwslTokenTypes.INTRINSIC_CALL) element else element.firstChild!!
                val refElement = if (element.elementType == BwslTokenTypes.REFERENCE) element else (element.parent ?: element)
                val outer = if (refElement.parent?.elementType == BwslTokenTypes.CALL_EXPRESSION) refElement.parent!! else refElement
                val hasReceiver = previousNonWhitespace(outer)?.elementType == BwslTokenTypes.DOT
                intrinsicDoc(callElement.text, hasReceiver)
            }
            BwslTokenTypes.FUNCTION_DECLARATION -> customFunctionDoc(element)
            // A method-style call (e.g. "values.cos()") is lexed as FUNCTION_CALL rather than
            // INTRINSIC_CALL because it has a receiver, but it may still name an intrinsic.
            BwslTokenTypes.FUNCTION_CALL -> customFunctionDoc(element) ?: intrinsicDoc(element.text, hasReceiver = false)
            BwslTokenTypes.IDENTIFIER -> {
                // Highest priority: input/output qualifier keywords and their member identifiers.
                val prev = previousNonWhitespace(element.parent ?: element)
                if (prev?.elementType == BwslTokenTypes.DOT)
                    shaderMemberDoc(element)?.let { return it }
                if (element.text == "input" || element.text == "output")
                    shaderQualifierDoc(element)?.let { return it }
                variableTypeDoc(element)
            }
            else -> null
        }
    }
}
