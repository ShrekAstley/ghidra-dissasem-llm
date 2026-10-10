# Tool reference

The model can only act through the tools below. This is the **complete** list: there is no shell,
no process execution, no filesystem or network tool. Each tool declares a *permission class*:

| Permission | Meaning |
|---|---|
| `READ_PROGRAM` | Read-only inspection of the open Ghidra program. Always granted. |
| `PROPOSE_CHANGE` | Queues a proposal for the Proposals tab. **Never modifies the program.** Can be revoked in Settings. |
| `LOCAL_KNOWLEDGE` | Reads/writes the local SQLite notes database (not the Ghidra program). Can be revoked in Settings. |

Arguments are validated against each tool's declared schema (types, ranges, enums, unknown keys
rejected) before anything executes. Results are truncated to *Max tool result chars*.

Anywhere a `function` argument is accepted you may pass a name, an address (`0x401000`), or
`current` (the function under the cursor). Omitting it also means `current`.

## Tools

| Tool | Permission | Signature (`?` = optional) | Description |
|---|---|---|---|
| `get_current_function` | READ_PROGRAM | get_current_function() | Describe the function containing the user's current cursor location in Ghidra, with its signature, size and call relationships. |
| `get_function` | READ_PROGRAM | get_function(function?: string) | Get an overview of a function: name, address range, signature, size, caller/callee counts and comments. |
| `get_function_signature` | READ_PROGRAM | get_function_signature(function?: string) | Get a function's prototype, calling convention, parameters (with storage) and return type. |
| `get_function_decompile` | READ_PROGRAM | get_function_decompile(function?: string) | Get Ghidra's decompiled C for a function. NOTE: decompiler output can be inaccurate (types, variable merging, missed control flow); cross-check against the assembly. |
| `get_function_assembly` | READ_PROGRAM | get_function_assembly(function?: string, start?: integer, max_instructions?: integer) | Get disassembly of a function with symbolic annotations for calls, strings and globals. |
| `get_function_variables` | READ_PROGRAM | get_function_variables(function?: string) | List a function's parameters and local variables, using the exact names shown in the decompiler (use these names for propose_rename_variable). |
| `get_function_at_address` | READ_PROGRAM | get_function_at_address(address: string) | Find the function containing an address (or report that none does). |
| `get_callers` | READ_PROGRAM | get_callers(function?: string, limit?: integer) | List functions that call the given function, with call-site addresses. |
| `get_callees` | READ_PROGRAM | get_callees(function?: string, limit?: integer) | List functions called by the given function (direct calls only; indirect calls are listed separately). |
| `get_call_graph` | READ_PROGRAM | get_call_graph(function?: string, direction?: string, depth?: integer) | Show the call graph around a function as an indented tree (bounded depth and size). |
| `search_functions` | READ_PROGRAM | search_functions(query: string, min_instructions?: integer, limit?: integer) | Search functions by name substring or regex (case-insensitive). Optionally filter by minimum instruction count. |
| `get_entry_points` | READ_PROGRAM | get_entry_points() | List program entry points and conventional start functions (entry, main, WinMain, DllMain, _start, ...). |
| `get_xrefs_to` | READ_PROGRAM | get_xrefs_to(target: string, limit?: integer) | List cross-references TO an address or symbol (who calls/reads/writes/points at it). |
| `get_xrefs_from` | READ_PROGRAM | get_xrefs_from(source: string, function_wide?: boolean, limit?: integer) | List references FROM an instruction address, or (function_wide=true) from every instruction of the function containing it. |
| `get_referenced_strings` | READ_PROGRAM | get_referenced_strings(function?: string, limit?: integer) | List string literals referenced by a function (deterministic: derived from Ghidra references). |
| `get_referenced_globals` | READ_PROGRAM | get_referenced_globals(function?: string, limit?: integer) | List global data (non-string) referenced by a function, with read/write direction. |
| `get_string` | READ_PROGRAM | get_string(address: string) | Read the string at an address (defined string data, or a printable C string in memory) and list where it is referenced. |
| `search_strings` | READ_PROGRAM | search_strings(query: string, limit?: integer) | Search defined strings by substring/regex (case-insensitive). Returns address, value and reference count. |
| `search_symbols` | READ_PROGRAM | search_symbols(query: string, kind?: string, limit?: integer) | Search symbols (functions, labels, imports, globals) by substring; wildcards * and ? supported. |
| `get_global` | READ_PROGRAM | get_global(target: string) | Describe a global variable/data item: its type, size, current value representation, and the functions that read or write it. |
| `get_memory` | READ_PROGRAM | get_memory(address?: string) | Describe the memory map (blocks, permissions) or, with an address, the block/code unit it belongs to. |
| `read_memory` | READ_PROGRAM | read_memory(address: string, length?: integer) | Hex-dump bytes from program memory (static file contents, not a running process). Max 512 bytes. |
| `get_instruction_at_address` | READ_PROGRAM | get_instruction_at_address(address: string, count?: integer) | Show the instruction(s) at an address with bytes, flow type, operand references and comments. |
| `get_data_type` | READ_PROGRAM | get_data_type(name: string) | Show a data type's definition (struct layout, enum values, typedef, function definition) by name. |
| `search_data_types` | READ_PROGRAM | search_data_types(query: string, limit?: integer) | Search the program's data types by name substring/regex. |
| `get_program_metadata` | READ_PROGRAM | get_program_metadata() | Get program metadata: name, format, architecture, compiler, image base, hashes, and counts of functions, memory blocks and imports. |
| `search_program` | READ_PROGRAM | search_program(query: string, limit_per_category?: integer) | Search across functions, symbols, defined strings and data types at once for a term. Good first step for natural-language questions. |
| `detect_packing` | READ_PROGRAM | detect_packing() | Check whether the program looks packed or protected (UPX, Themida, VMProtect, ...) from static signals: section names, entropy, imports, code coverage. Run this first on unfamiliar binaries; if the verdict is LIKELY, decompiler output is not trustworthy. |
| `list_imports` | READ_PROGRAM | list_imports(filter?: string, limit?: integer) | List imported (external) functions, optionally filtered by substring. Imports reveal capabilities (file I/O, networking, crypto...). |
| `list_functions` | READ_PROGRAM | list_functions(order?: string, skip_thunks?: boolean, start?: integer, limit?: integer) | Page through functions ordered by address, or rank by 'size' / 'xrefs' (most-called first) to find important functions. |
| `trace_value` | READ_PROGRAM | trace_value(function?: string, variable?: string, address?: string, direction?: string, max_depth?: integer, follow_callers?: boolean) | Trace where a variable's value comes from (backward) or where it goes (forward) using decompiler data-flow. Backward tracing can continue into callers when the origin is a parameter. Output labels each step [verified] or [inferred]. |
| `propose_rename_function` | PROPOSE_CHANGE | propose_rename_function(function: string, new_name: string, reason: string, confidence?: string) | Propose renaming a function. Does not change the program; queues a proposal for user approval. Use descriptive snake_case names supported by evidence. |
| `propose_rename_variable` | PROPOSE_CHANGE | propose_rename_variable(function: string, old_name: string, new_name: string, new_type?: string, reason: string, confidence?: string) | Propose renaming (and optionally retyping) a local variable or parameter, using the exact decompiler name from get_function_variables. |
| `propose_function_signature` | PROPOSE_CHANGE | propose_function_signature(function: string, signature: string, reason: string, confidence?: string) | Propose a C-style prototype for a function, e.g. 'void * load_asset(char * path, int flags)'. |
| `propose_comment` | PROPOSE_CHANGE | propose_comment(address: string, comment: string, type?: string, reason: string, confidence?: string) | Propose a comment at an address (plate = block above, pre, eol, post, repeatable). |
| `propose_function_comment` | PROPOSE_CHANGE | propose_function_comment(function: string, comment: string, reason: string, confidence?: string) | Propose a descriptive comment block above a function. Describe purpose, inputs, outputs and side effects, hedging uncertain claims. |
| `propose_label` | PROPOSE_CHANGE | propose_label(address: string, name: string, reason: string, confidence?: string) | Propose a label (symbol name) at an address, e.g. for a global variable or code location. |
| `propose_structure` | PROPOSE_CHANGE | propose_structure(name: string, fields: array, reason: string, confidence?: string) | Propose a new structure data type (created under /LocalLLM; existing types are never replaced). fields: array of {type, name, offset (optional, decimal or 0x hex), comment (optional)}. |
| `propose_enum` | PROPOSE_CHANGE | propose_enum(name: string, size?: integer, values: array, reason: string, confidence?: string) | Propose a new enum data type under /LocalLLM. values: array of {name, value}. |
| `propose_apply_type` | PROPOSE_CHANGE | propose_apply_type(address: string, type: string, reason: string, confidence?: string) | Propose applying a data type (e.g. 'MyStruct', 'int', 'char *') to the data at an address. Never overwrites instructions. |
| `recall_notes` | LOCAL_KNOWLEDGE | recall_notes(query?: string, limit?: integer) | Search previously stored analysis notes for this binary (summaries, hypotheses, approved renames, subsystem labels, user notes). |
| `save_note` | LOCAL_KNOWLEDGE | save_note(kind: string, subject: string, text: string, address?: string, confidence?: string) | Store a finding for later sessions (local database only). Use for confirmed facts or labelled hypotheses worth remembering; it does not change the Ghidra program. |

