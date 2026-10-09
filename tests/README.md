# Tests

The automated tests follow the Gradle convention and live in [`src/test/java`](../src/test/java):

| Suite | What it covers |
|---|---|
| `LMStudioProviderTest` | request serialization, response parsing, timeouts, HTTP/JSON errors, cancellation, loopback-only guard, Test Connection |
| `ToolRegistryTest`, `ParsersTest` | tool registration, argument validation, permissions, unknown tools, `<tool_call>` fallback parser, reasoning-block stripping |
| `ContextManagerTest` | priority order, truncation, de-duplication, "already sent" suppression, conversation fitting |
| `AgentLoopTest` | multi-step tool calls, native and prompted protocol, auto fallback, tool/step/repeat limits, loop termination, cancellation, malformed calls |
| `GhidraToolsTest` | every inspection tool against a real x86-64 program in headless Ghidra (including decompiler and data-flow tracing) |
| `ProposalTest` | every change type: proposing never modifies the program, approve/reject/edit, validation, analyst-name protection, undo |
| `AssistantServiceTest` | end to end: mock LM Studio + real Ghidra program, context reuse, regenerate, stop, program analysis |
| `RemoteProvidersTest` | OpenAI-compatible and Anthropic providers: auth headers, request/response mapping, thinking-block echo, temperature rules, refusal, retries, consent policy, secrets, routing, agent loop over Claude |
| `McpServerTest`, `McpBridgeTest` | MCP protocol over real HTTP against a real Ghidra program: negotiation, tool list/call, permission gating, bearer/Origin/Host checks, loopback-only bind, limits; the stdio bridge as a subprocess |
| `KnowledgeAndSettingsTest`, `MarkdownRendererTest` | SQLite store, settings persistence, UI renderer |

No live LLM is needed: `testutil/MockLmStudio` is a scripted OpenAI-compatible server and
`testutil/TestPrograms` builds a small real program in a headless Ghidra.

Run them with `./gradlew test` (requires `GHIDRA_INSTALL_DIR`).
