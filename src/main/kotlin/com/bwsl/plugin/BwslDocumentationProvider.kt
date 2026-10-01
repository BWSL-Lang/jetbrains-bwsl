package com.bwsl.plugin
import com.bwsl.plugin.references.previousNonWhitespace

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType

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

/** A value the vertex stage writes with `output.<name> = ...`, as the compiler typed it. */
private data class StageValue(val name: String, val type: String, val interpolation: String)

/**
 * The values [pass]'s vertex stage writes for the fragment stage: the pass's stage-interface
 * symbols, in the compiler's order. The type is the compiler's own; the interpolation qualifier is
 * on the first assignment that defines the value.
 */
private fun stageValues(index: BwslAstIndex, pass: AstNodePos): List<StageValue> =
    index.symbolsById.values
        .filter { it.kind == "stage-interface" && it.owner == pass.id }
        .map { symbol ->
            val interpolation = symbol.definitions.firstNotNullOfOrNull { index.nodesById[it]?.interpolation } ?: "DEFAULT"
            StageValue(symbol.name, symbol.type.ifBlank { "?" }, interpolation)
        }

/** The attributes [pass] lists in its `use attributes { ... }`, in that order, as the compiler resolved them. */
private fun usedAttributes(index: BwslAstIndex, pass: AstNodePos): List<AstSymbol> =
    index.nodesById.values
        .filter { it.id.startsWith("${pass.id}/used-attribute:") }
        .sortedBy { it.id.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE }
        .mapNotNull { node ->
            index.refsByFrom[node.id].orEmpty().firstOrNull { it.role == "attribute" }
                ?.let { index.symbolsById[it.to] }
        }

private fun outputListHtml(outputs: List<StageValue>): String {
    if (outputs.isEmpty()) return "(none)"
    return outputs.joinToString("<br/>") { vo ->
        val interp = interpolationLabel(vo.interpolation)?.let { " &nbsp;<i>$it</i>" } ?: ""
        "<code><b>${vo.type}</b> ${vo.name}</code>$interp"
    }
}

/** Tooltip for the `attributes` qualifier, listing the attributes available in the current pass. */
private fun attributesQualifierDoc(element: PsiElement): String? {
    if (element.text != "attributes") return null
    val index = buildAstIndex(element.containingFile) ?: return null
    val pass = index.enclosingNodeOfType("PASS", element.textOffset) ?: return null
    val used = usedAttributes(index, pass)
    val listHtml = if (used.isEmpty()) "(none)" else
        used.joinToString("<br/>") { "<code><b>${it.type}</b> ${it.name}</code>" }
    return renderDoc("attributes", "Pipeline attribute inputs",
        "Per-vertex attributes available in this pass via <code>use attributes { ... }</code>.<br/><br/>$listHtml")
}

/** Tooltip for the `input` or `output` qualifier identifier, explaining its role in the shader pipeline. */
private fun shaderQualifierDoc(element: PsiElement): String? {
    val name = element.text
    if (name != "input" && name != "output") return null
    val index = buildAstIndex(element.containingFile) ?: return null
    val offset = element.textOffset
    val pass = index.enclosingNodeOfType("PASS", offset) ?: return null
    val inFragmentStage = index.enclosingNodeOfType("FRAGMENT_STAGE", offset) != null
    val inVertexStage = index.enclosingNodeOfType("VERTEX_STAGE", offset) != null

    return when (name) {
        "input" if inFragmentStage -> {
            renderDoc("input", "Built-in fragment stage qualifier",
                "Provides access to values written to <code>output.*</code> in the vertex stage, " +
                        "interpolated across the triangle.<br/><br/>" +
                        "Vertex outputs available here:<br/>${outputListHtml(stageValues(index, pass))}")
        }
        "input" -> renderDoc("input", "Built-in stage qualifier",
            "In a vertex stage: provides per-vertex built-in values such as <code>vertex_id</code>, <code>instance_id</code>.<br/>" +
                    "In a compute stage: provides dispatch-grid built-ins such as <code>global_id</code>, <code>local_id</code>.")
        "output" if inVertexStage -> {
            renderDoc("output", "Built-in vertex stage qualifier",
                "Writes per-vertex output attributes passed to the fragment stage as <code>input.*</code>.<br/><br/>" +
                        "Outputs declared in this vertex block:<br/>${outputListHtml(stageValues(index, pass))}")
        }
        else -> renderDoc("output", "Built-in stage qualifier",
            "Writes values to render targets or depth. " +
                    "In a fragment stage: <code>output.color</code>, <code>output.depth</code>, etc.")
    }
}

