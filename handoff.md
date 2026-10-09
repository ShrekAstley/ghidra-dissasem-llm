# Handoff: Local LLM RE Assistant for Ghidra

Snapshot of where the project stands so the next person (or session) can continue without re-deriving anything.
Branch: `claude/eager-dirac-zvic9g` · Last commit: `ee3d230` · Target: Ghidra **12.0.1 PUBLIC**, JDK **21**.

## 1. What this is

A Ghidra extension (`GhidraLocalLLM`) that adds a dockable **Local LLM RE Assistant** window. An LLM acts as an *agent*: it pulls
evidence from Ghidra through a fixed set of read-only tools (decompiler, assembly, xrefs, strings, data-flow tracing…), answers with
CONFIRMED / LIKELY / POSSIBLE / UNKNOWN labels, and can only *propose* changes (renames, comments, signatures, structs, enums, types),
which the analyst approves in a Proposals tab. Default provider is LM Studio on localhost. Hosted providers (OpenAI-compatible,
Anthropic Claude) and an MCP server are **opt-in extras**.

Original requirements were a 39-section spec (local-first, tool registry, agent loop with hard limits, context manager, proposals,
program analysis, SQLite knowledge, expert mode, debug log, tests, docs, installable ZIP). All of it is implemented. The later
requests changed the "no cloud" rule to "local by default, cloud opt-in" and added MCP.

## 2. State at a glance

| Area | Status |
|---|---|
| Build / package | Works on Linux with JDK 21. Produces `dist/ghidra_12.0.1_PUBLIC_<date>_GhidraLocalLLM.zip` (~14 MB, mostly `sqlite-jdbc`). |
| Automated tests | **142 passing** (`./gradlew test`, ~1.5 min). No live LLM needed. |
| Verified in real Ghidra GUI (Linux, Xvfb) | Plugin loads and is listed in *File → Configure*; chat + tool calls against a real program; evidence-labelled answer; proposal queued then applied (Listing and Decompiler updated, Undo available); MCP server called from `curl` (auth, tools/list, cursor-aware tool, decompile, proposal queued and visible in the Proposals tab, 403 on bad Origin). |
| Windows | Build works after pulling the JDK/`GHIDRA_INSTALL_DIR` fixes. **`gradlew test` crashed the test JVM** (see §6.1) — unresolved. |
| Real model / real APIs | **Never run** against a real LM Studio model, the real Anthropic API, the real OpenAI API, Claude Desktop, or Claude Code as an MCP client. Only mock servers. |

## 3. Repo map

```
build.gradle, settings.gradle, gradle.properties, gradlew*   Gradle (uses Ghidra's support/buildExtension.gradle)
gradle/gradle-daemon-jvm.properties                          pins the build JVM to 21 (Foojay auto-provisioning)
extension.properties, Module.manifest                        Ghidra extension metadata
src/main/java/ghidrallm/
  llm/        LLMProvider, RoutingProvider, LMStudioProvider, OpenAiCompatibleProvider, AnthropicProvider,
              HttpTransport, EndpointPolicy (consent gate), OpenAiJson, ChatMessage/Request/Response, LLMException
  agent/      AssistantService (worker thread, workflows, program analysis, MCP lifecycle), AgentLoop, ContextManager,
              Conversation, Prompts, Workflows, ToolCallParser (<tool_call> fallback), ResponseParser, ArchitectureReport
  tools/      ToolRegistry/Tool/ToolParam/ToolContext/Tools, impl/* (Function, Reference, Data, Program, Trace,
              Proposal, Knowledge tools)
  changes/    ChangeProposal + 8 proposal types, ProposalManager (validate -> approve -> one undoable transaction)
  ghidra/     DecompilerService, Resolver, ContextCollector, ProgramAccess
  knowledge/  KnowledgeStore (SQLite), Note, ProgramKeys
  mcp/        McpProtocol (JSON-RPC), McpHttpServer (127.0.0.1, bearer token)
  config/     Settings, SettingsStore, SecretStore
  log/ util/  DebugLog, CancellationToken, Text
  ui/         LlmAssistantPlugin, AssistantProvider, AssistantPanel, TranscriptPane, ProposalsPanel, ArchitecturePanel,
              KnowledgePanel, DebugLogPanel, SettingsDialog, RemoteConsent, MarkdownRenderer, Theme
src/main/resources/images/    icons (generated)
src/test/java/ghidrallm/      tests; testutil/MockLmStudio (OpenAI + Anthropic shaped), testutil/TestPrograms (headless Ghidra program),
                              ui/PanelPreview (screenshot harness, not a unit test)
docs/                         architecture, tools, configuration, development, providers-and-mcp, images/
examples/                     mcp_stdio_bridge.py (ships in the ZIP), mock_lm_studio.py, sample.c (test binary source)
tests/README.md               index of the test suites
```

