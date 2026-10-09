# Architecture

```
Ghidra ── Program DB · Listing · Decompiler · Functions · Symbols · Data types · Refs · Memory
   ▲                                  │
   │ read-only tools / approved edits │
┌──┴──────────────────────────────────┴───────────────────────────────┐
│ ui/            LlmAssistantPlugin · AssistantProvider · AssistantPanel │
│                Proposals · Architecture · Knowledge · Debug · Settings │
├────────────────────────────────────────────────────────────────────────┤
│ agent/         AssistantService (worker thread, workflows)            │
│                AgentLoop · ContextManager · Conversation · Prompts    │
│                ToolCallParser · ResponseParser · ArchitectureReport   │
├────────────────────────────────────────────────────────────────────────┤
│ tools/         ToolRegistry · ToolContext · impl/* (inspection,       │
│                trace, propose_*, knowledge)                           │
│ changes/       ChangeProposal · ProposalManager (approve → validate → │
│                apply in one undoable transaction)                     │
│ ghidra/        DecompilerService · Resolver · ContextCollector        │
│ knowledge/     KnowledgeStore (SQLite)                                │
│ config/ log/   Settings · SettingsStore · DebugLog                    │
├────────────────────────────────────────────────────────────────────────┤
│ llm/           LLMProvider ◄─ RoutingProvider ─► LMStudioProvider     │
│                                          ├► OpenAiCompatibleProvider  │
│                                          └► AnthropicProvider         │
│                EndpointPolicy (loopback or user-approved host only)   │
│ mcp/           McpProtocol (JSON-RPC) · McpHttpServer (127.0.0.1)     │
└───────────────┬──────────────────────────────────────▲─────────────────┘
                │ HTTP: loopback by default,           │ MCP clients (loopback,
                │ remote hosts only after consent      │ bearer token)
                ▼                                      │
     LM Studio (default) · OpenAI-compatible · Claude  Claude Code, Cursor, …
```

## Threading

* The Swing EDT never waits for the model, the decompiler, or a search.
* `AssistantService` owns a single daemon worker thread; every agent run, context collection,
  decompile and program analysis happens there. UI callbacks hop back with `invokeLater`.
* Cancellation is a `CancellationToken` shared by the UI, the agent loop, tool loops and the HTTP
  layer (the in-flight `HttpClient` request is cancelled, so **Stop** takes effect immediately).
* Applying a proposal runs on a short-lived background thread inside a Ghidra transaction.

## The agent loop (`AgentLoop`)

```
user turn (question + prioritized Ghidra context)
 └─ repeat:
     fit history to the prompt budget          (ContextManager.fit)
     ask the model                              (native tools, or prompted <tool_call> protocol)
     tool calls?  ── no ──► final answer (reasoning blocks stripped) ─► done
        │ yes
        ├─ unknown / invalid arguments  → error text returned to the model
        ├─ identical call repeated      → blocked, model told why
        ├─ call budget exhausted        → blocked, final answer forced
        └─ execute on Ghidra (read-only or queue proposal) → result (truncated) → back to model
```

Hard limits (all configurable): tool calls per question, agent steps ("recursion"), identical
repeats, tool-result size, per-request timeout, wall-clock timeout, output tokens, context budget.
When a limit trips, tools are withheld and the model is told to answer from the evidence so far, so a
misbehaving model cannot loop forever. Failed or cancelled turns are removed from history.

### Tool-calling modes

| Mode | Behavior |
|---|---|
| `NATIVE` | OpenAI `tools` / `tool_calls`. |
| `PROMPTED` | Tool list is embedded in the system prompt; the model emits `<tool_call>{"name":…,"arguments":{…}}</tool_call>`; results return as `<tool_response>` user messages. |
| `AUTO` (default) | Try native; if the server rejects tools (HTTP 400) switch to prompted for the session. `<tool_call>` text is also understood in native mode (some models emit it as content). |

The parser only produces data (name + JSON arguments). Nothing the model writes is ever executed
except by name through the registry.

## Context management

* **Initial context** for the selected function is collected by running the same read-only tools
  the agent has (`ContextCollector`) and priced by `ContextManager.assemble`, in this priority:
  current function → user question → assembly → decompilation → callers/callees → strings →
  globals → xrefs → related/stored notes → broader program. One item may use at most 45 % of the block;
  oversize items keep head and tail; omitted items are listed with the tool that fetches them.
* **Reuse:** each item has a content-hash key. Items already sent in this conversation are replaced
  by a one-line "unchanged" note, so follow-ups don't resend code. Editing the function (e.g. an
  approved rename) changes the hash and the context is resent.
* **History fitting:** when over budget, old tool results are elided first, then oldest whole turns
  are dropped. A compact **session memory** (one line per analysed function) stays in the system
  prompt so "what about the second caller?" still resolves.
* Mentioning a function by name/address in the question (`FUN_…`, `0x…`) re-targets the context to
  that function even if the cursor is elsewhere.

## Change proposals

`Proposal → Preview → User approval → Validation → Apply`

* `propose_*` tools create `ChangeProposal`s. Creating one never touches the program.
* `ProposalManager.approve` re-validates against the *current* program, refuses on errors, and asks
  for explicit confirmation for `OVERWRITE` problems (analyst-named functions/variables, existing
  comments, defined data, user-set signatures).
* Apply happens in one `program.startTransaction("Local LLM: …")` so **Edit → Undo** reverts it.
* Types are created under `/LocalLLM` and never replace existing types; type application refuses to
  overwrite instructions.
* Proposals can be edited (name, signature, comment text, struct body) before approval.

## Persistence and logs

* `KnowledgeStore` (SQLite, one file under the Ghidra user settings directory) stores function
  summaries, hypotheses, relationships, subsystems, approved changes and user notes, keyed by
  program name + MD5. AI notes are labelled unverified.
* `DebugLog` is in-memory (2000 entries). Entries holding program data are flagged 🔒 and can be masked
  in view, copy and export.

## Providers, consent and MCP

* `RoutingProvider` picks the implementation named by `Settings.providerType` on every call, so switching providers needs no restart.
* All providers share `HttpTransport` (cancellation, timeouts, retries for 429/5xx/529 on remote hosts, proxy policy) and call
  `EndpointPolicy.check` before every request; non-loopback hosts need an entry in `remoteConsentHosts` (written only by the settings UI).
* Provider-specific conversation state (Claude `thinking`/`tool_use` blocks) rides in `ChatMessage.providerBlocks` and is
  echoed back only inside the turn that produced it.
* `SecretStore` keeps API keys and the MCP token outside `settings.json`.
* `McpProtocol` serves `Tools.create(mcpAllowProposals, mcpAllowKnowledge)` — the same tool classes, permission model and
  `ToolContext` as the in-app agent, so the MCP surface cannot drift from it. `AssistantService.reconfigureMcp()` starts/stops the listener from settings.
