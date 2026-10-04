package com.bwsl.plugin.completion

import com.bwsl.plugin.*
import com.bwsl.plugin.references.findPreviousNonWhitespace

import com.google.gson.JsonObject
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
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

// Above the default priority (0), so locals sort ahead of keywords, types and intrinsics.
private const val LOCAL_PRIORITY = 100.0

// Names the file declares or imports: after the locals, ahead of keywords, types and intrinsics.
private const val NAME_PRIORITY = 50.0

// Names that need an import first: after everything the file can use as it is.
private const val IMPORTABLE_PRIORITY = 10.0

// Where a name can be completed: an identifier, or a name that is followed by `(` or lexes as a module name.
private val NAME_TOKENS = TokenSet.create(
    BwslTokenTypes.IDENTIFIER, BwslTokenTypes.FUNCTION_CALL, BwslTokenTypes.INTRINSIC_CALL,
    BwslTokenTypes.MODULE_NAME, BwslTokenTypes.MODULE_QUALIFIER
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
private fun classifyCurrentBlockContext(parameters: CompletionParameters): BwslBlockContext {
    val file = parameters.originalFile
    val path = file.virtualFile?.path ?: return BwslBlockContext.STATEMENT_BODY
    val root = BwslAstCache.findRoot(path) ?: return BwslBlockContext.STATEMENT_BODY
    // Use the position just before the inserted dummy identifier, which is where the real token starts.
    val (line, column) = toLineColumn(file, parameters.offset) ?: return BwslBlockContext.STATEMENT_BODY
    return classifyBlockContextAt(root, line, column, file.text)
}

/**
 * The parameters, locals and constants in scope at the completion position, from the cached AST.
 * Empty when no AST is cached (there is no lexical fallback).
 */
private fun collectVisibleLocals(parameters: CompletionParameters): List<VisibleLocal> {
    val ast = findCompletionAst(parameters) ?: return emptyList()
    return collectVisibleLocalsAt(ast.root, ast.raw, ast.line, ast.column)
}

/** The cached AST of the file being completed, and the 1-based position of the caret in it. */
private class CompletionAst(val root: AstRoot, val raw: JsonObject, val line: Int, val column: Int)

/** The cached AST for the completion position, or null when none is cached (there is no lexical fallback). */
private fun findCompletionAst(parameters: CompletionParameters): CompletionAst? {
    val file = parameters.originalFile
    val path = file.virtualFile?.path ?: return null
    val root = BwslAstCache.findRoot(path) ?: return null
    val raw = BwslAstCache.findRawRoot(path) ?: return null
    val (line, column) = toLineColumn(file, parameters.offset) ?: return null
    return CompletionAst(root, raw, line, column)
}

/** Choosing a module's name continues with `::` and offers the module's members straight away. */
private val QUALIFIER_INSERT_HANDLER = InsertHandler<LookupElement> { context, _ ->
    if (!context.document.charsSequence.startsWith("::", context.tailOffset)) {
        EditorModificationUtil.insertStringAtCaret(context.editor, "::")
    } else {
        context.editor.caretModel.moveToOffset(context.tailOffset + 2)
    }
    AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
}

private fun buildLookupElementFor(name: DeclaredName): LookupElement {
    val base = LookupElementBuilder.create(name.name)
    val element = when (name.kind) {
        DeclaredName.Kind.FUNCTION -> base
            .withTypeText(name.type.orEmpty())
            .withTailText("(${name.parameters.orEmpty().joinToString(", ")})", true)
            .withInsertHandler(ParenthesesInsertHandler.getInstance(name.parameters.orEmpty().isNotEmpty()))
        DeclaredName.Kind.MODULE -> base.withTypeText("module").withInsertHandler(QUALIFIER_INSERT_HANDLER)
        DeclaredName.Kind.CONSTANT -> base.withTypeText(name.type.orEmpty()).withTailText(" constant", true)
        DeclaredName.Kind.ENUM_VALUE -> base.withTypeText(name.type.orEmpty()).withTailText(" enum value", true)
        DeclaredName.Kind.STRUCT, DeclaredName.Kind.ENUM -> base.withTypeText(name.kind.label)
    }
    return PrioritizedLookupElement.withPriority(element, NAME_PRIORITY)
}

/**
 * A member of a module the file does not import yet. Choosing it adds `import Module` to the module or
 * pipeline the caret is in and, unless the qualifier is already typed ([qualify] false), writes `Module::` before it.
 */
private fun buildImportingLookupElement(importable: ImportableName, qualify: Boolean): LookupElement {
    val name = importable.name
    val base = LookupElementBuilder.create(name.name)
        .withTypeText(name.type?.takeIf { it.isNotEmpty() } ?: name.kind.label)
        .withTailText((if (name.kind == DeclaredName.Kind.FUNCTION) "(${name.parameters.orEmpty().joinToString(", ")})" else "") +
            " ${importable.module} (import)", true)
        .withInsertHandler(InsertHandler<LookupElement> { context, item ->
            val start = context.startOffset
            if (qualify) context.document.insertString(start, "${importable.module}::")
            if (name.kind == DeclaredName.Kind.FUNCTION) {
                ParenthesesInsertHandler.getInstance(name.parameters.orEmpty().isNotEmpty()).handleInsert(context, item)
            }
            PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
            findImportInsertion(context.file, start, importable.module)?.let { context.document.insertString(it.offset, it.text) }
        })
    return PrioritizedLookupElement.withPriority(base, IMPORTABLE_PRIORITY)
}

/** The modules this file imports (by name, not alias) and the one the caret is in: their names need no import. */
private fun collectModulesNotToImport(parameters: CompletionParameters, ast: CompletionAst?): Set<String> {
    val imported = collectImportDeclarationsOf(parameters.originalFile).map { it.module }
    val enclosing = ast?.let { findEnclosingDeclarationName(it.root, it.raw, it.line, it.column) }
    return (imported + listOfNotNull(enclosing)).toSet()
}

/**
 * The modules `import` can name: the standard modules fetched so far, the module files in the project
 * and the module paths (bwslc finds a module in `<Module>.bwsl`), and the other modules of this file;
 * not the ones this file imports already.
 */
private fun collectImportableModuleNames(parameters: CompletionParameters): List<String> {
    val file = parameters.originalFile
    val ownRoot = file.virtualFile?.path?.let { BwslAstCache.findRoot(it) }
    val alreadyImported = collectImportDeclarationsOf(file).map { it.module }.toSet()
    val fromFiles = collectIndexedFiles(file.project)
        .filter { it.path != file.virtualFile?.path }
        .filter { indexed -> BwslAstCache.findRoot(indexed.path)?.collectOwnPipelines()?.isEmpty() != false }
        .map { it.nameWithoutExtension }
    val inThisFile = ownRoot?.collectOwnModules()?.map { it.name }.orEmpty()
    return (BwslStdlibSources.collectModuleNames() + fromFiles + inThisFile)
        .distinct().filter { it !in alreadyImported }.sorted()
}

class BwslCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withElementType(NAME_TOKENS),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    val prevSibling = parameters.position.parent?.let { findPreviousNonWhitespace(it) }
                    val beforeDot = if (prevSibling?.elementType == BwslTokenTypes.DOT) findPreviousNonWhitespace(prevSibling) else null
                    if (prevSibling?.elementType == BwslTokenTypes.DOT &&
                        (beforeDot?.text == "attributes" || beforeDot?.text == "input")
                    ) {
                        val file = parameters.originalFile
                        val root = file.virtualFile?.path?.let { BwslAstCache.findRoot(it) }
                        val (line, column) = toLineColumn(file, parameters.offset) ?: (0 to 0)
                        val scope = root?.let { findScope(it, line, column) }
                        val pass = scope?.pass
                        val pipeline = scope?.pipeline
                        if (pass != null) {
                            if (beforeDot.text == "attributes" && pipeline != null) {
                                for (attr in collectPassUsedAttributes(pass, pipeline)) {
                                    result.addElement(
                                        LookupElementBuilder.create(attr.name)
                                            .withTypeText(attr.dataType)
                                    )
                                }
                            } else if (beforeDot.text == "input") {
                                val allAttrs = pipeline?.attributes ?: emptyList()
                                for ((_, vo) in collectVertexOutputAssignments(pass, allAttrs)) {
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
                    val prevRef = posParent?.let { findPreviousNonWhitespace(it) }
                    if (prevRef?.firstChild?.elementType == BwslTokenTypes.MODULE_NAME &&
                        findPreviousNonWhitespace(prevRef)?.elementType == BwslTokenTypes.KW_SUBMODULE
                    ) {
                        result.addElement(LookupElementBuilder.create("extends").bold())
                        return
                    }

                    // `Module::` is followed by a member of that module (or a value of an enum), and nothing else.
                    val previousLeaf = PsiTreeUtil.prevCodeLeaf(parameters.position)
                    if (previousLeaf?.elementType == BwslTokenTypes.COLONCOLON) {
                        val qualifier = PsiTreeUtil.prevCodeLeaf(previousLeaf)?.text
                        val ast = findCompletionAst(parameters)
                        if (qualifier != null && ast != null) {
                            val aliases = collectImportDeclarationsOf(parameters.originalFile)
                                .mapNotNull { import -> import.alias?.let { it to import.module } }.toMap()
                            val members = collectMembersOf(ast.root, ast.raw, ast.line, ast.column, qualifier, aliases)
                            for (member in members) result.addElement(buildLookupElementFor(member))
                            // A module the file does not import yet: its members, and choosing one imports it.
                            if (members.isEmpty()) {
                                val excluded = collectModulesNotToImport(parameters, ast)
                                for (importable in collectImportableNames(excluded).filter { it.module == qualifier }) {
                                    result.addElement(buildImportingLookupElement(importable, qualify = false))
                                }
                            }
                        }
                        return
                    }

                    // `import <module>` names a module that can be imported; `using <module>` one that is.
                    if (previousLeaf?.elementType == BwslTokenTypes.KW_IMPORT) {
                        for (name in collectImportableModuleNames(parameters)) {
                            result.addElement(LookupElementBuilder.create(name).withTypeText("module"))
                        }
                        return
                    }
                    if (previousLeaf?.elementType == BwslTokenTypes.KW_USING) {
                        for (import in collectImportDeclarationsOf(parameters.originalFile)) {
                            result.addElement(LookupElementBuilder.create(import.visibleName).withTypeText("module"))
                        }
                        return
                    }

                    val blockContext = classifyCurrentBlockContext(parameters)

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

                    // Names the user declared come first: they are the most likely thing to type in a
                    // function or stage body. Not after `.` or `::`, where a name is a member or a
                    // module-level name, not a local.
                    val afterMemberOrQualifier =
                        prevSibling?.elementType == BwslTokenTypes.DOT || prevSibling?.elementType == BwslTokenTypes.COLONCOLON
                    if (blockContext == BwslBlockContext.STATEMENT_BODY && !afterMemberOrQualifier) {
                        for (local in collectVisibleLocals(parameters)) {
                            result.addElement(
                                PrioritizedLookupElement.withPriority(
                                    LookupElementBuilder.create(local.name)
                                        .withTypeText(local.type ?: "")
                                        .withTailText(" ${local.kind.label}", true),
                                    LOCAL_PRIORITY
                                )
                            )
                        }
                    }

                    // The functions, structs, enums and imported modules the file declares, from the last compile.
                    if (!afterMemberOrQualifier && blockContext != BwslBlockContext.PIPELINE_BODY && blockContext != BwslBlockContext.PASS_BODY) {
                        findCompletionAst(parameters)?.let { ast ->
                            for (name in collectNamesVisibleAt(ast.root, ast.raw, ast.line, ast.column)) {
                                val isExpressionName = name.kind == DeclaredName.Kind.FUNCTION || name.kind == DeclaredName.Kind.CONSTANT
                                if (isExpressionName && blockContext != BwslBlockContext.STATEMENT_BODY) continue
                                result.addElement(buildLookupElementFor(name))
                            }

                            // Names of modules that are not imported yet; choosing one writes `Module::name` and imports the module.
                            val importable = collectImportableNames(collectModulesNotToImport(parameters, ast)).filter { candidate ->
                                val kind = candidate.name.kind
                                !(kind == DeclaredName.Kind.FUNCTION || kind == DeclaredName.Kind.CONSTANT) ||
                                    blockContext == BwslBlockContext.STATEMENT_BODY
                            }
                            if (parameters.invocationCount >= 2) {
                                for (candidate in importable) result.addElement(buildImportingLookupElement(candidate, qualify = true))
                            } else if (importable.isNotEmpty()) {
                                result.addLookupAdvertisement("Press Ctrl+Space again for names from modules that are not imported yet")
                            }
                        }
                    }

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
