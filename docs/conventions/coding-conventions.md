# Coding conventions

## Naming functions

Every function is named as a verb phrase: the name starts with a verb in its base form and says
what the function does, so a call reads as an instruction. This applies to every function the
project defines: top-level and member functions, private helpers, extension functions, local
functions and lambdas that are given a name, and test helpers.

A name is never a bare noun or noun phrase (`declarationName`, `pendingItems`), and never
`<noun>Of`/`<noun>At` without a verb in front. A qualifier such as `At`, `Of`, `For`, `In` or
`From` comes **after** the verb phrase to say what the argument is, not instead of the verb.

### Choose the verb by what the function does

| Kind of function | Verb | Contract | Examples |
|---|---|---|---|
| Look something up that may not exist | `find` | Returns `null` (or an empty collection for a plural) when there is no match. Never throws for "not found". | `findSymbolAt(index, offset)`, `findEnclosingNodeOfType(type, offset)`, `findRoot(filePath)` |
| Read something that is always there | `get` | Cheap, no search, never null. Use for accessors and simple derivations of a field. | `getInstallPath()`, `getInstance()` |
| Gather every item that matches | `collect` | Returns a list (possibly empty), in a stable, documented order. | `collectOwnModules()`, `collectVisibleLocalsAt(root, rawJson, line, column)`, `collectDeclarationIdsAt(index, offset)` |
| Follow a reference to what it points at | `resolve` | Returns what the reference points at, or nothing when it points at nothing. | `resolveSymbolAt(file, index, offset)`, `resolveDeclarationPosition(file, index, declId)` |
| Work a value out | `compute` / `deduce` | `compute` for a calculation from the arguments alone; `deduce` for inferring something that is not stated. | `deduceExprType(expr, block, attributes)` |
| Assemble a new structure from parts | `build` | Returns the finished value; the caller owns it. | `buildAstIndex(file)`, `buildFunctionSignature(index, symbol)` |
| Make a new instance of a type | `create` | Always returns a new object. | `createPositionsFor(text)` |
| Convert one representation into another | `to` | Pure; the result carries the same information in the other form. | `toOffset(line, column)`, `toLineColumn(file, offset)`, `Diagnostic.toTextRange(document)` |
| Present a cheap view of a value as another type | `as` | Does not copy; may fail with `OrNull`. | `JsonElement.asStringOrNull()`, `JsonElement.asIntOrNull()` |
| Turn a value into text for a person | `render` / `format` | `render` produces markup or a document; `format` produces a short string. | `renderFunctionDoc(at)`, `renderSignatureHtml(returnType, name, params)`, `formatInterpolationLabel(interpolation)` |
| Walk a structure | `visit` | Calls back for each node; returns nothing useful. | `visitObject(o, inBlock)` |
| Run an action with side effects | a specific verb for the effect | Name the effect, not just that something happens. | `downloadLatest(target)`, `notify(project, content, type)` |
| Change state | `add` / `remove` / `update` / `set` / `clear` / `register` | `set` replaces a value; `update` changes it based on its old value. | `add(name, type, kind)` |
| Check a condition (returns `Boolean`) | `is` / `has` / `can` / `does` / `should` | See "Predicates" below. | `isOperator(…)`, `hasRange()`, `doesRangeContain(…)` |
| Load or store | `load` / `save` / `read` / `write` | Reading and writing something outside the process or its cache. | `readModuleBwslSource()`, `readTypeTextOf(name)` |
| Verify | `check` / `verify` / `assert` | `check` returns a result describing what is wrong; `assert` throws, and is the form for test assertions. | `assertCompletions(source, present, absent)`, `assertNameRangeIs(index, source, nodeId, expected)` |

When two verbs seem to fit, pick the one whose contract matches what the function does:

- `find` searches and can come back empty; `get` does not search and cannot. A lookup in a cache is
  a `find`, even when it is a single map access: `findRoot(filePath)` returns null when nothing is
  cached, `getInstallPath()` always has an answer.
- `collect` returns *all* matches; `find` returns *one* (or the first): `collectOwnModules()` against
  `findEnclosingNodeOfType(type, offset)`.
- `build` assembles from parts and `create` instantiates; if the function does both, it is `build`.
- `compute` and `deduce` are pure; if there are side effects, name the effect instead.
- Avoid vague verbs that say nothing about the effect: `process`, `handle`, `do`, `manage`, `perform`,
  `execute`. Use one only when the function really is a generic dispatcher, and say what it
  dispatches (`handleKeyPress`, not `handle`).

### Say what the argument is

Put the key after the verb phrase, with the preposition that fits:

- `At` — a position or index: `findSymbolAt(index, offset)`, `collectVisibleLocalsAt(…)`
- `Of` — the thing a result belongs to or is derived from: `findNameRangeOf(node)`,
  `collectQualifiedPathOf(index, symbol)`, `findAssignmentTargetOf(assignmentId)`
- `For` — the purpose or recipient: `createPositionsFor(text)`, `collectSignaturesFor(nameToken, hasReceiver)`
- `In` — a container to search: `resolveInSourceFile(file, index, node)`
- `From` — a source to read or convert from

Prefer the preposition over a longer verb phrase, and don't repeat the argument's type in the name
when the parameter already says it.

### Predicates

A function that returns `Boolean` states a condition as a sentence about its subject:

