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
- `BwslAstAnnotator.kt` — runs `bwslc <file> -ast-json -modules <paths...>` as an
  `ExternalAnnotator`, parses the JSON (handles UTF-16 BOM output) into both the typed `AstRoot`
  and a raw `JsonObject` via Gson, and stores both in `BwslAstCache`.
- `BwslAstCache.kt` — data classes mirroring the `bwslc -ast-json` schema (`AstRoot`, `AstModule`,
  `AstStruct`, `AstFunction`, `AstStatement`, `AstBlock`, `AstSymbol`, `AstReference`,
  `AstReferenceIndex`, ...) plus a cache keyed by file path, storing both the typed root and the
  raw JSON tree.
- `BwslAstIndex.kt` — a generic id → position index built by walking the *raw* JSON tree (not the
  typed model, which can't practically mirror every node type), plus lookups over the compiler's
  own `referenceIndex` (`symbolsById`/`refsByFrom`/`refsByTo`). `nameRangeOf` is just a node's
  `nameLine`/`nameColumn` plus its name (`member` for a `MEMBER_ACCESS`), failing closed to null
  when absent — a node's own `line`/`column` is **not** its name (a `VARIABLE_DECL` points at the
  declared type, a `MODULE` at the keyword, a `MEMBER_ACCESS` at the dot). **Other files are in
  the payload too**: imported modules, and members a `submodule` merged into its parent. Their
  line/column are relative to the file they were written in, which `sourceFile` names on every
  top-level entry and member (inherited down by `AstNodePos.sourceFile`). `AstRoot.roots` says
  which entries are the compiled file's own. Never measure `AstRoot.modules`/`pipelines` directly
  against the current file's text: use `ownModules()`/`ownPipelines()`, or `BwslAstIndex.nodesById`
  (written by the compiled file) vs `externalNodesById` (written elsewhere, measured against that
  node's `sourceFile` text via `SourcePositions`).
- `BwslAstResolver.kt` — `resolveSymbolAt(file, index, offset)`: the single generic resolver.
  Caret → occurrence node (via `nameRangeOf`) → the reference-index edge from that node → the
  target symbol's declaration → that declaration's position (an own node, an external node opened
  through its `sourceFile`, or a stage-interface value, which has no declaration and resolves to
  the target of the first assignment in the symbol's `definitions`) → PSI element. A caret on a
  declaration's own name ignores its `type`/`return-type` edges, so a field or parameter doesn't
  navigate to its type. Returns no results (not a guess) when nothing resolves — see "no
  fallback" below.
- `BwslPsiReferences.kt` — `BwslAstReference`, the one `PsiReference` (backed by `resolveSymbolAt`).
- Also index-driven, through `declarationIdsAt`/`referenceEdgesAt`/`symbolAt`/
  `functionSignatureOf` in `BwslAstResolver.kt` and `BwslAstIndex.enclosingNodeOfType`: every hover
  doc (`BwslDocumentationProvider`) and parameter info for non-intrinsic calls
  (`BwslParameterInfoHandler`). A function's signature is its symbol plus the parameter symbols it
  owns; its qualified name is its owners' names. The `input.x`/`output.x`/`attributes.x` docs follow
  the member's reference edge to the stage-interface, fragment-output or attribute symbol, and the
  qualifier docs list the pass's stage-interface symbols and used-attribute nodes. A doc for a call
  into another file is generated from the *hovered* element (`originalElement`), because the
  declaration it resolved to has no cached AST of its own. Intrinsics come from the built-in table
  in `BwslIntrinsics.kt`.
- **Not** index-driven, by design: completion (`completion/`). It runs on half-typed code where the
  cached AST is stale, so it works from the typed model (`BwslAstScope.kt`: `findScope`,
  `blockContextAt`, `vertexOutputAssignments`, `passUsedAttributes`, `deduceExprType`) and line
  ranges. Nothing else may use those helpers.

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
