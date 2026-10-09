# Hosted providers and MCP

The project is **local-first**: the default provider is LM Studio on `localhost`. This page covers the two optional
extensions — sending prompts to a hosted model, and exposing the Ghidra tools to MCP clients — and what each one
means for your data.

## 1. Hosted model providers

| Provider | Settings → Provider | Endpoint (default) | Auth | Notes |
|---|---|---|---|---|
| LM Studio | *LM Studio (local, default)* | `http://localhost:1234/v1` | none | Local only. |
| OpenAI-compatible | *OpenAI-compatible* | `https://api.openai.com/v1` | `Authorization: Bearer <key>` | Also Ollama (`http://localhost:11434/v1`), llama.cpp, vLLM, OpenRouter, any server speaking `/chat/completions`. Adapts to models that need `max_completion_tokens` or reject `temperature`. |
| Anthropic Claude | *Anthropic Claude* | `https://api.anthropic.com/v1` | `x-api-key` + `anthropic-version: 2023-06-01` | Messages API with native tool use. |

### What happens when you choose a remote host

* Saving (or testing) shows a warning listing what leaves the machine — decompiled code, disassembly, symbol names,
  strings, memory bytes, cross-references, your questions and the conversation — and asks you to approve **that host**.
* Approvals are stored per host in `settings.json` (`remoteConsentHosts`). Only the settings dialog adds entries; the model and MCP clients cannot.
* Until a host is approved every request to it fails with *"remote host you have not approved"* before any network traffic.
* The header shows `☁ REMOTE: <host>` whenever the active endpoint is not loopback, and `🔒 local` otherwise.
* Local endpoints bypass system proxies; remote ones use the JVM's default proxy selection (corporate proxies work).

### API keys

* Preferred: put the key in an environment variable and enter its **name** in *API key env var* (e.g. `ANTHROPIC_API_KEY`). It must be set in the environment of the process that launches Ghidra.
* Otherwise paste it into *API key*: it is stored in `<settings dir>/local-llm-re/secrets.json` (owner-only permissions where the OS supports it; not encrypted) — **not** in `settings.json`, the debug log, or prompts.
* Precedence: key typed in the open dialog (for testing) → environment variable → stored key. *Remove stored key* deletes it.

### Claude specifics (as implemented)

* **Reasoning models:** newer Claude models reason by default, so `max_tokens` is at least 8000 even if *Max output tokens* is lower; the optional *Reasoning effort* setting is sent as `output_config.effort`.
* **Sampling parameters:** models that reject them (generation-5 families, Opus 4.7/4.8) get no `temperature`; other models get your value; an API 400 mentioning `temperature` drops it automatically.
* **Tool loops:** within the turn that produced them, `thinking`/`tool_use` blocks are sent back **verbatim**; earlier turns are rebuilt without thinking blocks.
* **Refusals:** a `refusal` stop reason is shown as a readable message with its category.
* **Errors:** 401/403 → *key rejected*; 429/529 → retried twice with back-off (honoring `retry-after`) then *rate limited*; 404 → *model not found*; oversized prompts → the agent shrinks its context and retries.
* **Tool protocol:** native only (the text fallback is disabled for hosted providers so a bad request is reported rather than silently changing behavior).
* **Implementation note:** requests are made with the JDK HTTP client rather than a vendor SDK, to keep the extension free of OkHttp/Jackson/Kotlin classpath conflicts inside Ghidra and to route every provider through the same cancellable, consent-checked transport.

### Cost and risk notes

The agent makes several requests per question and context blocks can be large. Watch the token counter in the header, lower *Agent tool-call limit* and *Context budget*, and prefer smaller models for *Analyze Program*. Prompt text can be influenced by strings inside the binary; the tool boundary and approval flow still apply with hosted models.

## 2. MCP server (Ghidra → MCP clients)

Settings → **MCP server** exposes the assistant's tool registry through the [Model Context Protocol](https://modelcontextprotocol.io)
so clients such as Claude Code, Cursor or VS Code can inspect the open program.

* Transport: **Streamable HTTP**, `POST http://127.0.0.1:<port>/mcp` (default port 8765), JSON responses, protocol versions 2025-06-18 / 2025-03-26 / 2024-11-05.
* Methods: `initialize`, `ping`, `tools/list`, `tools/call` (+ notifications). No resources/prompts/sampling.
* **Security**
  * Bound to `127.0.0.1` only (never the LAN).
  * `Authorization: Bearer <token>` on every request (token generated locally, shown only via *Copy token*, rotatable).
  * `Host` must be `127.0.0.1|localhost|[::1]:<port>` and any `Origin` must be loopback (blocks DNS-rebinding from web pages).
  * 1 MB request limit; per-call time limit (*Agent time limit*); results truncated like the agent's.
  * Off by default. Read-only inspection tools by default; **propose_\*** tools only if *MCP clients may queue change proposals* is ticked — they only add entries to the Proposals tab and never modify the program; knowledge tools only if ticked.
  * Every call is written to the Debug Log (`[MCP] …`, results flagged 🔒).
* **Data flow:** a connected client receives whatever its tool calls return. If that client is a cloud-hosted model, that data goes to its provider — the consent prompt above does not apply to MCP clients, so decide per client.

### Connecting clients

**Claude Code** (supports HTTP MCP servers):
```bash
claude mcp add --transport http ghidra http://127.0.0.1:8765/mcp --header "Authorization: Bearer <token>"
```

**Claude Desktop and other stdio-only clients** — use the bundled bridge (Python 3 standard library only). It ships inside the extension at `<Ghidra>/Ghidra/Extensions/GhidraLocalLLM/examples/mcp_stdio_bridge.py` (or `<user settings>/Extensions/GhidraLocalLLM/examples/…` for per-user installs), and in this repo under `examples/`:
```json
{
  "mcpServers": {
    "ghidra": {
      "command": "python3",
      "args": ["/ABSOLUTE/PATH/TO/examples/mcp_stdio_bridge.py"],
      "env": { "GHIDRA_MCP_URL": "http://127.0.0.1:8765/mcp", "GHIDRA_MCP_TOKEN": "<token>" }
    }
  }
}
```
Settings has *Copy* buttons that fill these in with your port and token.

**Anything else:** point an HTTP-capable MCP client at the URL with the `Authorization` header.

**Manual check:**
```bash
curl -s -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' http://127.0.0.1:8765/mcp
```

### What this does not do

* **Cloud-hosted MCP services (e.g. OpenAI's hosted MCP tool, claude.ai connectors) cannot reach `127.0.0.1`.** Exposing the server through a tunnel or reverse proxy publishes access to your binary's analysis data and is strongly discouraged; it is not supported or tested.
* The assistant is not an MCP *client*: it cannot call other MCP servers. A stdio MCP server is arbitrary process execution, which would break the rule that the model only gets explicit, permissioned Ghidra tools. If you need it, it should be added with its own permission class and per-server approval.

### Testing status

Covered by automated tests (mock servers, real headless Ghidra, a real HTTP socket, and the bridge as a subprocess) and a manual run with `curl` against a live Ghidra GUI. **Not yet verified with real Claude Code / Claude Desktop installs or the real Claude/OpenAI APIs.**