- `is…` — state or classification: `isOperator(…)`
- `has…` — possession or presence: `hasRange()`
- `can…` — capability or permission: `canFindUsagesFor(element)` (a platform name; see below)
- `does…` — a relationship between the arguments, or between the subject and one of them, when
  `is`/`has`/`can` don't read naturally: `doesRangeContain(line, column, startLine, …)`,
  `doesNodeContain(line, column, stage)`, `doesPathMatch(first, second)`,
  `doesReferenceResolveTo(reference, targetFile, targetOffset)`, `doesStartBeforeCaret(o)`,
  `doesLoopContainCaret(loop)`
- `should…` — a policy decision

Never name a predicate after the thing it tests (`samePath`, `startsBeforeCaret`). A predicate is never
negated in its name (`isNotEmpty` is a stdlib exception; write `isEmpty` and negate at the call).

### Constructors and factories

A constructor is named by its type. A function that makes an instance of a type is a `create…`
function (`createPositionsFor(text)`); one that builds a value from other values is `build…`
(`buildAstIndex(file)`); one that converts is `to…` (`toOffset(line, column)`). Service accessors
on a companion object are `getInstance()`. Static-style factories on a companion object may use
`of`/`from` only where the standard library or the platform does.

### Extension functions

Name an extension as if it were a member of its receiver, so the call site reads as a sentence:
`json.getStringOrNull(key)`, `json.getObjectOrNull(key)`, `o.hasRange()`, `diagnostic.toTextRange(document)`,
`element.asStringOrNull()`. The receiver is the subject; the name is the verb phrase.

### Properties

Properties (`val`/`var`) are nouns or adjectives, not verbs; this convention is about functions
only. A function that merely returns a stored field is a property (`AstRoot.roots`). Use a function
when the work is not trivial or the call can fail (`collectOwnModules()` filters that list).

### Tests

A test function's name is `test` followed by a sentence in the present tense that states the
behaviour being checked, subject first:

- `testSameNamedFunctionsInDifferentModulesHaveSeparateUsages`
- `testNothingIsSuggestedAsALocalAfterADot`
- `testCaretOnADeclarationsOwnNameDoesNotFollowItsTypeEdge`

This holds for JUnit 3 style and JUnit 5 `@Test` methods alike. Test helpers follow the rules above,
not the sentence form: `configureAndCache(textWithCaret, modules)`,
`collectUsageOffsetsAtCaret(sourceWithCaret)`, `buildPipelineSource(vertexBody)`,
`assertCompletions(source, present, absent)`.

### Names the platform dictates

A function that overrides or implements a method of a library or framework interface keeps that
method's name, even when it is not a verb phrase: `isModified` and `actionPerformed`, `canFindUsagesFor`,
`toString`, and the program's `main`. Everything else the project defines follows the rules above.
A thin wrapper that exists only to give a dictated name a verb-phrase alias should not be written:
call the dictated name.

### Sharing helpers

A helper is written once. If a second file needs the same helper, move it to a shared file and have
both call it; do not copy a private function into another file, even a small one.

- **Same job, same name, one definition.** Two private functions with the same name in different
  files are a sign that a shared helper is missing. A helper that does the same thing under two
  different names is the same problem and gets the same fix.
- **Where it goes.** A helper that belongs to one concept lives next to that concept. A helper that
  is generic, such as reading a typed value out of a JSON object (`BwslJson.kt`), goes in a small
  shared file named for what it offers, visible to the package (`internal`), not `public`.
- **Tests share too.** Setup that several test classes repeat belongs in a shared test helper or a
  common base class, not in a private copy per class: `BwslAstFixtureTestCase.configureAndCache`
  for fixture setup, `BwslcAstHelper.buildIndex` for building an index from real compiler output.
  A test helper follows the same naming rules as any other function.
- **Same name, different job is allowed only across unrelated types.** A function name is reused
  when the functions are members of different types and mean the same kind of thing for each. Two
  free functions or private helpers that share a name but behave differently should be renamed so
  the difference shows.
- **Duplicate test names.** A test name may repeat across test classes only when the classes test
  different layers and the behaviour really differs, as `testFragmentInputResolvesToVertexOutputAssignment`
  does in the resolver test (the resolver) and the reference test (the PSI reference); otherwise
  delete the duplicate.

### Exception: builders for declarative tables

A private builder that exists only to write the rows of a long, declarative table of data may have a
very short name (one or two letters) instead of a verb phrase, when the full name would make the
table unreadable. Every row calls it, so a long name repeats dozens or hundreds of times and hides
the data. The intrinsic function table in `BwslIntrinsics.kt` is the case: `p(type, name)` builds a
parameter and `fn(name, ret, …)` builds an intrinsic function.

- It is `private` to the file that holds the table, and used for nothing else.
- A comment directly above it says what each builder builds.
- It is the only exception to the verb-phrase rule for functions the project defines; a helper that
  is used outside such a table is named in the normal way.

### Choosing good names

- Name the **effect** or the **result**, not the mechanism: `findSymbolAt`, not
  `loopOverNodesAndCompare`.
- Be as specific as the call site needs and no more. A longer name is better than a name that
  needs its body to be understood, but don't restate the receiver or the parameter types.
- Keep one verb per concept across the codebase: lookups that may fail are `find…` everywhere
  (`findRoot`, `findSymbolAt`, `findNodeAtOffset`), never `lookup…`, `search…` or `locate…` for the
  same contract.
- Do not abbreviate. A name is read far more often than it is written.
