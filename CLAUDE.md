# jetbrains-bwsl

JetBrains IDE plugin (Kotlin, IntelliJ Platform SDK) for the BWSL shader language.

## Build / test

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME","User")
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
./gradlew.bat test --no-configuration-cache
```
JAVA_HOME (`C:\Users\lundis\.jdks\corretto-21.0.9`) is set persistently at User level but is **not**
inherited by fresh shell sessions started by tools — always export it first.

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
  Decides which reference class (`BwslFunctionReference`, `BwslVariableReference`,
  `BwslTypeReference`, `BwslModuleReference`) applies based on the inner element type and
  surrounding tokens. `BWSL_TYPE_KEYWORDS` is the TokenSet of built-in type keywords
  (`float`, `int`, `float2`, ...).
- `BwslAstAnnotator.kt` — runs `bwslc <file> -ast-json -modules <paths...>` as an
  `ExternalAnnotator`, parses the JSON (handles UTF-16 BOM output) into `AstRoot` via Gson, and
  stores it in `BwslAstCache`.
- `BwslAstCache.kt` — data classes mirroring the `bwslc -ast-json` schema (`AstRoot`, `AstModule`,
  `AstStruct`, `AstFunction`, `AstStatement`, `AstBlock`, ...) plus a cache keyed by file path.
- `BwslAstScope.kt` — the core of AST-driven navigation. Converts PSI offsets ↔ 1-based
  line/column (`lineColumnAt`/`offsetAt`, matching bwslc's AST positions), finds the enclosing
  module/struct/pass/function from AST ranges (`findScope`, `findEnclosingFunction`), collects
  local `VARIABLE_DECL`s (`collectVariableDecls`), resolves a variable's declared type
  (`findVariableType`), and resolves `Module::Type` qualified struct types (`resolveStruct`).
- `BwslPsiReferences.kt` — the actual `PsiReference` implementations:
  - `BwslFunctionReference` (poly-variant): resolves function/method calls via
    `resolveViaAst` (AST scope + receiver-variable-type lookup for `s.method()`, qualifier lookup
    for `Module::func()`). Falls back to `walkScopes` (brace-depth PSI tree-walk) when no AST is
    cached.
  - `BwslVariableReference`: resolves variable/parameter usages via `resolveVariableViaAst`
    (finds enclosing AST function, then the nearest preceding `VARIABLE_DECL` or matching
    parameter, then locates the actual PSI token via `findIdentifierInRange` — leftmost matching
    identifier in the declaration's line/function range, skipping `.`/`::` member-access
    occurrences). Falls back to a whole-file nearest-preceding-declaration text search when no
    AST is cached.
  - `BwslTypeReference`: resolves a declared type name (`testStruct` or
    `LengthMethodTest::testStruct`) to its `AstStruct` declaration via `isAstTypeReference` +
    `resolveStruct`. Falls back to a lexical "identifier followed by identifier" heuristic when
    no AST is cached.
  - `BwslModuleReference`: resolves `import`/`using` module names to `.bwsl` files (via
    `FilenameIndex`, configured module paths, or sibling lookup).

## AST-driven design principle (important — user preference)

Navigation/scope resolution should be driven by the **bwslc AST** (`-ast-json`, with line/column
ranges for modules/structs/passes/functions/statements), not by ad-hoc PSI tree-walking or text
search. The flat token-based PSI tree has no real scoping, so things like two same-named
functions in different `pipeline`/`pass` blocks, or `s.method()` where `s`'s struct type must be
looked up, cannot be disambiguated by tree-walking alone. The lexical/tree-walking code paths
that remain (`walkScopes`, text-search fallbacks in `BwslVariableReference`/`BwslTypeReference`)
are explicitly **fallbacks for when no AST is cached** (e.g. unit tests that don't shell out to
`bwslc`, or the compiler not configured) — new functionality should be implemented AST-first,
with the lexical approach only as a fallback, not the primary mechanism.

## Known bwslc AST bugs (reported upstream, blocking removal of fallbacks)

The user wants to eventually remove all lexical fallbacks and have tests run against a real
`bwslc` binary (found locally at `C:\Users\lundis\BWSL\BWSL\build\bwslc.exe`,
`bwslc <file> -ast-json -modules <dir>`). This is currently blocked by a compiler bug:

- `VARIABLE_DECL.line`/`column` is supposed to be the start of the declared-type text, and is
  correct for some cases (e.g. `LengthMethodTest::testStruct s1;` → points at `LengthMethodTest`).
  But for many other locals (e.g. `float2 normalized = ...` on its own line), `line`/`column`
  instead points at the *previous* statement/token's end position (often the `{` that opened the
  enclosing block), not the actual declaration site. This makes span-based matching
  (`isAstTypeReference`) unreliable for non-struct-typed locals — currently masked because those
  are built-in types filtered out by `BWSL_TYPE_KEYWORDS` before reaching that check.

Once this is fixed upstream, revisit: rewrite `BwslReferenceTest` to populate `BwslAstCache` via
real `bwslc` output (a small test helper that shells out like `BwslAstAnnotator` does), then strip
`walkScopes` and the text-search fallbacks from `BwslPsiReferences.kt`.

## Test reference file

`src/test/resources/lexer_test_files/module.bwsl` is the running example used for lexer and
reference-resolution tests — it deliberately contains tricky cases: intrinsic vs. method calls on
array receivers (`values.length()` vs `values.cos()`), same-named functions/methods across
different modules/structs (`test`/`testStruct::test`), and qualified/typed variable declarations
(`LengthMethodTest::testStruct s1;` vs `testStruct s2;`) used to verify navigation correctly
disambiguates by AST scope rather than text proximity.