/**
 * Tooltip for the member in `attributes.<member>`, `input.<member>` or `output.<member>`: whichever
 * declaration the compiler's reference edge for that member points at.
 */
private fun shaderMemberDoc(element: PsiElement): String? {
    val index = buildAstIndex(element.containingFile) ?: return null
    val edge = referenceEdgesAt(index, element.textOffset)
        .firstOrNull { it.role == "attribute" || it.role == "input" || it.role == "output" } ?: return null
    val symbol = index.symbolsById[edge.to] ?: return null
    val member = symbol.name
    val type = symbol.type.ifBlank { "?" }

    return when (symbol.kind) {
        "attribute" -> renderDoc("attributes.$member", "$type $member", "Pipeline vertex attribute")
        "stage-interface" -> {
            val interpolation = symbol.definitions.firstNotNullOfOrNull { index.nodesById[it]?.interpolation }
                ?.let { interpolationLabel(it) }
            val details = buildString {
                append("Vertex output attribute")
                if (interpolation != null) append(", interpolated as <code>$interpolation</code>")
            }
            renderDoc("${edge.role}.$member", "$type $member", details)
        }
        "fragment-output" -> renderDoc("output.$member", "$type $member", "Fragment output (render target)")
        else -> null
    }
}

/**
 * Shows the declared type of the variable, parameter, constant or struct field the caret is on or
 * refers to - at a declaration's own name or at a use of it. The compiler's reference index says
 * which declaration a use belongs to, so same-named variables in different scopes can't be mixed up.
 */
private fun variableTypeDoc(element: PsiElement): String? {
    val index = buildAstIndex(element.containingFile) ?: return null
    val symbol = symbolAt(index, element.textOffset) ?: return null
    val description = when (symbol.kind) {
        "parameter" -> "parameter"
        "variable" -> "local variable"
        "constant" -> "constant"
        "struct-field" -> "field"
        else -> return null
    }
    val type = symbol.type.takeIf { it.isNotBlank() } ?: return null
    return renderDoc(null, "$type ${symbol.name}", description)
}

/** The names of the module/struct/pass that enclose [symbol], outermost first (a pipeline is not part of a function's path). */
private fun qualifiedPathOf(index: BwslAstIndex, symbol: AstSymbol): List<String> {
    val path = ArrayList<String>()
    var owner = symbol.owner
    var depth = 0
    while (owner.isNotEmpty() && depth++ < 8) {
        val ownerSymbol = index.symbolsById[owner] ?: break
        if (ownerSymbol.kind == "module" || ownerSymbol.kind == "struct" || ownerSymbol.kind == "pass") {
            path.add(0, ownerSymbol.name)
        }
        owner = ownerSymbol.owner
    }
    return path
}

/**
 * Documentation for the function or method the caret is on or calls: its qualified name and
 * signature, from the symbol. [at] is the element in the file that has the cached AST - a call, or
 * the declaration itself - not a declaration the call resolved into another file.
 */
private fun functionDoc(at: PsiElement): String? {
    val index = buildAstIndex(at.containingFile) ?: return null
    val symbol = symbolAt(index, at.textOffset) ?: return null
    val signature = functionSignatureOf(index, symbol) ?: return null
    val qualifiedName = (qualifiedPathOf(index, symbol) + symbol.name).joinToString("::") + "()"
    return renderDoc(qualifiedName, signatureHtml(signature.returnType, signature.name, signature.params), null)
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
        // Variable/parameter identifiers resolve to their declaration via BwslAstReference,
        // but the type lookup works identically for the usage and the declaration itself, so
        // skip reference resolution entirely and document the hovered element directly.
        if (element.elementType == BwslTokenTypes.IDENTIFIER) return element
        if (element.elementType == BwslTokenTypes.KW_ATTRIBUTES) return element
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
            // The target of a hover is the declaration a call resolved to, which may be in another
            // file with no cached AST of its own: document it from the hovered element instead.
            BwslTokenTypes.FUNCTION_DECLARATION -> functionDoc(originalElement ?: element)
            // A method-style call (e.g. "values.cos()") is lexed as FUNCTION_CALL rather than
            // INTRINSIC_CALL because it has a receiver, but it may still name an intrinsic.
            BwslTokenTypes.FUNCTION_CALL -> functionDoc(element) ?: intrinsicDoc(element.text, hasReceiver = false)
            BwslTokenTypes.KW_ATTRIBUTES -> attributesQualifierDoc(element)
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
