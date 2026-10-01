# bwslc gaps

What `bwslc <file> -ast-json` (schema `bwsl.ast.v3`) doesn't give the
plugin, and what it does instead in the meantime. Only compiler/AST-side gaps are listed. Each entry
says what to ask for and what would go away on the plugin side if it were fixed.

## Worth asking for

### 1. Function return types have no position, and a qualified one has no edge at all
A function's return type is only a string, `returnType`, with no `line`/`column`. Two consequences,
both in `manual_ast_test_files/return_type_position.bwsl`:

- `plain :: () -> Local` has a `FUNCTION -> STRUCT_DECL [return-type]` edge, but nothing says where
  `Local` is written, so a caret on the return type text can't be mapped to the edge.
- `qualified :: () -> Common::Box` has **no edge** at all (not even to the struct), and `returnType`
  reads just `"Box"`, dropping the `Common::`. Variables, parameters and struct fields get a
  positioned `typeQualifier` and a `qualifier` edge to the module; return types get neither.

The plugin resolves nothing for a return type's text: no edge it can reach, and no lexical
fallback.

**Ask:** `returnTypeLine`/`returnTypeColumn` on functions (and the qualified `returnType` as
written), a positioned `typeQualifier` for it, and the `return-type`/`qualifier` edges for the
qualified form.
**Removes:** nothing to delete; it adds navigation from return types that doesn't exist today.

### 2. A stage-interface value has no type when it is assigned an intrinsic call
`output.unit = normalize(attributes.uv);` produces a `PASS:0/interface:unit` symbol with no
`type` key at all, and nothing else in the payload says what `normalize` returned: the assigned
`FUNCTION_CALL` node has no type, and its only edge is `call -> builtin:function:normalize`.
Values assigned an operator expression (`attributes.uv * 2.0`), a constructor, or an attribute are
typed (`float2`). See `manual_ast_test_files/stage_value_type.bwsl`; the same happens for
`dot(...)`.

Hover docs for `input.unit` / `output.unit` therefore show `?` as the type.

**Ask:** a `type` on the symbol (and ideally a type on every expression node) for these values.
**Removes:** nothing to delete; it fills in the `?`.
