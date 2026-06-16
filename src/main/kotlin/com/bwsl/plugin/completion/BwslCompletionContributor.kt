package com.bwsl.plugin.completion

import com.bwsl.plugin.*
import com.bwsl.plugin.references.previousNonWhitespace

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext

// Keywords whose validity doesn't depend on the surrounding block structure.
private val UNRESTRICTED_KEYWORDS = listOf(
    "compute_graph", "struct", "enum", "eval", "node", "inputs", "outputs",
    "import", "using", "as", "use", "const", "shared", "constraint", "rules", "require", "conflict",
    "null", "true", "false", "self",
    "readonly", "readwrite", "writeonly",
    "vertex_function", "fragment_function", "compute_function", "pass_block"
)

// Statement-level control-flow keywords — only valid where statements are allowed (function/stage bodies).
private val STATEMENT_KEYWORDS = listOf(
    "return", "if", "else", "for", "foreach", "while", "loop", "switch", "case", "default",
    "break", "skip", "continue", "discard", "in", "by", "until"
)

// "module"/"pipeline" declarations can only appear at the top level of a file.
private val TOP_LEVEL_KEYWORDS = listOf("module", "submodule", "pipeline")

// "attributes"/"resources"/"variants"/"pass" can only appear directly inside a pipeline body.
private val PIPELINE_BODY_KEYWORDS = listOf("attributes", "resources", "variants", "pass")

// "vertex"/"fragment"/"compute" shader-stage blocks can only appear directly inside a pass body.
private val PASS_BODY_KEYWORDS = listOf("vertex", "fragment", "compute")

private val TYPE_KEYWORDS = listOf(
    "bool",
    "int", "int2", "int3", "int4", "uint", "uint2", "uint3", "uint4",
    "int64", "int64x2", "int64x3", "int64x4", "uint64", "uint64x2", "uint64x3", "uint64x4",
    "float", "float2", "float3", "float4", "double", "double2", "double3", "double4",
    "mat2", "mat3", "mat4", "dmat2", "dmat3", "dmat4",
    "sampler", "texture2D", "texture3D", "texture2DArray", "textureCube", "image2D",
    "buffer", "cbuffer", "void"
)

private val INTRINSIC_NAMES = listOf(
    "abs", "acos", "all", "any", "asin", "atan", "ceil", "clamp", "cos", "cross",
    "degrees", "distance", "dot", "exp", "exp2", "floor", "fmod", "frac",
    "inversesqrt", "length", "lerp", "log", "log2", "max", "min", "mix", "mod",
    "normalize", "pow", "radians", "reflect", "refract", "round", "saturate",
    "sign", "sin", "smoothstep", "sqrt", "step", "tan", "trunc"
)

/**
 * Determines the [BwslBlockContext] surrounding the completion position via the cached bwslc AST.
 * Falls back to [BwslBlockContext.STATEMENT_BODY] (i.e. no restrictions) when no AST is cached,
 * since the lexical fallback has no notion of block structure.
 */
private fun currentBlockContext(parameters: CompletionParameters): BwslBlockContext {
    val file = parameters.originalFile
    val path = file.virtualFile?.path ?: return BwslBlockContext.STATEMENT_BODY
    val root = BwslAstCache.getRoot(path) ?: return BwslBlockContext.STATEMENT_BODY
    // Use the position just before the inserted dummy identifier, which is where the real token starts.
    val (line, column) = lineColumnAt(file, parameters.offset) ?: return BwslBlockContext.STATEMENT_BODY
    return blockContextAt(root, line, column, file.text)
}

class BwslCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withElementType(BwslTokenTypes.IDENTIFIER),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    val prevSibling = parameters.position.parent?.let { previousNonWhitespace(it) }
                    val beforeDot = if (prevSibling?.elementType == BwslTokenTypes.DOT) previousNonWhitespace(prevSibling) else null
                    if (prevSibling?.elementType == BwslTokenTypes.DOT &&
                        (beforeDot?.text == "attributes" || beforeDot?.text == "input")
                    ) {
                        val file = parameters.originalFile
                        val root = file.virtualFile?.path?.let { BwslAstCache.getRoot(it) }
                        val (line, column) = lineColumnAt(file, parameters.offset) ?: (0 to 0)
                        val scope = root?.let { findScope(it, line, column) }
                        val pass = scope?.pass
                        val pipeline = scope?.pipeline
                        if (pass != null) {
                            if (beforeDot.text == "attributes" && pipeline != null) {
                                for (attr in passUsedAttributes(pass, pipeline)) {
                                    result.addElement(
                                        LookupElementBuilder.create(attr.name)
                                            .withTypeText(attr.dataType)
                                    )
                                }
                            } else if (beforeDot.text == "input") {
                                val allAttrs = pipeline?.attributes ?: emptyList()
                                for ((_, vo) in vertexOutputAssignments(pass, allAttrs)) {
                                    result.addElement(
                                        LookupElementBuilder.create(vo.member)
                                            .withTypeText(vo.type ?: "output")
                                    )
                                }
                            }
                            return
                        }
                    }

                    // `extends` is only valid directly after a submodule's name:
                    // `submodule <MODULE_NAME> <caret>` → offer only "extends".
                    // MODULE_NAME is wrapped in a REFERENCE composite by the parser, so check firstChild.
                    val posParent = parameters.position.parent
                    val prevRef = posParent?.let { previousNonWhitespace(it) }
                    if (prevRef?.firstChild?.elementType == BwslTokenTypes.MODULE_NAME &&
                        previousNonWhitespace(prevRef)?.elementType == BwslTokenTypes.KW_SUBMODULE
                    ) {
                        result.addElement(LookupElementBuilder.create("extends").bold())
                        return
                    }

                    val blockContext = currentBlockContext(parameters)

                    if (blockContext == BwslBlockContext.TOP_LEVEL) {
                        for (kw in TOP_LEVEL_KEYWORDS) {
                            result.addElement(LookupElementBuilder.create(kw).bold())
                        }
                        return
                    }

                    if (blockContext == BwslBlockContext.ATTRIBUTES_BODY ||
                        blockContext == BwslBlockContext.RESOURCES_BODY ||
                        blockContext == BwslBlockContext.VARIANTS_BODY
                    ) {
                        for (type in TYPE_KEYWORDS) {
                            result.addElement(
                                LookupElementBuilder.create(type)
                                    .withTypeText("type")
                            )
                        }
                        return
                    }

                    val keywords = UNRESTRICTED_KEYWORDS +
                        (if (blockContext == BwslBlockContext.STATEMENT_BODY) STATEMENT_KEYWORDS else emptyList()) +
                        (if (blockContext == BwslBlockContext.PIPELINE_BODY) PIPELINE_BODY_KEYWORDS else emptyList()) +
                        (if (blockContext == BwslBlockContext.PASS_BODY) PASS_BODY_KEYWORDS else emptyList())

                    for (kw in keywords) {
                        result.addElement(LookupElementBuilder.create(kw).bold())
                    }
                    if (blockContext != BwslBlockContext.PIPELINE_BODY &&
                        blockContext != BwslBlockContext.PASS_BODY
                    ) {
                        for (type in TYPE_KEYWORDS) {
                            result.addElement(
                                LookupElementBuilder.create(type)
                                    .withTypeText("type")
                            )
                        }
                    }
                    if (blockContext == BwslBlockContext.STATEMENT_BODY) {
                        for (name in INTRINSIC_NAMES) {
                            result.addElement(
                                LookupElementBuilder.create(name)
                                    .withTailText("(...)", true)
                                    .withTypeText("intrinsic")
                            )
                        }
                    }
                }
            }
        )
    }
}
