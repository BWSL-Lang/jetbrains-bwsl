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

### Compiler diagnostics

- **Errors and warnings in the editor**, from `bwslc`'s JSON diagnostics, with
  the compiler's source locations
- Checks the text in the editor, so **unsaved edits are validated** too
- Honours the configured **module paths**, so imports resolve as they do when
  you compile
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
- **`attributes.` members** — the attributes the pass uses
- **`input.` members** in a fragment stage — the vertex stage's outputs
- **`extends`** after a submodule's name

### Settings

**Settings → BWSL**

- **Compiler path**, with a **Download Latest…** button that fetches the right
  `bwslc` for your OS and architecture from the BWSL GitHub releases
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
- [ ] Code formatter, auto-indent and **Reformat Code**
- [ ] Comment / uncomment with the comment shortcut
- [ ] Brace and quote matching and auto-closing
- [ ] Code folding (blocks, functions, comments)
- [ ] Live templates and snippets for common shapes (`pipeline`, `pass`,
      functions, loops)
- [ ] Extend selection and smart Enter

**Code insight**
- [ ] Unresolved-reference highlighting
- [ ] Inspections and quick fixes (unused variables and parameters, unused
      imports, missing import for a used module)
- [ ] Auto-import when completing a name from a module that isn't imported yet
- [ ] Inlay hints: parameter names at call sites, inferred types, array lengths
- [ ] Semantic highlighting that colours parameters, locals, fields and
      constants differently
- [ ] Documentation comments shown in hover docs
- [ ] Spell-checking of comments and strings

**Completion**
- [ ] Function, struct and module names, including `Mod::` members
- [ ] Struct fields and swizzles after `.`, and fields inside methods
- [ ] Constants and functions declared in the enclosing module, pass or pipeline
- [ ] Completion that sees code typed since the last save
- [ ] Argument-aware ranking and smart completion by expected type

**Navigation and search**
- [ ] Go to Symbol / Go to Class / Search Everywhere for functions, structs,
      modules and passes
- [ ] File Structure view and breadcrumbs
- [ ] Go to Type Declaration
- [ ] Call hierarchy
- [ ] Navigation into the compiler's built-in standard modules (`stdlib://`
      sources)
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
- [ ] Notice a newer `bwslc` release and offer to update

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
