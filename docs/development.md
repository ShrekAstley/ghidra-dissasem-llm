# Development guide

You do not need to know Java or Ghidra internals to build and install the extension — follow
**Build** and **Install**. The rest is for contributors.

## Requirements

| | |
|---|---|
| Ghidra | 12.0.x (built and verified against **12.0.1 PUBLIC**) |
| JDK | **21** (Ghidra 12 needs 21+; the bundled Gradle 8.14 cannot run on JDK 25+, so use 21 for the build) |
| Gradle | 8.5+ — the included wrapper (`./gradlew`) downloads 8.14.3 |
| Network | Only for fetching Gradle/Maven dependencies at build time (JUnit, `sqlite-jdbc`). The extension itself never uses the network except `localhost`. |

Other Ghidra versions: set `GHIDRA_INSTALL_DIR` to that install and rebuild; the extension ZIP is
versioned for the Ghidra it was built against (Ghidra refuses to install extensions built for another version).

## Build

The build is pinned to JDK 21 via `gradle/gradle-daemon-jvm.properties`: Gradle uses an installed JDK 21 or downloads one (Foojay) automatically, whatever `JAVA_HOME` is. Check with `./gradlew --version` (look at the *JVM* line). If it is not 21, set `JAVA_HOME` to a JDK 21 install. Running on a newer JDK fails immediately with `Unsupported class file major version NN` (70 = Java 26).

```bash
export GHIDRA_INSTALL_DIR=/path/to/ghidra_12.0.1_PUBLIC     # Windows: set GHIDRA_INSTALL_DIR=C:\ghidra...
./gradlew buildExtension          # → dist/ghidra_<ver>_<date>_GhidraLocalLLM.zip
```

On Windows PowerShell use `$env:GHIDRA_INSTALL_DIR = "C:\path\to\ghidra_12.0.1_PUBLIC"` and `.\gradlew.bat`. To avoid setting it each time, add `GHIDRA_INSTALL_DIR=C:/path/to/ghidra_12.0.1_PUBLIC` to `~/.gradle/gradle.properties`.

## Test

```bash
./gradlew test                    # 110 tests; uses a mock LM Studio and a headless Ghidra, no live LLM
```

Reports: `build/reports/tests/test/index.html`.

## Package / install

1. In Ghidra: **File → Install Extensions → ＋**, choose the ZIP from `dist/`, tick *GhidraLocalLLM*, OK.
2. Restart Ghidra.
3. Open a program in CodeBrowser. If prompted about new plugins, accept; otherwise
   **File → Configure → Local LLM RE Assistant ☑**. The assistant docks on the right
   (**Window → Local LLM RE Assistant** to reopen).

Command-line alternative: unzip the ZIP into `<ghidra>/Ghidra/Extensions/` (all users) or
`<user settings>/Extensions/` (one user) and restart.

## Debug

* **Debug Log tab** in the assistant: every request, tool call/result, timing and error.
* Ghidra log: `~/.config/ghidra/ghidra_<ver>/application.log`.
* Launch Ghidra with `support/ghidraDebug` to attach an IDE debugger (JDWP, port 18001).
* UI work without Ghidra: `xvfb-run ./gradlew previewPanel` renders the real panel against the
  mock server into `build/preview/*.png` (light and dark themes).
* Try the whole thing without a model: `python3 examples/mock_lm_studio.py` serves a scripted LM Studio on
  port 1234. `examples/sample.c` is a small program with an unsafe `strcpy` and a file loader that
  is handy for manual testing (`gcc -O0 -no-pie`).

## Layout

```
build.gradle, settings.gradle, gradle.properties, gradlew   Gradle build using Ghidra's buildExtension.gradle
extension.properties, Module.manifest                       Ghidra extension metadata
src/main/java/ghidrallm/{llm,tools,agent,changes,ghidra,knowledge,config,log,ui,util}
src/main/resources/images                                    icons
src/test/java/ghidrallm                                      unit + integration tests, testutil/ (mock LM Studio, test program)
docs/  examples/  tests/README.md
```

## Adding another LLM provider

1. Implement `ghidrallm.llm.LLMProvider` (`id`, `listModels`, `chat`, `testConnection`; optionally `canFallBackToPrompted`,
   `requiresExplicitModel`). Map failures to `LLMException` kinds and honor the `CancellationToken`.
2. Use `HttpTransport` (cancellation, timeouts, retry policy, proxy handling) and call `EndpointPolicy.check(settings, uri)` before **every**
   request so non-loopback hosts stay consent-gated. Take keys from the `Function<Settings,String>` passed in (backed by `SecretStore`).
3. Servers speaking the OpenAI chat format usually need nothing new: use `OpenAiCompatibleProvider` with a different URL.
4. Register it in `AssistantService`'s provider map, add a `ProviderInfo` entry in `SettingsDialog`, and extend `Settings.normalize()`.
5. Test it against a scripted HTTP server (`MockLmStudio` has OpenAI- and Anthropic-shaped endpoints).

New *hosted* providers must surface the data-leaves-the-machine warning (it is automatic for any non-loopback endpoint) and never log keys.

## Conventions

* Anything that can modify the program goes through `ChangeProposal` + `ProposalManager`, and needs
  a test in `ProposalTest` proving it is inert until approved and undoable afterwards.
* Prefer deterministic Ghidra facts in tool output; mark inference explicitly.
* No blocking calls on the EDT.