Read first: `README.md`, then `docs/architecture.md`. Per-feature detail: `docs/tools.md` (tool list generated from the registry),
`docs/providers-and-mcp.md`, `docs/configuration.md`.

## 4. How to build, test, install

```bash
export GHIDRA_INSTALL_DIR=/path/to/ghidra_12.0.1_PUBLIC     # PowerShell: $env:GHIDRA_INSTALL_DIR = "C:\...\ghidra_12.0.1_PUBLIC"
./gradlew test buildExtension                               # ZIP -> dist/
```
Install: Ghidra *File → Install Extensions → +*, pick the ZIP, restart; then in CodeBrowser *File → Configure → ☑ Local LLM RE Assistant*.
(Installing from a directory: unzip into `<ghidra>/Ghidra/Extensions/`.)

Other useful commands:

* `./gradlew buildExtension -x test` — build without tests (use this if tests crash, §6.1).
* `xvfb-run ./gradlew previewPanel` — renders the real UI against mocks into `build/preview/*.png` (light and dark).
* `python3 examples/mock_lm_studio.py` — scripted LM Studio stand-in on :1234 for manual GUI runs.
* `./gradlew test --tests '*GhidraEnvironmentTest' -i` — prints Java/OS/Ghidra info and fails with a clear message if headless Ghidra can't start.

