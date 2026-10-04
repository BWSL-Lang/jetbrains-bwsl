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
| Look something up that may not exist | `find` | Returns `null` (or an empty collection for a plural) when there is no match. Never throws for "not found". | `findDeclarationAt(offset)`, `findEnclosingBlock(position)` |
| Read something that is always there | `get` | Cheap, no search, never null. Use for accessors and simple derivations of a field. | `getName()`, `getSourceText()` |
| Gather every item that matches | `collect` | Returns a list (possibly empty), in a stable, documented order. | `collectUsagesOf(declaration)`, `collectVisibleNames(position)` |
| Follow a reference to what it points at | `resolve` | Returns what the reference points at, or nothing when it points at nothing. | `resolveReference(reference)` |
| Work a value out | `compute` / `deduce` | `compute` for a calculation from the arguments alone; `deduce` for inferring something that is not stated. | `computeLineStarts(text)`, `deduceType(expression)` |
| Assemble a new structure from parts | `build` | Returns the finished value; the caller owns it. | `buildLookupTable(entries)`, `buildSignature(function)` |
| Make a new instance of a type | `create` | Always returns a new object. | `createWidget()`, `createConnection(address)` |
| Convert one representation into another | `to` | Pure; the result carries the same information in the other form. | `toRange(span)`, `toOffset(position)` |
| Present a cheap view of a value as another type | `as` | Does not copy; may fail with `OrNull`. | `asList()`, `asNumberOrNull()` |
| Turn a value into text for a person | `render` / `format` | `render` produces markup or a document; `format` produces a short string. | `renderDocumentation(symbol)`, `formatTypeName(type)` |
| Walk a structure | `visit` | Calls back for each node; returns nothing useful. | `visitChildren(node)` |
| Run an action with side effects | a specific verb for the effect | Name the effect, not just that something happens. | `uploadReport()`, `sendReminder(message)` |
| Change state | `add` / `remove` / `update` / `set` / `clear` / `register` | `set` replaces a value; `update` changes it based on its old value. | `addItem(item)`, `registerListener(listener)` |
| Check a condition (returns `Boolean`) | `is` / `has` / `can` / `does` / `should` | See "Predicates" below. | `isDeclaration(node)`, `hasChildren(node)` |
| Load or store | `load` / `save` / `read` / `write` | Reading and writing something outside the process or its cache. | `loadConfiguration()`, `readFile(path)` |
| Verify | `check` / `verify` / `assert` | `check` returns a result describing what is wrong; `assert` throws. | `checkSyntax(text)` |

When two verbs seem to fit, pick the one whose contract matches what the function does:

- `find` searches and can come back empty; `get` does not search and cannot.
- `collect` returns *all* matches; `find` returns *one* (or the first).
- `build` assembles from parts and `create` instantiates; if the function does both, it is `build`.
- `compute` and `deduce` are pure; if there are side effects, name the effect instead.
- Avoid vague verbs that say nothing about the effect: `process`, `handle`, `do`, `manage`, `perform`,
  `execute`. Use one only when the function really is a generic dispatcher, and say what it
  dispatches (`handleKeyPress`, not `handle`).

### Say what the argument is

Put the key after the verb phrase, with the preposition that fits:

- `At` — a position or index: `findDeclarationAt(offset)`
- `Of` — the thing a result belongs to or is derived from: `collectUsagesOf(declaration)`
- `For` — the purpose or recipient: `buildDocumentationFor(symbol)`
- `In` — a container to search: `findIdentifierIn(range)`
- `From` — a source to read or convert from: `readNameFrom(node)`

Prefer the preposition over a longer verb phrase, and don't repeat the argument's type in the name
when the parameter already says it.

### Predicates

A function that returns `Boolean` states a condition as a sentence about its subject:

- `is…` — state or classification: `isDeclaration(node)`, `isEmpty()`
- `has…` — possession or presence: `hasChildren(node)`, `hasParent(node)`
- `can…` — capability or permission: `canParse(input)`
- `does…` — a relationship between the arguments, when `is`/`has`/`can` don't read naturally:
  `doesRangeContain(range, offset)`, `doesNameMatch(candidate, target)`
- `should…` — a policy decision: `shouldReportWarning(finding)`

Never name a predicate after the thing it tests (`sameFile`, `startsEarlier`). A predicate is never
negated in its name (`isNotEmpty` is a stdlib exception; write `isEmpty` and negate at the call).

### Constructors and factories

A constructor is named by its type. A function that makes an instance of a type is a `create…`
function; one that builds a value from other values is `build…`; one that converts is `to…`.
Static-style factories on a companion object may use `of`/`from` only where the standard library
or the platform does.

### Extension functions

Name an extension as if it were a member of its receiver, so the call site reads as a sentence:
`node.findParent(kind)`, `table.getString(key)`, `text.splitIntoLines()`. The receiver is the subject;
the name is the verb phrase.

### Properties

Properties (`val`/`var`) are nouns or adjectives, not verbs; this convention is about functions
only. A function that merely returns a stored field is a property. Use a function when the work is
not trivial or the call can fail.

### Tests

A test function's name is `test` followed by a sentence in the present tense that states the
behaviour being checked, subject first:

- `testEmptyInputProducesNoTokens`
- `testExpiredEntryIsNotReturned`

Test helpers follow the rules above (`createSampleFile`, `collectMatchOffsets`), not the sentence form.

### Names the platform dictates

A function that overrides or implements a method of a library or framework interface keeps that
method's name, even when it is not a verb phrase (a framework's `toString`, `compareTo` or
`onEvent`-style callback, or the program's `main`). Everything else the project defines follows the rules
above. A thin wrapper that exists only to give a dictated name a verb-phrase alias should not be
written: call the dictated name.

### Sharing helpers

A helper is written once. If a second file needs the same helper, move it to a shared file and have
both call it; do not copy a private function into another file, even a small one.

- **Same job, same name, one definition.** Two private functions with the same name in different
  files are a sign that a shared helper is missing. A helper that does the same thing under two
  different names (`formatLabel` in one file, `makeLabel` in another) is the same problem and gets the same fix.
- **Where it goes.** A helper that belongs to one concept lives next to that concept. A helper that
  is generic, such as reading a typed value out of a JSON object, goes in a small shared file named
  for what it offers, visible to the package (`internal`), not `public`.
- **Tests share too.** Setup that several test classes repeat belongs in a shared test helper or a
  common base class, not in a private copy per class. A test helper follows the same naming rules as
  any other function.
- **Same name, different job is allowed only across unrelated types.** A function name is reused
  when the functions are members of different types and mean the same kind of thing for each. Two
  free functions or private helpers that share a name but behave differently should be renamed so
  the difference shows.
- **Duplicate test names.** A test name may repeat across test classes only when the classes test
  different layers and the behaviour really differs; otherwise delete the duplicate.

### Choosing good names

- Name the **effect** or the **result**, not the mechanism: `findDeclarationAt`, not
  `loopOverNodesAndCompare`.
- Be as specific as the call site needs and no more. A longer name is better than a name that
  needs its body to be understood, but don't restate the receiver or the parameter types.
- Keep one verb per concept across the codebase: if lookups that may fail are `find…` in one place,
  they are `find…` everywhere, never `lookup…`, `search…` or `locate…` for the same contract.
- Do not abbreviate. A name is read far more often than it is written.
