# jetbrains-bwsl

JetBrains IDE plugin (Kotlin, IntelliJ Platform SDK) for the BWSL shader language.

## Ecosystem

This plugin is one of three repositories, all under the BWSL-Lang GitHub organisation:

- **Compiler** (`bwslc`): https://github.com/BWSL-Lang/BWSL. Local clone at `C:\Users\lundis\BWSL\BWSL`, built to
  `build\bwslc.exe`. The language itself, the intrinsic table (`src/core/bwsl_stdlib.h`), the standard
  modules (`modules/`), the AST schema (`docs/ast-json.md`) and the compiler's own tests live there. Compiler bugs and gaps found from the
  plugin are filed as issues on it (written standalone, with complete examples, without reference to
  this repo's implementation).
- **Documentation** (https://www.bwsl.dev/docs): source at https://github.com/BWSL-Lang/bwsl-docs, no local
  clone. The plugin links to its pages and reads intrinsic summaries from them (see "Intrinsic
  documentation" below).
- **This plugin**: https://github.com/BWSL-Lang/jetbrains-bwsl.

Work on any of them is possible when it is needed, but **always check with the user first** before changing
or filing anything in the compiler or docs repository (a branch, a commit, a push, an issue or a pull
request); the plugin repository is where work happens by default.

## Build / test

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME","User")
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
./gradlew.bat test --no-configuration-cache --rerun-tasks
```
JAVA_HOME (`C:\Users\lundis\.jdks\corretto-21.0.9`) is set persistently at User level but is **not**
inherited by fresh shell sessions started by tools — always export it first.

**`--rerun-tasks` is required, not optional.** Gradle's up-to-date checks are unreliable in this
project (most likely the IntelliJ Platform Gradle Plugin's sandbox-install step not correctly
tracking its inputs) - without `--rerun-tasks`, `./gradlew test` frequently reports `BUILD
SUCCESSFUL` in ~1s while having silently skipped re-running anything, including after real source
changes. A `NoSuchMethodError` in the sandboxed test IDE (stale plugin classes from an old build)
looks like a stale-cache/stale-daemon problem but is just tasks not re-executing. Deleting
`build`/`.gradle` does **not** fix this - `--rerun-tasks` does. Always pass it when a test result
needs to be trusted, e.g. after any code change.

## Conventions

Follow `docs/conventions/coding-conventions.md` when naming or writing code. In particular, every
function is named as a verb phrase (`find…`, `collect…`, `build…`, `is…`/`has…`/`can…` for
predicates, …); that file says which verb to use for which kind of function.

When editing `README.md`, follow `docs/conventions/doc-conventions.md`: prose
is wrapped at 80 characters.

## Architecture overview

- `BwslLexerAdapter.kt` — flex-generated lexer adapter. Tracks `prevSignificantType` to detect a
  `.` (DOT) receiver before `identifier(`, distinguishing `INTRINSIC_CALL` vs `FUNCTION_CALL`
  (e.g. `values.length()` stays `INTRINSIC_CALL`, `values.cos()` becomes `FUNCTION_CALL`).
  The names that count as intrinsics are `BwslIntrinsics.NAMES`, the same table the hover, parameter info
  and name completion use (the lexer and the completion contributor each had their own, stale, shorter
  list: no `fma`, but `frac` and `inversesqrt`, which BWSL does not have).
- `BwslParserDefinition.kt` — defines `BwslReferenceElement` (an `ASTWrapperPsiElement` override)
  for the `REFERENCE` composite element type. **Critical**: plain `ASTWrapperPsiElement.getReferences()`
  does NOT delegate to `ReferenceProvidersRegistry` by default — `BwslReferenceElement` overrides
  it to call `ReferenceProvidersRegistry.getReferencesFromProviders(this)`. Without this override,
  ctrl+click navigation silently does nothing.
- `BwslReferenceContributor.kt` — registers reference providers on `BwslTokenTypes.REFERENCE`.
  Everything dispatches to `BwslAstReference` (generic, index-driven — see below), except
  intrinsic calls and a module's own declaration name, which have no reference. There are no
  other reference classes: `import`/`using`, `Mod::` qualifiers (calls and declared types),
  `use attributes { ... }` names, struct fields and fragment outputs are all positioned nodes with
  an edge in the reference index, so they need no special handling.
- `BwslAstAnnotator.kt` — an `ExternalAnnotator` that compiles the edited file with
  `compileAndCache` (`BwslAstCompiler.kt`), which runs `bwslc <file> -ast-json -modules <paths...>`,
  parses the JSON (handles UTF-16 BOM output) into both the typed `AstRoot` and a raw `JsonObject`
  via Gson, and stores both in `BwslAstCache` together with the files the AST was built from. It shows
  nothing itself (the compiler's own diagnostics, including its notes about shadowed declarations, come
  from `BwslExternalAnnotator`; the plugin used to detect shadowing itself, until the compiler did).
  **Unsaved text** (`AstCollectedInfo.hasUnsavedChanges`: the document is modified or its hash differs
  from the snapshot) is compiled from stdin instead: `compileEditorText` runs
  `bwslc --stdin --source-file <path> -ast-json`, where `--source-file` is what finds the modules beside
  the file, so nothing is written to disk and the AST's `sourceFile` is the real path. The result goes to
  `BwslAstCache.updateLive`, a second slot per file read only through `findRootForCompletion`/
  `findRawRootForCompletion` (completion); the saved slot, its recorded inputs and so the project
  index, Find Usages and Rename never see it. A text that does not parse gives no AST and the previous
  live one stays; a compile of the saved text clears it. Because the live AST is of exactly the editor's
  text.
  Diagnostics (`BwslExternalAnnotator`) use the same `--stdin --source-file` for a file on disk;
  a copy in the system temp directory (the old way, still used for text with no file) cannot find the
  modules beside the file.
- The Compile action's command is `buildCompileCommand` (`BwslCompileAction.kt`): output format flag,
  `-debug-names` when the `emitDebugNames` setting is on, then each `-modules`. The flag only changes what
  is *written*, so the AST, diagnostics and rename-conflict runs do not pass it.
- **Project index** (`BwslProjectIndex.kt`, `BwslProjectConfig.kt`, `BwslAstCompiler.kt`). The
  features that search across files (Find Usages, Rename) need an AST for every BWSL file, not only
  the ones opened. `-ast-json` takes exactly one input file (batch and directory inputs are refused),
  so each file is its own compile. `collectIndexedFiles` is the set: the project's BWSL files plus the
  files *directly inside* each module path (bwslc looks for `<Module>.bwsl` beside the compiled file
  and in each `-modules` directory, not in their subdirectories), minus `bwsl.json`'s `exclude`.
  The `BwslProjectIndex` project service compiles the stale ones (up to four at a time) at startup
  (`BwslProjectIndexStartup`) and, debounced, whenever `BwslFileChangeListener` sees a `.bwsl` file or
  `bwsl.json` change. Only files on the local disk are compiled (bwslc needs a real path).
  Freshness is per input: `BwslAstCache` records, for each AST, `normalizePathKey -> hashText` of the
  saved text of the file and of every file the payload names by `sourceFile` (its imports), taken
  *before* the compile from `snapshotCandidateInputs` (the file, its siblings, the module-path files)
  so a save during the compile cannot be mistaken for what bwslc read. `hasCurrentAst` compares
  those with the files now; an edit to a module therefore also stales its importers. A file bwslc
  produces no AST for is recorded with `recordUncompilable` against the whole candidate snapshot and
  is not retried until something it could read changes; a compile that could not run at all
  (`IOException`) records nothing. `bwsl.json` (project root, `modulePaths` relative to the root and
  `exclude`) is read by `readProjectConfig`; `collectModulePaths` is the IDE setting's module paths
  plus the config's, and every compile, diagnostic run and the Compile action use it.
- `BwslAstCache.kt` — data classes mirroring the `bwslc -ast-json` schema (`AstRoot`, `AstModule`,
  `AstStruct`, `AstFunction`, `AstStatement`, `AstBlock`, `AstSymbol`, `AstReference`,
  `AstReferenceIndex`, ...) plus a cache keyed by file path, storing both the typed root and the
  raw JSON tree.
- `BwslAstIndex.kt` — a generic id → position index built by walking the *raw* JSON tree (not the
  typed model, which can't practically mirror every node type), plus lookups over the compiler's
  own `referenceIndex` (`symbolsById`/`refsByFrom`/`refsByTo`). `findNameRangeOf` is just a node's
  `nameLine`/`nameColumn` plus its name (`member` for a `MEMBER_ACCESS`), failing closed to null
  when absent — a node's own `line`/`column` is **not** its name (a `VARIABLE_DECL` points at the
  declared type, a `MODULE` at the keyword, a `MEMBER_ACCESS` at the dot). **Other files are in
  the payload too**: imported modules, and members a `submodule` merged into its parent. Their
  line/column are relative to the file they were written in, which `sourceFile` names on every
  top-level entry and member (inherited down by `AstNodePos.sourceFile`). `AstRoot.roots` says
  which entries are the compiled file's own. Never measure `AstRoot.modules`/`pipelines` directly
  against the current file's text: use `collectOwnModules()`/`collectOwnPipelines()`, or `BwslAstIndex.nodesById`
  (written by the compiled file) vs `externalNodesById` (written elsewhere, measured against that
  node's `sourceFile` text via `SourcePositions`).
- `BwslAstResolver.kt` — `resolveSymbolAt(file, index, offset)`: the single generic resolver.
  Caret → occurrence node (via `findNameRangeOf`) → the reference-index edge from that node → the
  target symbol's declaration → that declaration's position (an own node, an external node opened
  through its `sourceFile`, or a stage-interface value, which has no declaration and resolves to
  the target of the first assignment in the symbol's `definitions`) → PSI element. A caret on a
  declaration's own name ignores its `type`/`return-type` edges, so a field or parameter doesn't
  navigate to its type. Returns no results (not a guess) when nothing resolves — see "no
  fallback" below.
- `BwslPsiReferences.kt` — `BwslAstReference`, the one `PsiReference` (backed by `resolveSymbolAt`).
- Also index-driven, through `collectDeclarationIdsAt`/`collectReferenceEdgesAt`/`findSymbolAt`/
  `buildFunctionSignature` in `BwslAstResolver.kt` and `BwslAstIndex.findEnclosingNodeOfType`: every hover
  doc (`BwslDocumentationProvider`) and parameter info for non-intrinsic calls
  (`BwslParameterInfoHandler`). A function's signature is its symbol plus the parameter symbols it
  owns; its qualified name is its owners' names. The `input.x`/`output.x`/`attributes.x` docs follow
  the member's reference edge to the stage-interface, fragment-output or attribute symbol, and the
  qualifier docs list the pass's stage-interface symbols and used-attribute nodes. A doc for a call
  into another file is generated from the *hovered* element (`originalElement`), because the
  declaration it resolved to has no cached AST of its own. Intrinsics come from the built-in table
  in `BwslIntrinsics.kt`.
- Find Usages (`BwslFindUsages.kt`, `BwslUsages.kt`, `BwslUseScopeEnlarger.kt`) is index-driven too.
  The PSI has no named elements, so the platform's word-index search can't be used: a custom
  `ReferencesSearch` follows the compiler's incoming edges (`refsByTo`) in every cached AST,
  `BwslTargetElementEvaluator` makes a caret on a declaration's name a target, and
  `BwslFindUsagesProvider` says which elements are targets (a declaration name that has a symbol).
  A declaration is identified across files by its symbol's `stableId`, since node ids differ per
  compile (a local has no `stableId` and exists only in its own file's AST). A declaration in a
  file with no AST of its own (an imported module) is found through the importing file's AST, where
  it is a node written in that file. Each candidate reference must resolve back to the target
  through the normal resolver. `BwslUseScopeEnlarger` widens the use scope to the indexed files, since
  a module file outside the project would otherwise only be searched in itself. `collectPayloadFiles`
  is the indexed files that have an AST. `BwslFindUsagesHandlerFactory` brings the index up to date
  (modal progress, only when stale) before a real Find Usages, then returns no handler so the default
  one runs; it skips highlight-usages requests. A stage-interface value (`output.uv`) has no
  declaration node, so `findSymbolDeclaredBy` treats the target of the first assignment in the
  symbol's `definitions` as its declaration (the place the resolver already sends a reference to):
  that makes it a target, and every `output`/`input` edge to the symbol a usage. Limit: a file that
  does not compile has no known usages.
- Rename (`BwslRename.kt`) builds on Find Usages. The PSI has no named elements, so the platform's
  default rename can't edit it: `BwslRenameProcessor` takes the declaration and the usages the
  reference search found and replaces each occurrence's text itself, last-to-first within a file.
  A module declared in a file of the same name also renames the file (`prepareRenaming`), because
  bwslc finds a module by its file name. `BwslNamesValidator` accepts only plain identifiers (a
  keyword or type name lexes to something else). `substituteElementToRename` first brings the
  project index up to date behind a modal progress (only when some file is stale), and
  `findReferences` searches the indexed files, since the platform's rename scope (project content)
  would miss a module file in an outside `-modules` directory. The rename works from the compiler's
  last result, so it refuses to run, instead of editing the wrong text or missing a usage, when
  `checkCompilerViewOf` finds any *indexed* file whose view is not current (unsaved changes; changed
  since its AST was built; produces no AST; never compiled), or when the text at an occurrence is
  not the old name. An AST cached without its inputs counts as stale.
- Rename conflict detection (`BwslRenameConflicts.kt`, `findExistingNameConflicts`) asks the
  compiler instead of re-implementing scoping, so it is not another set of scope rules. The rename is
  applied to a *copy* of the sources (`SourceMirror`: a temp directory per source directory and
  module path, with the renamed text and, for a module in a same-named file, the new file name) and
  compared with an unrenamed copy, both compiled with bwslc, in parallel. It reports (1) errors the
  rename introduces (`-errors-json -no-validate -check`, matched by message so a moved line is not a
  new error; a file that stops producing an AST counts), (2) any change in the reference index's
  edges, and (3) for a stage value, a change in the number of stage-interface symbols (a merge).
  This works because of facts probed against bwslc, not assumed: node and symbol ids follow source
  order, so a rename changes no id and the edge sets are *identical* unless some name now resolves to
  a different declaration; the one id that carries a name, `PASS:n/interface:<name>`, is mapped
  old to new before comparing. Shadowing is legal in BWSL (a local may reuse a parameter's, a
  const's or a function's name), so capture is silent in the compiler and only the edge diff finds it;
  a duplicate in one block, an identical overload, a duplicate struct, field, parameter or module
  are hard errors, and `-ast-json` prints nothing for them. The conflicts reach the platform's
  conflicts dialog (a `ConflictsInTestsException` in tests). Skipped when the compiler's view is
  stale (the rename then refuses with the reason) or bwslc cannot be run.
- Formatting (`BwslFormatting.kt`, `BwslCodeStyle.kt`). The PSI is flat, so the formatting model is
  flat: one block per token (`collectLeaves`, looking through REFERENCE/CALL_EXPRESSION) directly
  under the file, each carrying the absolute indent of its line, computed up front by
  `IndentTracker` from what precedes it: `{` frames, open `(`/`[` (continuation indent), operator
  continuation lines, braceless bodies, and `case`/`default` labels (the `:` that ends a label, found
  at the paren depth the label started at, opens a `KW_CASE` construct; a `;` never ends it, the
  next label or the switch's `}` does, and a `default` only counts as a label when a `:` follows). A body is a *construct* (`if (...)`, `else`, `for`,
  `loop`, ...) opened when its header closes; it indents a level only if its first token starts a
  line, ends at its `;` or closing `}`, and an `else` after it attaches to the `if` just ended
  (walk outwards, stopping there), which is what puts `else` at its `if`'s level. `spacingBetween`
  has a rule only where the meaning is unambiguous and returns null (leave as is) otherwise: `<`/
  `>` (comparison or generic), `:`, `?`, `^` (xor or pointer) and signs. `getChildAttributes`
  (from `indentForNewLine`) is what Enter uses; `BwslTypedHandler` re-indents a typed `}`. Two
  things bit during development and are load-bearing: **block ranges must be read once when the
  model is built** (the formatter edits the tree as it goes, so a node's own range drifts), and the
  lexer's whitespace rule is `{WHITE_SPACE}+` - the platform formatter replaces *one* whitespace
  leaf per gap, and a lexer that emits one token per space corrupts text when it shrinks two of
  them. `BwslFormattingTest` formats every fifth file of the compiler's `tests`/`modules` (found
  next to `bwslc.path`; skipped if absent) and requires that no token changed and that a second
  pass changes nothing; before shipping a change to the rules run it with every file (all 1,045
  passed, 663 unchanged).
- Compiler updates (`BwslCompilerUpdates.kt`). The compiler's version is the `Compiler v <x>` line of
  its `-h` banner (`readCompilerVersion`; `-errors-json` also has a `version` field, `--version`
  does not exist). A development build says `0.0.0-dev`, and `parseReleaseVersion` returns null for
  that, a pre-release or anything non-numeric, so such a compiler is never "out of date".
  `BwslCompilerDownloader.findLatestReleaseTag` reads GitHub's `releases/latest`; `decideUpdate`
  offers a newer tag unless it is the one in `skippedCompilerVersion`. The check runs in the
  background once per session and at most once a day (`lastCompilerUpdateCheck`), can be turned off
  (`checkForCompilerUpdates`), and **Tools → Check for BWSL Compiler Update** asks at once and also
  offers a skipped version. **Update** downloads to the plugin's install path and sets it as the
  compiler path. The version reader and tag lookup are injectable for tests.
- Standard-library sources (`BwslStdlibSources.kt`). The compiler embeds its standard modules: the
  AST gives them `sourceFile` `stdlib://modules/<file>.bwsl` and a `sourceUrl`
  (`https://github.com/<owner>/<repo>/blob/<ref>/modules/<file>`, `<ref>` the release tag, or
  `master` for a dev build). `compileAndCache` hands every `sourceUrl` to
  `downloadInBackground`, which fetches the file and the rest of its directory (listed through the
  GitHub contents API, so unimported modules are known too) into `<system>/bwsl/stdlib/<ref>/modules/`,
  read-only. A tag is fetched once, a branch once per session. `AstNodePos.sourceUrl` is inherited
  down like `sourceFile`; `resolveInSourceFile` opens the copy for a `stdlib://` node and **only
  trusts it if the text at the node's name range equals the node's name**, since the copy is of the
  repository, not necessarily of the compiler's build (a mismatch resolves to nothing, not a guess).
  `findSourceKeyOf` maps a copy back to its `stdlib://` name so Find Usages from a standard
  declaration finds the identity through the importing file's AST; `BwslRenameProcessor` refuses
  elements in a copy. The downloader is injectable (`fetchText`, `cacheRoot`) so tests serve the
  compiler repo's own `modules/` directory instead of the network.
- Name completion (`BwslAstNames.kt`, the contributor): `collectNamesVisibleAt` reads the cached
  raw AST (the enclosing own module/pipeline's functions, structs and enums; the enclosing pass's
  functions; the functions and constants of modules named by `using`; the names of imported modules)
  and `collectMembersOf` the members of a `Qualifier::` (a module of the payload's `modules`, or an
  enum's values). The AST's `imports` entries record the module but **not** an `as` alias (only a
  `using` records `writtenName`), so `collectImportDeclarationsOf` reads `import X [as Y]` from the
  tokens. After `::` only members are offered (the contributor used to offer keywords there). The
  trigger accepts every name token (`FUNCTION_CALL`, `MODULE_NAME`, ...), not only `IDENTIFIER`, so
  it also works inside an existing `Mod::name(...)`.
- Auto-import (`BwslImports.kt`, the contributor). `collectImportableNames` takes the members of
  **every module entry in every cached AST** (own or imported), minus the modules the file imports
  (read from the tokens) and the one the caret is in, deduplicated; so it works for project module
  files and module paths as soon as the index has compiled them. The standard modules have no file to
  compile (compiling a copy of `math.bwsl` fails: it redeclares the embedded `Math`), so
  `BwslStdlibSources.writeProbeFiles` writes `Probe_<Module>.bwsl` = `module BwslProbe<Module> { import
  <Module> }` into a directory *outside* the cache, one per module found in the copies, and the
  background index compiles those (`refreshNow(includeStandardModules = true)`, not the modal one that
  rename and Find Usages wait on); the imported module's entry then holds its members. Names
  starting `BwslProbe` are never offered. Importable names appear from the second invocation
  (`parameters.invocationCount >= 2`; the first adds an advertisement), or at once after a typed
  `Module::` that names no visible module. The insert handler writes `Module::` (unless typed),
  runs the parentheses handler, commits, then `findImportInsertion` (token-based, so right for text
  newer than the last compile) inserts `import Module` after the last import of the enclosing
  top-level `{}` with that import's indent, or first in the block. A module imported under an alias
  counts as imported.
- Inspections (`BwslInspections.kt`: three `LocalInspectionTool`s, registered in plugin.xml with
  descriptions under `inspectionDescriptions/`) read the cached AST through `findInspectionInput`,
  which returns null unless the file's text hashes to what was compiled. Unused: a `variable`/
  `constant` symbol with no `owner` (a local) or a `parameter` whose own node is in this file, with no
  incoming edge, or only `write` edges. An unused import: an own `…/import:n`/`…/using:n` node whose
  target module is the owner (through `owner` links) of no edge's target other than other
  import/using edges. A missing import: a positioned `IDENTIFIER` followed by `::` with no outgoing
  edge whose name is a module some cached AST knows. Fixes work on tokens.
- File Structure and breadcrumbs (`BwslStructure.kt`): token-based on purpose (they must follow text
  being typed, like folding), not AST-driven. `collectOutline` pairs braces and walks each region
  statement by statement (a statement ends at its `;` or at the `}` of its block; `import`/`using` have
  no `;`): container keywords (module, submodule, pipeline, struct, enum, pass) recurse, `name :: (`
  is a function (method in a struct), `vertex`/`fragment`/`compute` a stage, `const … name =` a
  constant, `Type name;` in a struct a field. The structure view's elements are not PSI (the PSI has no
  named elements) and navigate with an `OpenFileDescriptor`; the breadcrumbs provider overrides
  `getParent` to walk the outline, since the PSI is flat.
- Go to Type Declaration (`BwslTypeDeclarations.kt`, `typeDeclarationProvider`; the platform hands
  over the declaration's name element, so the provider works from its offset): the symbol's
  `type`/`return-type` edge to a `struct` symbol, resolved with `resolveDeclarationPosition`.
- Go to Class / Symbol (`BwslGotoContributors.kt`, `gotoClassContributor`/`gotoSymbolContributor`
  over `ChooseByNameContributor`): `collectProjectSymbols` walks the project's BWSL files
  (`FileTypeIndex`) that have a cached AST of their current text, lists the symbols of kind
  module/pipeline/struct/pass/function/method/constant whose declaration is an own node, and the rows
  are lightweight `NavigationItem`s opening an `OpenFileDescriptor` (the PSI has no named elements).
- Semantic highlighting (`BwslSemanticHighlighting.kt`, an `Annotator` that runs once on the file):
  `collectSemanticHighlights` colours the name range of every own node whose own symbol, or the
  target of its edge, is a parameter, variable, loop-iterator, constant or struct-field.
- Inlay hints (`BwslInlayHints.kt`, a declarative `InlayHintsProvider` registered as
  `codeInsight.declarativeInlayProvider`, strings in `messages/BwslBundle.properties`):
  `collectParameterNameHints` takes each `FUNCTION_CALL` token followed by `(`, the function or
  method its edge resolves to (`collectDeclarationIdsAt`), the parameter symbols it owns, and
  splits the arguments by bracket depth. Only with an AST of the current text
  (`findInspectionInput`). A second provider, `collectArrayLengthHints`, walks the raw AST for
  declarations of the file with a non-empty `typeInfo.arraySizes`, reads the written type at
  `typeLine`/`typeColumn` (`float[N]`) and, when a size in it is not a number, shows the resolved
  sizes after it.
- Extend Selection (`BwslSelection.kt`, an `ExtendWordSelectionHandlerBase`) and Smart Enter
  (`BwslSmartEnter.kt`, a `SmartEnterProcessor`, which lives in
  `com.intellij.codeInsight.editorActions.smartEnter`, registered as `lang.smartEnterProcessor`).
  Both work on tokens. `collectSelectionRanges` pairs every bracket (`findGroups`), then for each
  group around the caret, innermost first, adds: for `( )`/`[ ]` the argument, the contents, the group;
  for `{ }` the statement, the contents, the block, and the header statement (found in the parent
  region) with the block. A statement (`findStatementBounds`) starts after the previous `;` or `}`
  (not a `}` before `else`) at the region's level, skipping whole groups, and ends at its `;` or at the
  `}` that closes a block (not one followed by `else`). The platform picks the smallest returned range
  that is larger than the selection. `planStatementCompletion` returns *edits* (closing brackets, then
  `;` or ` {\n\n}`), not a text, so it can be tested without touching the document; the processor
  applies them last-to-first and re-indents the two new lines with `adjustLineIndent`. A `;` is only
  added where the enclosing `{`'s owner is not `attributes`/`resources`/`variants`/`outputs`/`inputs`
  or a `module`/`pipeline`/`submodule`/`enum` body (except a `const`), and never for `import`, `using`,
  `case`/`default` or a line ending in a continuing token. Returning null makes the platform start a new line.
- Formatter options (`BwslCodeStyleSettings` in `BwslCodeStyle.kt`, used in `BwslFormatting.kt`):
  `BRACE_STYLE` (`BraceStyle`: keep/end of line/next line), `WRAP_CALL_ARGUMENTS`,
  `ALIGN_CONTINUED_EXPRESSIONS`, all off by default so the "no token moves" guarantee holds. Facts
  that shaped them: (1) **do not use the common `WRAP_LONG_LINES` flag**: it is what makes the
  platform execute wraps *and* a hard wrapper that cuts a line wherever it passes the margin, through
  `1.055` as `1` `.` `055`; and `Wrap` objects on flat blocks never fired without it. So
  `collectWrappedCallArguments` simulates the layout itself (indent per token, spacing from
  `spacingBetween`, one line at a time) and returns the tokens that must start a line, chopping the
  commas of the shallowest call on an over-long line, then the next, until it fits; it depends only
  on the tokens, so a second pass changes nothing; they become `SpacingRule(minLineFeeds = 1)`.
  (2) Braces: `SpacingRule` carries min/max spaces, `minLineFeeds` and `keepLineBreaks`; only a `{`
  that follows a brace owner (`)`, a name, a string, any `KW_`) and whose block spans lines is moved,
  so `case 1: {` and `{ x }` stay. A block counts as multi-line also when the formatter adds a break
  inside it (a wrapped argument, an `else` moved to its own line, a nested multi-line block) -
  `collectMultiLineBraces(leaves, text, wrapped, movesElse)` - otherwise its brace moved only on the
  second pass. (3) Alignment uses platform `Alignment` objects (one per expression after `=`/`return`,
  one per paren group), which do work on flat blocks. `BwslFormattingOptionsTest` runs the compiler's
  `tests`/`modules` with every option on and requires unchanged tokens and a stable second pass
  (verified over all 1,045 files; the committed sample is every seventh).
- Member completion (`BwslReceiverTypes.kt`). After a `.` the receiver is **read from the tokens**
  (`parseReceiver` walks back from the token before the dot: `)` to its `(` and the name before it,
  `]` to its `[`, `.`/`::` to the previous element) into steps (Name, Call, Field, Method, Index),
  then typed left to right: the base from the visible locals (`collectVisibleLocalsAt`), the
  enclosing struct's fields (inside a method), constants, or a call's return type (an overloaded
  name whose return types differ has none); a field from the struct's `fields[].dataType`, a vector
  swizzle by `^(float|int|uint|double)[234]$` (families `xyzw` and `rgba`, never mixed, limited to
  the component count), a method from `methods[].returnType`, `[i]` as element/column/component.
  Probed facts that shaped it: every declaration (parameter, local, field, and the symbols) has
  `typeInfo` (`elementType`, `arrayDimensions`, `arrayLength`, `arraySizes`: resolved sizes, also for
  `float[N]`), and `declaredType`/`dataType` is the *element* type; `VisibleLocal.isArray` reads
  `typeInfo.arrayDimensions`. A compiler from before that (BWSL#106) gave an array local the
  `declaredType` `"array"` and a parameter nothing; the code still reads the element type from the
  source at `typeLine`/`typeColumn` (`VisibleLocal.typePosition`) for the first, and a field has `arraySize`, and
  `stpq` is not a swizzle family. Nothing is guessed: an untypable receiver gives no members. A
  swizzle in progress is extended from `result.prefixMatcher.prefix`. After a dot keywords and type
  names are skipped; the intrinsics stay (method-style calls like `v.normalize()`), so the generic
  `length` intrinsic is always there and the array member is told apart by its `int` type text.
  Fields and methods of the enclosing struct are also added to `collectNamesVisibleAt` (a struct's
  range holds its methods' bodies).
- Expected types (`BwslExpectedTypes.kt`). `collectExpectedTypesAt` reads the tokens before the
  caret: it drops a word ending at the caret (being typed), skips back over a member chain or
  `Module::` (`skipBackOverMemberAccess`, via `parseReceiver(...).startIndex`, so `float t = l.|`
  looks at `float t =`), then looks at the token before: an assignment operator (a declaration
  `Type name =`, with the type read from the tokens and checked for a boundary before it, else the
  type of the target chain through the receiver machinery), `return` (the innermost enclosing
  function/method/pass function in the raw AST), or `(`/`,` (walk back to the unmatched `(` counting
  top-level commas, then the callee: a method through the receiver's struct, `Mod::f`, a function in
  scope, or an intrinsic whose table classes `floatN`, `floatVecN`, `numeric`, `scalar`, `matN`,
  `boolVecN`, `texture` are expanded to types; `T` expands to nothing). Overloads give a *set*.
  `doesTypeMatch` compares ignoring a module qualifier. Basic completion adds `TYPE_MATCH_BONUS`
  (1000) to the priority of a typed suggestion that matches; a separate `CompletionType.SMART`
  provider offers only matching typed values (locals, functions by return type, constants, fields,
  members after `.`/`::`), and *all* typed values when nothing is expected. Only what directly
  precedes the caret counts, so `a + |` expects nothing; no implicit conversions are assumed.
- Tests never use the network: `BwslAstFixtureTestCase` stubs `BwslStdlibSources.fetchText` (a
  compile of a file that uses a standard module starts a background download, which would otherwise
  reach GitHub and could land in another test's cache directory).
- Intrinsic documentation (`BwslIntrinsicDocs.kt`, `BwslIntrinsicNavigation.kt`). The official page of an
  intrinsic is `https://www.bwsl.dev/docs/intrinsics/<name>` (server-rendered HTML, no raw markdown
  or API). `parseDocPage` takes the page's `<meta name="description">` as the summary and the first
  `<p>` of `div.prose.docs-prose` as the intro (only `code`/`em`/`strong` kept); it is fragile to a site
  redesign and then yields null, so the hover falls back to the built-in line. `findDoc` reads the disk
  cache (`<system>/bwsl/intrinsic-docs/<name>.json`, a week) and starts one background fetch per name
  per session when there is no fresh copy; it answers null until the copy is there. `UNDOCUMENTED`
  (`fmod`, `barrier`, `memoryBarrier`, `storageBarrier`) lists the table's intrinsics with no page,
  and `discard` (a keyword here) has one; `BwslDocumentationSiteTest` compares that with the site's
  listing page. Ctrl+click is a
  `gotoDeclarationHandler` returning a `FakePsiElement` (`BwslDocumentationTarget`) whose `navigate`
  calls `BwslBrowser.open` (replaced in tests); the `.` before an array's `length()` is found by
  climbing to the first ancestor with a previous sibling, since the token is wrapped twice. The fixture
  base class stubs the fetch and the cache directory.
  Keywords go to the language pages (`BwslKeywordDocs.kt`: `KEYWORD_PAGES` by token type, so a keyword
  inside a string or comment is not one; `attributes` is told apart by the token after it, `{` or `.`,
  and `input`/`output`, plain identifiers to the lexer, count only before a `.`). Which keyword goes
  to which page is a judgement (the site has no keyword index): `vertex`/`fragment` to the pipeline page,
  `constraint`/`rules`/`require`/`conflict` to shader variants, the resource qualifiers to resources.
  Tests that read the site (`BwslDocumentationSiteTest`: the intrinsic listing, every keyword page) are
  JUnit 5, because a JUnit 3 `TestCase` (every platform fixture test) reports a failed `Assume` as a
  failure, not a skip; they are skipped when the site cannot be reached.
- Intrinsic SPIR-V mapping (`BwslIntrinsicSpirv.kt`). `SPIRV_INSTRUCTIONS` maps an intrinsic to the
  instructions in the compiler's table (`SPV_MAP(core op, GLSLstd450 op)` in `src/core/bwsl_stdlib.h`,
  plus its comments for type-dependent variants and the wave ops, whose op is only a number there). A
  name with an `Op` prefix is core (the spec page has an anchor per instruction); any other is
  `GLSL.std.450` (that page has no per-instruction anchors, so the link is a text fragment, `#:~:text=Name`,
  checked offline against the page: the name's first whole-word match is its definition for all but
  `RoundEven`, `ModfStruct`, `FrexpStruct` and `Degrees`, which get a suffix of the words after the name). Left out: rows
  with both ops `NONE` (`rcp`, `log10`, `isfinite`, ...) and the `*_offset` sampling variants, whose
  op the table does not say. The mapping is a snapshot of that table: **tests cannot read the compiler's
  source** (they only have the compiler binary), so `BwslIntrinsicSpirvTest` checks what a binary can
  tell - every name in `BwslIntrinsics` is known to the compiler under test (called with no arguments:
  either its arity error names it, or the reference index resolves the call to `builtin:function:<name>`;
  an unknown name does neither) and every mapped name is in the table. It cannot catch an intrinsic the
  compiler gained or a changed instruction; when the compiler's table changes, redo the mapping from
  `src/core/bwsl_stdlib.h` by hand.
- Doc comments (`BwslDocComments.kt`). `findDocCommentAbove(file, nameOffset)` reads the text, not
  the AST: from the name's token it goes back to the first token of that line (the declaration's
  start, so `const float PI` and `struct Point` work), then collects the comments directly above
  (exactly one newline between each and what follows). `///` lines (not `////`) and `/** */`
  blocks count; a blank line or an ordinary `//` stops the walk. `findDocumentationFor` in the
  provider resolves the hovered symbol to its declaration element with `resolveSymbolAt` (so a call
  into another file, or into a fetched copy of a standard module, reads *that* file's text) or, on
  a declaration's own name, uses the own node, and `renderDocCommentHtml` escapes, joins
  paragraphs, marks up `code` and links. It is a separate CONTENT block between the signature and
  the qualified name. A struct or enum has no signature popup, so `renderTypeDoc` only shows one
  when it has a doc comment.
- Spell-checking (`BwslSpellchecking.kt`). `BwslSpellcheckingStrategy` returns the text tokenizer for
  `LINE_COMMENT`, `BLOCK_COMMENT` and `STRING_LIT` and nothing for anything else (BWSL's comments
  are plain tokens, not `PsiComment`, so the platform's default strategy sees none of them).
  `BwslBundledDictionaryProvider` registers `com/bwsl/plugin/bwsl.dic` (one word per line) for the
  shading vocabulary. Build: `bundledModule('intellij.spellchecker')` for the API, and
  `<dependencies><module name="intellij.spellchecker"/>` in plugin.xml. **The typo inspection itself
  is `GrazieSpellCheckingInspection` in the Natural Languages plugin (`tanvd.grazi`) in this IDE
  version**, so the tests add `testBundledPlugin('tanvd.grazi')`; the plugin does not depend on it.
- Reading a process: **read stdout and stderr at the same time** (`compileAst`, the test helper).
  Reading one to its end first deadlocks when a file with many errors fills the other pipe, and the
  15 s timeout never starts because it comes after the reads.
- Editor support that needs only tokens (`BwslEditing.kt`, `BwslFolding.kt`): `BwslCommenter`
  (`//`, `/* */`), `BwslBraceMatcher` (`{}` structural, `()` and `[]`; `<`/`>` deliberately not a
  pair, and a closing bracket is only auto-inserted before whitespace, a comment, the end or
  something that closes), `BwslQuoteHandler` (a `SimpleTokenSetQuoteHandler` on `STRING_LIT`) and
  `BwslFoldingBuilder`. Folding reuses the formatter's `collectLeaves`: a stack pairs `{`/`}`
  (a region only if they are on different lines, placeholder `{...}`), a multi-line block comment
  folds as `/*...*/`, and consecutive `//` comments that each start a line, one directly under the
  other, fold to `// <first line>...`. None of it needs an AST, so it works while the file does not
  compile. The Enter-between-braces behaviour comes from the brace matcher plus the formatter's
  `getChildAttributes`.
- **Not** index-driven, by design: completion (`completion/`). It runs on half-typed code where the
  cached AST is stale, so it works from the typed model (`BwslAstScope.kt`: `findScope`,
  `classifyBlockContextAt`, `collectVertexOutputAssignments`, `collectPassUsedAttributes`, `deduceExprType`) and line
  ranges. Nothing else may use those helpers. The parameters, locals, consts and loop variables it
  offers in a function or stage body come from `collectVisibleLocalsAt` (`BwslAstLocals.kt`), which walks
  the *raw* cached AST down to the position (only into containers whose range holds it) rather than
  asking the reference index: it answers "what could be typed here", not "what does this name
  refer to". A name counts once it is declared and only while its block is open; module-, pipeline-
  and pass-level consts count throughout. The AST is the live one (see the annotator above),
  from the last successful compile of the editor's text, so a local typed since then is offered once the
  annotator has run, but not while a syntax error is open (no AST, so the previous one stays).

## The AST and reference index (what the plugin relies on)

Schema `bwsl.ast.v3`, from `bwslc <file> -ast-json [-modules <dir>]` (may be UTF-16 with a BOM).

- **Ids.** Every AST node has an `id` of the form `TYPE:index` (`FUNCTION:3`). Synthetic ids are
  `<owner-id>/<kind>:<index-or-name>`: `FUNCTION:0/parameter:1`, `STRUCT_DECL:0/field:0`,
  `PASS:0/used-attribute:2`, `PASS:0/fragment-output:1`, `PASS:0/interface:uv`,
  `MODULE:0/import:0`, `MODULE:0/using:0`, `VARIABLE_DECL:0/type-qualifier`,
  `LITERAL:7/folded-constant`. `builtin:*` (`builtin:function:cos`, `builtin:type:float4`) has no
  source and resolves to nothing.
- **Positions** are 1-based. `nameLine`/`nameColumn` on every named node is where its name starts;
  the node's own `line`/`column` is not (a `VARIABLE_DECL` points at the type, a `MODULE`/
  `STRUCT_DECL`/`PIPELINE` at the keyword, a `MEMBER_ACCESS` at the dot, a qualified call at `::`).
  Declarations also have `typeLine`/`typeColumn`; bodies have `endLine`/`endColumn`.
- **`referenceIndex.symbols`** reuse the AST ids and carry no position of their own; a symbol's
  position always comes from the AST node with the same id. Fields: `id`, `kind`, `name`,
  `declaration`, `owner`, `type`, `stableId`, and `definitions` (the ids of the assignments that
  write it, in source order) on symbols that are assigned. A stage value's `type` is inferred, including
  from an intrinsic call's result. Kinds seen: `module`, `pipeline`, `pass`, `struct`,
  `struct-field`, `variable`, `constant`, `parameter`, `function`, `method`, `attribute`,
  `core-type`, `intrinsic`, `stage-interface`, `fragment-output`.
- **`referenceIndex.references`** are `from -> to [role]` edges. Roles seen: `read`, `write`,
  `call`, `construct`, `type`, `return-type`, `qualifier`, `member`, `output`, `input`,
  `attribute`, `import`, `using`. `type` and `return-type` describe what a declaration declares;
  they are not what a caret on the declaration's own name navigates to.
- **Stage IO.** A vertex `output.x = ...` and a fragment `input.x` reference the same
  `PASS:n/interface:x` symbol (roles `output` and `input`). A stage interface value has no
  declaration; its assignment is its definition. A fragment output is declared by an entry in the
  pass's `outputs { name: type }` block, and `output.x` in the fragment stage has an `output` edge
  to that declaration.
- **Qualifiers.** `Mod::f()` has a positioned `qualifier` IDENTIFIER with a `qualifier` edge to the
  module; `Mod::Type v` (variables, parameters, struct fields) has a positioned `typeQualifier` with
  one too. A function records where its return type is written (`returnTypeLine`/
  `returnTypeColumn`, and `returnType` keeps the qualification as written); a qualified one also has
  a positioned `returnTypeQualifier` with a `qualifier` edge to the module, alongside the
  `return-type` edge to the type.
- **Consts.** Module-, pipeline-, pass- and function/stage-level consts are `VARIABLE_DECL` nodes
  (module/pipeline/pass ones also in a `consts` array). A use the parser folds into a literal keeps
  a positioned `foldedFrom` IDENTIFIER with a `read` edge to the declaration.
- **Other files.** `roots` lists the ids of the compiled file's own top-level declarations;
  `modules[]`/`pipelines[]` also hold every imported module, and a `submodule`'s members are merged
  into its parent module. `sourceFile` on top-level entries and members names the file each was
  written in (the compiled file as it was passed to bwslc, others as an absolute path), and a
  node's line/column are relative to that file.
- **Keys.** `type` is the node kind where present; parameters and struct fields use `dataType` for
  their data type. The singular `root` repeats one top-level pipeline; use `roots`.

## AST-driven design principle (important — user preference)

Navigation/scope resolution is driven entirely by **bwslc's own reference index**
(`referenceIndex.symbols`/`referenceIndex.references` in `-ast-json`), not by re-implementing
BWSL's scoping rules in the plugin. The compiler already resolves scope-sensitive cases (two
same-named functions in different `pipeline`/`pass` blocks, `s.method()` where `s`'s struct type
must be looked up, etc.) — the plugin just follows its edges.

**No lexical/PSI-tree-walking fallback.** When no AST is cached (or the reference index has no
edge for a given position), `BwslAstReference` returns no results — it does not fall back to text
search or tree-walking. This is deliberate (writing BWSL without a working compiler isn't a
supported workflow) — do not reintroduce a "thin fallback."

## Verifying against real bwslc

`C:\Users\lundis\BWSL\BWSL\build\bwslc.exe <file> -ast-json [-modules <dir>]` is the real compiler
binary. Tests that need real AST/reference-index output use `BwslcAstHelper`
(`src/test/kotlin/com/bwsl/plugin/completion/BwslcAstHelper.kt`) — `parse`/`parseRaw` shell out to
it, and `parseAndCache(source, filePath)` populates `BwslAstCache` with both (mirrors what
`BwslAstAnnotator` does for a real file). Prefer this over hand-built `AstRoot` literals in new
tests — a hand-built `AstRoot` has no `referenceIndex`, so `BwslAstReference` resolves nothing
against it.

When something looks wrong, verify against the compiler directly before assuming it's a plugin
bug, and don't assume plugin-side AST/index code is wrong without probing real `bwslc` output
first. A plain compile (`bwslc <file>`, not `-ast-json`) also reports semantic errors that
`-ast-json` doesn't, so check a probe file with both. It writes `.spv` files next to the source;
delete them. Probe files live in `src/test/resources/manual_ast_test_files/`.

Module files are written by `BwslcAstHelper` into a directory that outlives the compile (cleaned up
at JVM exit), because the AST names them by absolute `sourceFile` and navigation opens that path.
Tests must not add the module to the fixture project to make navigation work — that would hide a
wrong path.

## Test reference files

- `src/test/resources/lexer_test_files/module.bwsl` — the running example for lexer and
  reference-resolution tests: intrinsic vs. method calls on array receivers
  (`values.length()` vs `values.cos()`), same-named functions/methods across different
  modules/structs (`test`/`testStruct::test`), and qualified/typed variable declarations
  (`LengthMethodTest::testStruct s1;` vs `testStruct s2;`).
- `src/test/resources/manual_ast_test_files/` — ad-hoc `.bwsl` + generated `.ast.json` pairs used
  to investigate/document specific compiler behavior (not wired into any test; regenerate the
  `.ast.json` from the `.bwsl` with the compiler binary above when investigating further, don't
  hand-edit it).
