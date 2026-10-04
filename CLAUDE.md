# jetbrains-bwsl

JetBrains IDE plugin (Kotlin, IntelliJ Platform SDK) for the BWSL shader language.

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
  via Gson, and stores both in `BwslAstCache` together with the files the AST was built from. Its
  `apply` also shows the shadowing warnings (`BwslShadowing.kt`): bwslc allows a parameter, local,
  const or loop variable to reuse a visible name and says nothing (probed), so
  `collectShadowingDeclarations` collects each such declaration from the raw AST and asks
  `collectVisibleLocalsAt` (the completion walker, so the two share one notion of scope) what is in
  scope *just before* it: at its own start for a local, one column before the function for a
  parameter, one column before a loop variable. The result carries the hash of the text bwslc
  compiled, and `apply` shows nothing unless the editor text still hashes to it (the compile reads
  the saved file, so positions are wrong while there are unsaved edits). Module-, pipeline- and
  pass-level consts are visible throughout their container and are not checked.
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
  and pass-level consts count throughout. The AST is from the last successful compile of the saved
  file, so a local typed since then is not offered until the file is saved and re-annotated.

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