## Notes on specific tools

* **`get_function_decompile`** prefixes its output with a reminder that decompilation can be
  inaccurate; the system prompt tells the model to cross-check against `get_function_assembly`.
* **`trace_value`** walks the decompiler's SSA p-code. Backward mode follows definitions, merges
  (phi nodes) and, when the origin is a parameter, hops into callers (up to two hops, four call
  sites) and continues there. Forward mode lists uses (calls, stores, returns, branches). Every line
  is tagged `[verified]` (a p-code def-use fact) or `[inferred]` (crosses memory, calls or aliasing).
* **`propose_*`** tools validate immediately. Validation errors (bad name, missing function,
  overlapping struct fields, unknown type) are returned to the model so it can correct itself;
  proposals that would overwrite analyst-authored data are queued but flagged and will require an
  explicit confirmation click.
* **`save_note` / `recall_notes`** store model findings in the local database with source `AI`
  (unverified). Approved changes are recorded automatically with source `APPROVED`.

## Adding a tool

1. Write a `SimpleTool` (see `tools/impl/*.java`): name (`snake_case`), permission, description,
   `ToolParam` list and a body that returns a `ToolResult` or throws `ToolException`.
2. Register it in the matching `register(ToolRegistry)` method (or a new class listed in `tools/Tools.java`).
3. Add a test in `GhidraToolsTest` (or a unit test with a fake `ToolContext`).

Tools that would give the model anything beyond Ghidra inspection need a **new permission class**
and an explicit user-facing toggle; do not reuse `READ_PROGRAM`.