Build gotchas already handled (don't re-debug): JDK 25+ breaks Gradle 8.14 (`Unsupported class file major version`) -> daemon JVM is pinned to 21;
`GHIDRA_INSTALL_DIR` is sanitized/validated in `build.gradle` (quotes, `<path>` placeholders); a stale second ZIP in `dist/` breaks
`unzip dist/*.zip` (delete old ones).

## 5. Design decisions worth knowing

* **Tool boundary is the security boundary.** Models only ever name tools from `ToolRegistry`; args are schema-validated; permission
  classes `READ_PROGRAM` / `PROPOSE_CHANGE` / `LOCAL_KNOWLEDGE`. No shell/process/filesystem tool exists. Don't add one without a new permission class.
* **Nothing modifies Ghidra except `ProposalManager.approve`**, which re-validates against the current program, demands explicit confirmation for
  `OVERWRITE` problems (analyst-named things, existing comments, defined data), and applies in one transaction (Undo works). Tests in
  `ProposalTest` prove proposals are inert until approved.
* **Agent loop limits:** tool calls, steps, identical repeats, result size, per-request and wall-clock timeouts; on a limit, tools are withheld
  and the model is told to answer from evidence. Failed/cancelled turns are removed from history.
* **Tool modes:** `NATIVE`, `PROMPTED` (`<tool_call>` JSON), `AUTO` (try native, fall back after an HTTP 400 — disabled for hosted providers).
* **Context:** priority-ordered, hash-keyed items; unchanged context isn't resent; old tool results are elided before turns are dropped;
  one-line session memory per analysed function; a function named in the question re-targets the context.
* **Remote consent:** `EndpointPolicy.check` runs before every request. Non-loopback hosts need an entry in `Settings.remoteConsentHosts`, written only by
  the settings dialog (`RemoteConsent`). `allowNonLoopbackEndpoint` is a legacy config-file escape hatch that bypasses consent (not exposed in the UI).
* **Claude:** raw HTTP (not the Java SDK) to avoid OkHttp/Jackson/Kotlin in Ghidra's classloader. Thinking/tool_use blocks are echoed verbatim only within the
  current turn (`ChatMessage.providerBlocks`); `max_tokens >= 8000`; `temperature` omitted for gen-5 models and Opus 4.7/4.8.
* **MCP:** same registry and `ToolContext` as the agent, via `Tools.create(allowProposals, allowKnowledge)`. Loopback bind, bearer token, Host/Origin
  checks, 1 MB limit. Token lives in `secrets.json`, generated lazily on first use.
* **Secrets:** `secrets.json` next to `settings.json` (`<Ghidra user settings>/local-llm-re/`), owner-only on POSIX, **not encrypted**.
* **Knowledge DB** is keyed by program name + executable MD5.

## 6. Open items (highest priority first)

1. **Windows test crash — unresolved.** User output showed `Gradle Test Executor 1 finished with non-zero exit value -1` right after
   `AgentLoopTest` (the first class needing headless Ghidra); no Java stack trace. Diagnostics were added in `2b67cb5`
   (`System.exit` trap with stack trace, `forkEvery = 1`, `build/hs_err_pid*.log`, `GhidraEnvironmentTest`). **Waiting on the user's
   output** of `.\gradlew.bat test --tests "*GhidraEnvironmentTest" -i`. Hypotheses: native/JVM crash from headless Ghidra init or the native
   decompiler on Windows, memory (now `-Xmx2g`), or an `System.exit` inside Ghidra. Workaround: `-x test`.
2. **Never tested against real services.** First real runs needed: LM Studio with a tool-capable model (check `AUTO` fallback and context sizing);
   Anthropic API (key + model list + a tool loop with a reasoning model); OpenAI API (`max_completion_tokens`/temperature adaptation is only
   unit-tested); Claude Code (`claude mcp add --transport http …`) and Claude Desktop via `examples/mcp_stdio_bridge.py`. Fix whatever surfaces.
3. **GUI paths not exercised in real Ghidra:** right-click *Listing → Local LLM* actions, *Window* menu entry, *Analyze Program* button (service path is tested),
   the new Settings dialog (only rendered offscreen; the Claude tab's right edge looked clipped in the offscreen render — check in the real dialog),
   consent dialog flow, key entry, MCP copy buttons, Windows/macOS look.
4. **Possible preserved-thinking issue with newest Claude models.** `ContextManager.fit` edits older tool results and drops oldest turns; for Anthropic
   only current-turn thinking blocks are echoed, older ones are rebuilt without thinking, which should be fine — verify live against Fable/Opus 5.x
   ("history-editing check" 400s).
5. **No streaming** (full reply when ready; Stop cancels the HTTP request). Fine for now; a UX gap for slow local models.
6. **MCP scope:** tools only (no resources/prompts/sessions/SSE). The assistant is not an MCP *client* (deliberate: stdio servers = process execution).
7. Minor: `MarkdownRenderer` links hex constants like `0x41535354` as addresses; `README.md` tool count wording; API keys not encrypted at rest;
   no Ghidra help topics (`markHelpUnnecessary` / no `HelpLocation`).

## 7. Suggested next steps

1. Get the Windows `GhidraEnvironmentTest` output; fix the crash or make Ghidra-backed tests skip with a clear message when headless init isn't possible.
2. Do a real-model smoke test (LM Studio + a 7–14B instruct model, 16k+ context): *Explain*, *Analyze*, *Trace*, *Security*, *Suggest names*, *Analyze Program*
   on `examples/sample.c` compiled with `gcc -O0 -no-pie`; note prompt/format problems per model family and tune `Prompts`/`Workflows`.
3. Smoke-test Claude and OpenAI with real keys on a small, shareable binary; confirm consent flow, cost per question, `refusal` handling.
4. Try MCP from Claude Code and Claude Desktop (bridge); adjust `instructions` text and tool descriptions if clients choose tools poorly.
5. Nice-to-haves: streaming, per-tool-call approval mode, richer data-type reconstruction workflow, exporting analysis reports, Ghidra help pages, encrypting `secrets.json` with OS keychain.

## 8. Working notes for whoever continues

* **Sandbox specifics (not in the repo):** Ghidra 12.0.1 was unpacked to `/opt/ghidra` (downloaded from the GitHub release; the source zips under `Ghidra/*/lib/*-src.zip`
  were used to check APIs — grep them rather than guessing signatures). A fresh environment must re-download Ghidra.
* **GUI automation recipe** (used for the live checks): `Xvfb :99 -screen 0 2600x1300x24`, launch `ghidraRun <project>.gpr`, drive with a small `java.awt.Robot` script
  (click/type/screenshot), import test binaries with `support/analyzeHeadless`. Screenshots are downscaled in viewers (coordinates x1.3). The first run needs Ghidra's
  user-agreement dialog accepted. Don't use `pkill -f ghidra` from a shell whose command line contains "ghidra" — it kills itself; use `pkill -x java`.
* **Ghidra API traps hit so far:** `ComponentProvider.setVisible(true)` before `addToTool()` -> "Component already added"; `FunctionSignatureParser` throws
  `ghidra.app.util.cparser.C.ParseException`; `DataTypeParser` throws `ghidra.program.model.data.InvalidDataTypeException`; the program-local `DataTypeManager` doesn't list
  built-ins until used (search also queries `BuiltInDataTypeManager`); default `FUN_xxxx` names resolve via the address fallback in `Resolver`.
* **Test conventions:** every state-changing path needs a test proving it is inert until approved and undoable afterwards; remote providers are tested only against
  `MockLmStudio`; tests that boot Ghidra use `TestPrograms.build` (x86-64, Win64 calling convention: RCX/RDX).
* **Docs claim only what's verified.** Keep the "not yet run against…" caveats in `README.md` and `docs/providers-and-mcp.md` until the open items above are done.
