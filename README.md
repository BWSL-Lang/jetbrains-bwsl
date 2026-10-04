# BWSL JetBrains Plugin

Language support for the [BWSL shader language](https://www.bwsl.dev/) in
IntelliJ-based IDEs: highlighting, compiler diagnostics, navigation, find
usages, documentation, parameter info and completion.

Most of it is driven by the `bwslc` compiler itself: the plugin asks the
compiler what a name refers to instead of re-implementing BWSL's scoping rules.
That means the compiler has to be configured (see [Setup](#setup)).

## Features

### Editing

- **Syntax highlighting** — block keywords, control-flow keywords, types,
  decorators, intrinsic functions, function declarations and calls, module
  declarations and qualifiers, strings, numbers, comments, operators and
  brackets
- **Color scheme customisation** — **Settings → Editor → Color Scheme → BWSL**
- **File icons** — `.bwsl` files get one icon for pipeline files and another for
  module files

### Brackets, quotes, comments and folding

- **Comment with Line Comment** (Ctrl+/) toggles `//` on the caret's line or
  every selected line, and **Comment with Block Comment** (Ctrl+Shift+/)
  wraps a selection in `/* */`
- **Bracket matching**: the partner of a `{ }`, `( )` or `[ ]` next to the caret
  is highlighted and **Move Caret to Matching Brace** jumps to it. `<` and `>`
  are not paired, since they are also comparisons
- **Auto-closing**: typing `{`, `(`, `[` or `"` inserts the closing one, and
  typing the closing one steps over it. No closing bracket is inserted when an
  identifier or number follows. Enter between `{` and `}` puts the caret on an
  indented line with the `}` below it
- **Code folding** of every `{ ... }` block that spans lines (functions,
  structs, passes, stages, loops and `if` bodies), of a multi-line `/* */`
  comment, and of a run of two or more `//` lines, which folds to its first
  line. It works from the text, so it does not need the file to compile

### Spell-checking

- Typos in **comments** (`//` and `/* */`) and **string literals** are
  reported by the IDE's spell checker, with its usual quick fixes (rename, save
  to dictionary, ignore). Names (identifiers, functions, types) are not checked
- A small bundled dictionary knows the vocabulary of shaders and the compiler
  (`bwslc`, `SPIRV`, `GLSL`, `swizzle`, `Fresnel`, `GGX`, ...)

### Formatting (Ctrl+Alt+L)

- **Reformat Code** for a file or a selection. It re-indents every line by how
  deeply it is nested, and normalises the spaces between tokens where the
  meaning is clear: after commas, around assignments, comparisons, `&&`, `||`
  and `->`, around binary `+ - * / %`, before `{`, inside `( )` and `[ ]`, and
  in `name :: (...)`. It never moves a token to another line or changes one,
  so the program means what it meant; this is checked against all 1,045 files
  in the compiler's own repository
- Understands braceless bodies: a statement under `if (...)`, `else`, `for`,
  `loop` and the like is indented one level, and an `else` lines up with the
  `if` it belongs to. A body on the same line as its header stays there
- `switch`: `case` and `default` labels sit one level inside the `switch`, and
  the statements after a label are indented one more until the next label or
  the closing `}`.
- Continued expressions are indented: a line after an operator, one that
  starts with an operator, and lines inside open `( )` or `[ ]`
- **Auto-indent**: Enter indents the next line (a level after `{` or a
  braceless header, the same level after `;`), and typing `}` at the start of a
  line moves it to the indent of the line that opened its block
- Left alone on purpose: signs (`-x`), `<` and `>` (a comparison or a
  generic's brackets), `:`, `?`, `^` (an operator or a pointer), `..`, blank
  lines and trailing comments. Line breaks are kept as written
- **Settings → Editor → Code Style → BWSL**: indent size (4 by default),
  continuation indent (4), tabs or spaces, and how many blank lines to keep

### Compiler diagnostics

- **Errors and warnings in the editor**, from `bwslc`'s JSON diagnostics, with
  the compiler's source locations
- Checks the text in the editor, so **unsaved edits are validated** too
- Honours the configured **module paths**, so imports resolve as they do when
  you compile
- **Weak warning when a declaration shadows another**: a parameter, local,
  constant or loop variable that reuses the name of one already in scope (a
  parameter or an enclosing local, a loop variable, or a module-, pipeline- or
  pass-level constant). bwslc allows shadowing without comment for now [See #103](https://github.com/BWSL-Lang/BWSL/issues/103)
- **Compile BWSL File** action (editor context menu, project view, **Tools**
  menu) for files that contain a pipeline, with a configurable output format and
  directory

### Navigation (Ctrl+click)

Every reference resolves through the compiler's own reference index, so scoping
is exactly the compiler's:

- variables, parameters, constants and locals to their declarations
- function and method calls (unqualified, `recv.f()` and `Mod::f()`), including
  same-named functions in different modules, structs and passes
- declared types, return types and `Mod::Type` qualifiers, to the struct and the
  module
- struct fields through member access
- `import` and `using` names, and `Mod::` qualifiers, to the module declaration
  — in another file when the module lives there
- `use attributes { … }` names and `attributes.x` to the attribute declaration
- fragment `output.x` to its entry in the pass's `outputs { … }` block, and
  `input.x` to the vertex stage's `output.x` assignment
- uses of a constant, including ones the compiler folded into a literal
- **the compiler's standard modules** (`Math`, `Color`, ...), which are
  embedded in `bwslc` and have no file on disk. The compiler names each one's
  source on GitHub, pinned to its release tag (or `master` for a development
  build). When a file that uses one has been compiled, the plugin fetches that
  directory in the background into a read-only cache in the IDE's system
  directory, and Ctrl+click opens the copy. A tagged version is fetched once, a
  branch once per session. Find Usages works from a standard declaration, and
  Rename is not offered on one. It needs a network connection the first time;
  until a file has been fetched, or if the copy no longer matches the compiler
  (a position that does not hold the name is not trusted), nothing happens
  instead of a guess

### Find Usages

- On any declaration: functions, methods, structs, fields, parameters, locals,
  constants, attributes, fragment outputs, modules and stage values
  (`output.uv` / `input.uv`, which have no declaration of their own: the
  vertex stage's first assignment stands in for it)
- Works from the declaration's name or from any use of it
- Finds usages across every BWSL file in the project and in the module paths,
  including files you have never opened. It waits for any file that is not up to
  date before it searches (see [Project index](#project-index))

### Project index

- When a project opens, the plugin compiles every BWSL file in the project and
  in the module paths, so the compiler's view covers all of them
- After that it only recompiles what a change affects. Each file's result
  records the files it was built from: itself and the modules it imports.
  Whenever a `.bwsl` file or `bwsl.json` changes, the plugin checks those
  records and recompiles only the files whose inputs changed. Editing a module
  recompiles that module and the files that import it, and nothing else
- A file that doesn't compile is not retried until a file it could have read
  changes: the file itself, a `.bwsl` file beside it, or one in a module path.
  Without a result there is no list of what it imports, so it can be retried
  once for a change to an unrelated neighbour
- Optional **`bwsl.json`** in the project root, to share the build configuration
  with the team:

  ```json
  {
    "modulePaths": ["shaders/lib"],
    "exclude": ["scratch", "vendor/old.bwsl"]
  }
  ```

  `modulePaths` are added to the module paths in the IDE settings (relative to
  the project root). `exclude` lists files and directories the index leaves
  out, such as shaders that are not meant to compile on their own

### Rename (Shift+F6)

- Renames a declaration and every usage in one step: functions, methods,
  structs, fields, parameters, locals, constants, attributes, fragment outputs,
  modules and stage values (renaming `output.uv` renames the vertex stage's
  assignments and the fragment stage's `input.uv` reads together)
- Works from the declaration's name or from any use of it, and respects scope: a
  parameter renamed in one function is untouched in another, and so are
  same-named functions in other modules
- Renaming a module that lives in a file of the same name **renames the file
  too**, since the compiler finds a module by its file name
- Rejects names that aren't plain identifiers, such as keywords and type names
- Brings the project index up to date first (behind a progress dialog, only when
  something is stale), so usages in files you never opened are renamed too,
  including module files outside the project
- **Checks the new name with the compiler** before changing anything, and
  lists what it finds in the platform's conflicts dialog, where you can still
  go ahead. It applies the rename to a temporary copy of the sources and
  compares that with an unrenamed copy, so it reports:
  - errors the rename would introduce: a duplicate declaration in one scope, an
    overload that already exists, a stage value whose type conflicts with the
    one it would be merged into, or any other rule the compiler enforces (the
    first attribute has to be called `position`, for one)
  - names that would silently mean something else: a local renamed to the name
    of a parameter, which would then capture the parameter's uses
  - stage values that would be merged, when the new name is already one in the
    same pass

  The check needs the compiler, and is skipped when it can't be run
- Refuses, with an explanation, when the compiler's view of any project file is
  not current: it has unsaved changes, has changed since the compiler last
  checked it, has never been compiled, or doesn't compile. A rename from a stale
  view would leave a usage pointing at the old name. Exclude a file that can't
  compile on its own in `bwsl.json`

### Quick documentation (hover / Ctrl+Q)

- **Variables, parameters, constants and fields** — declared type and kind
- **Functions and methods** — qualified name (`Module::Struct::name()`) and
  signature, including calls into other files
- **Documentation comments** — the `///` lines (or `/** */` block) written
  directly above a function, constant, struct, enum or field are shown in its
  popup, for a call and for the declaration itself. Blank lines separate
  paragraphs, text in `backticks` is code, and web addresses are links. A
  doc comment in another file is shown for a call into it, and so is one in a
  standard module once its source has been fetched. A blank line or an
  ordinary `//` comment between the comment and the declaration means it is not
  about it
- **Intrinsics** — signature and description
- **`attributes`, `input` and `output`** — the attributes used in the pass, the
  vertex outputs and their interpolation (`@flat`, `@noperspective`), with the
  types the compiler inferred
- **`attributes.x`, `input.x`, `output.x` members** — type and kind (vertex
  attribute, vertex output, fragment output)

### Parameter info (Ctrl+P)

- Signatures for intrinsics, and for your own functions and methods
- Highlights the current parameter

### Code completion

- **Context-aware keywords** — only what is valid at the position:
  `module`/`pipeline` at the top level,
  `attributes`/`resources`/`variants`/`pass` in a pipeline,
  `vertex`/`fragment`/`compute` in a pass, statements in function and stage
  bodies
- **Types** and **intrinsics**, in the places they are valid
- **Locals, parameters, constants and loop variables** that are in scope, sorted
  first, with their type
- **Functions, structs, enums and imported modules**, sorted after the locals:
  what the enclosing module, pipeline and pass declare, shown with return type
  and parameters (a function is inserted with its parentheses). A module name
  continues with `::`
- **`Module::` members** — the functions, structs, enums and constants of the
  module (an `import ... as` alias works too), or the values of an enum after
  `Enum::`. Nothing else is offered there, also when the caret is inside an
  existing call name
- **Auto-import** — press Ctrl+Space a second time to also see the functions,
  structs, enums and constants of modules the file does not import yet (the
  module files of the project and the module paths, and the compiler's
  standard modules once they have been fetched). Choosing one writes
  `Module::name` and adds `import Module` to the module or pipeline you are in,
  after its last import. After typing `Module::` for a module that is not
  imported, its members are offered at once and choosing one adds the import
- **`using`** makes a module's functions and constants available without a
  qualifier, so they are suggested too
- **After `import`**, the modules that can be imported: the standard modules
  (once they have been fetched, see below), the module files in the project and
  the module paths, and the other modules of the file. After `using`, the
  modules and aliases the file imports
- **After a `.`** — what the value before it has: a struct's **fields and
  methods**, a vector's **swizzles**, an array's `length`. A swizzle is offered
  for each component (`x y z w`, `r g b a`) and as the prefixes `xy`, `xyz`,
  `rgb`, ...; once you have typed `xy` it is extended with each component that
  can follow (the two families are not mixed, and a `float3` has no `w`). The
  value can be a name, `self`, a call, a constructor, a field, a method call
  or an index, in any chain: `lights[1].color.`, `makeLight().`, `m[1].` (a
  matrix column). Keywords and type names are no longer offered after a dot
- **Inside a struct's methods** its own fields and methods are suggested
  without a qualifier
- **`attributes.` members** — the attributes the pass uses
- **`input.` members** in a fragment stage — the vertex stage's outputs
- **`extends`** after a submodule's name

Which type a value has comes from the last compile, and the expression before
the dot is read from the text. A value that is a parenthesised or arithmetic
expression has no type here, and nothing is guessed. The compiler records no
array size for a function parameter, so `length` is not offered on one.

These names come from the last compile of the saved file, like the locals: a
function typed since then is offered once the file has been saved and compiled.
Aliases and imports are read from the text, so they are current.

### Settings

**Settings → BWSL**

- **Compiler path**, with a **Download Latest…** button that fetches the right
  `bwslc` for your OS and architecture from the BWSL GitHub releases
- **Check for a newer compiler release on startup** — on by default. When a
  project opens (at most once a day, in the background) the plugin asks GitHub
  for the newest `bwslc` release and compares it with your compiler's version.
  If yours is older, a notification offers **Update** (downloads it, makes it
  the compiler path) or **Skip this version**. A development build (`0.0.0-dev`)
  is not compared and never nagged. **Tools → Check for BWSL Compiler Update**
  asks right away and says what it found
- **Module paths** — the directories passed to `bwslc` as `-modules`
- **Output format** — SPIR-V, all formats, Metal, HLSL, GLSL 450 or GLSL ES /
  WebGL
- **Output directory** — defaults to the source file's directory

## How it behaves

- Navigation, find usages, documentation, parameter info and the locals in
  completion read the compiler's AST for the **saved** file. After you edit a
  file the AST is refreshed in the background; until it has been saved and
  re-checked, those features reflect the last successful compile.
- A file that doesn't compile keeps its previous AST, so navigation keeps
  working while you type.
- Find usages and rename search the project index, and both bring it up to date
  first (behind a progress dialog, only when something is stale), so files the
  background pass has not reached yet are still covered. Highlighting usages in
  the editor does not wait.
- Files that are not on the local disk, or that `bwsl.json` excludes, are not
  indexed.
- With no compiler configured, or a file the compiler has never been able to
  read, those features return nothing rather than guessing from the text.
  Highlighting and keyword completion don't depend on the compiler.

## Roadmap

Not implemented yet:

**Refactoring and editing**
- [ ] Formatter options beyond indentation and spacing: brace placement,
      wrapping long lines and aligning continued expressions
- [ ] Live templates and snippets for common shapes (`pipeline`, `pass`,
      functions, loops)
- [ ] Extend selection and smart Enter

**Code insight**
- [ ] Unresolved-reference highlighting
- [ ] Inspections and quick fixes (unused variables and parameters, unused
      imports, missing import for a used module)
- [ ] Inlay hints: parameter names at call sites, inferred types, array lengths
- [ ] Semantic highlighting that colours parameters, locals, fields and
      constants differently

**Completion**
- [ ] Completion that sees code typed since the last save
- [ ] Argument-aware ranking and smart completion by expected type

**Navigation and search**
- [ ] Go to Symbol / Go to Class / Search Everywhere for functions, structs,
      modules and passes
- [ ] File Structure view and breadcrumbs
- [ ] Go to Type Declaration
- [ ] Call hierarchy
- [ ] Ctrl+click on intrinsics, with their documentation

**Compiler integration**
- [ ] Validate and analyse the editor's unsaved text for navigation and
      completion (today only diagnostics do; `bwslc` has a `-stdin` mode worth
      evaluating)
- [ ] Problems tool window entries and compiler output with clickable locations
- [ ] Run configurations and compile-on-save
- [ ] Per-project compiler path (module paths can already be set per project in
      `bwsl.json`)
- [ ] Show the compiled output (SPIR-V disassembly, GLSL, HLSL, Metal) next to
      the source
- [ ] Shader variant selection in the compile action

**Platform and ecosystem**
- [ ] BWSL code blocks highlighted inside Markdown
- [ ] A Language Server (LSP) front end for other editors
- [ ] Debugger integration

## Prerequisites

| Tool          | Version             |
|---------------|---------------------|
| IntelliJ IDEA | 2026.2+             |
| JDK           | 21+                 |
| Gradle        | 9.5.1 (via wrapper) |

## Setup

After installing the plugin, point it at the compiler:

**Settings → BWSL → Compiler path** — select the `bwslc` executable, or press
**Download Latest…** to fetch one.

Add any directories you import modules from under **Module paths**, or list them
in a `bwsl.json` in the project root (see [Project index](#project-index)).

## Building

### Build the plugin

```
./gradlew buildPlugin
```

Output: `build/distributions/bwsl-jetbrains-plugin-<version>.zip`

### Run in a sandbox IDE

```
./gradlew runIde
```

Opens a fresh IntelliJ IDEA instance with the plugin installed. Open any `.bwsl`
file to test.

### Install manually

1. Build the plugin zip (see above).
2. In IntelliJ IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the zip and restart.

### Test

```
./gradlew test --no-configuration-cache --rerun-tasks
```

`--rerun-tasks` is required: without it Gradle often skips the tests. The tests
run the real `bwslc` compiler. By default Gradle downloads one into
`build/bwslc`; to use your own build, set `bwslc=<path to bwslc>` in the
gitignored `local.properties` (for example `bwslc=C:/path/to/bwslc.exe`).

## Code generation

### `src/main/resources/BwslLexer.flex`

A [JFlex](https://jflex.de/) lexer definition. The `generateLexer` Gradle task
compiles it to `generated/com/bwsl/plugin/_BwslLexer.java`, which is the
tokeniser used for syntax highlighting. Token type constants are defined in
`BwslTokenTypes.kt`.
