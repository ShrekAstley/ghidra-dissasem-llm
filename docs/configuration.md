# Configuration

Settings are edited in **⚙ Settings** (or the header controls) and stored as JSON in the Ghidra user
settings directory: `<Ghidra user settings dir>/local-llm-re/settings.json` (on Linux typically
`~/.config/ghidra/ghidra_<version>/local-llm-re/`). The knowledge database `knowledge.db` lives in the
same folder. Nothing is machine-specific; delete the folder to reset everything.

| Setting | Default | Notes |
|---|---|---|
| LM Studio URL | `http://localhost:1234/v1` | Loopback only unless *Allow non-localhost endpoint* is ticked. |
| Model | *(empty)* | Empty = the model LM Studio reports first (embedding models skipped). |
| Temperature | `0.2` | Low values recommended. |
| Max output tokens | `2048` | Per model reply. |
| Context budget (tokens) | `16384` | Must be ≤ the context length you loaded the model with. |
| Agent tool-call limit | `16` | Per question. |
| Agent step limit | `12` | Model round-trips per question. |
| Max identical repeats | `2` | Same tool + args beyond this are blocked. |
| Max tool result chars | `6000` | Longer results are truncated. |
| Request timeout | `180 s` | One HTTP request. |
| Agent time limit | `600 s` | Whole question. |
| Tool protocol | `AUTO` | `AUTO`, `NATIVE`, `PROMPTED`. |
| Program-analysis functions | `25` | Key functions summarized by *Analyze Program*. |
| Allow change proposals | on | Revoke to make the model strictly read-only. |
| Allow knowledge tools | on | Lets the model call `save_note` / `recall_notes`. |
| Persist knowledge | on | SQLite file; off = no database at all. |
| Attach selected-function context | on | Off = the model starts with only the question and fetches via tools. |
| Expert mode | off | Shows tool calls/arguments, per-step timing, token usage, raw views. |
| Mask program data in debug log | off | Default for view/copy/export. |

### Sizing the context budget

The fixed prompt cost (system prompt + native tool schemas) is roughly 3–4 k tokens. With a
16 k context that leaves room for the selected function's context and several tool results. On
4–8 k models use `PROMPTED` mode (smaller tool list) or reduce *Max tool result chars* and the
tool-call limit; below ~8 k the assistant will work but with little room for evidence.
