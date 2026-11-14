<!--
SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
SPDX-License-Identifier: MIT
License: see LICENSE in the repository root.
-->

# Repository guide for AI coding agents

## Project identity and scope

Seq MCP is a Kotlin JetBrains IDE plugin that exposes Seq structured-log search, Seq SQL, signals, workspaces, dashboards and related tools through the IDE's bundled MCP server. It is not a standalone MCP server or a Seq deployment.

Start with [README.md](README.md), [docs/mcp-tools.md](docs/mcp-tools.md) and [CHANGELOG.md](CHANGELOG.md). The current provider registers 18 tools. The build targets IntelliJ IDEA Ultimate `263.7307.200`, requires the bundled `com.intellij.mcpServer` plugin and uses Java 25. Read build metadata before changing compatibility claims.

## Architecture and navigation

Kotlin paths below are under `src/main/kotlin/com/vitaliiboiko/seqmcp/`.

| Path | Responsibility |
| --- | --- |
| `mcp/SeqMcpToolsProvider.kt` | Tool descriptors, JSON schemas, argument validation, project resolution, backend calls and structured results |
| `services/SeqApiService.kt` | `SeqMcpBackend` interface, request/result types, API errors and the IDE service adapter |
| `services/SeqApiClient.kt` | Production Seq HTTP/WebSocket client, resource-link resolution, credentials and transport limits |
| `services/SeqMcpToolService.kt` | Tool descriptions displayed in the UI |
| `services/SeqMcpSettingsService.kt` | Application-wide server settings and Password Safe API keys, including workspace overrides |
| `services/SeqMcpProjectSettingsService.kt` | Per-project enablement persisted in workspace settings |
| `services/SeqMcpProjectService.kt` | Project status presentation |
| `services/SeqMcpLogService.kt` | Bounded in-memory log/listener lifecycle |
| `settings/SeqMcpConfigurable.kt` | Settings UI and apply/reset behavior |
| `toolWindow/SeqMcpToolWindowFactory.kt`, `startup/SeqMcpProjectActivity.kt` | Tool window and startup visibility |
| `SeqMcpBundle.kt` | Resource-bundle access |

Other entry points:

- `src/main/resources/META-INF/plugin.xml`: plugin identity, dependencies and extension registrations.
- `src/main/resources/messages/MyBundle.properties`: localized UI strings.
- `src/test/kotlin/com/vitaliiboiko/seqmcp/SeqMcpPluginTest.kt`: platform tests with fake backend/project resolver, schema and behavior checks.
- `src/test/kotlin/com/vitaliiboiko/seqmcp/SeqTransportTest.kt`: production-client tests against a local TLS/WebSocket server.
- `src/test/kotlin/com/vitaliiboiko/seqmcp/SeqSandboxUiTest.kt`: opt-in real Seq connection-button test; `scripts/test-seq-sandbox.py` provisions a disposable authenticated sandbox.
- `src/test/kotlin/com/vitaliiboiko/seqmcp/SeqCloudIntegrationTest.kt`: opt-in live-server tests; see `docs/development.md` for credentials and sandbox setup.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`: toolchain, plugin versions, target IDE, required Gradle version and release configuration.
- `.github/workflows/build.yml`: packaging, checks on the latest Linux/Windows/macOS runners, then plugin verification and REUSE license checks; `gradlepublish.yml`: Marketplace publishing; `run-ui-tests.yml`: separate manual UI workflow.
- `.run/`: checked-in IDE run configurations.

Tool calls flow through the provider's schema/argument checks and enabled-project resolution to `SeqMcpBackend`, then `SeqApiService`. Keep the backend interface usable by the existing fake tests.

## Build and validation

Run from the repository root using Java 25 and an installed Gradle 9.9.1 on `PATH` (also selected as the local Gradle installation in the IDE). The repository does not bundle a Gradle wrapper. Initial runs need network/cache access for Gradle, dependencies and the target IDE.

```sh
gradle buildPlugin
gradle check
gradle verifyPlugin
```

These are the main CI tasks. `buildPlugin` packages the plugin; `check` runs verification including tests; `verifyPlugin` checks compatibility with the configured current IDEA build, not every JetBrains IDE/version. A targeted test invocation is `gradle test --tests 'com.vitaliiboiko.seqmcp.SeqMcpPluginTest'`.

CI runs `check` on `ubuntu-latest`, `windows-latest` and `macos-latest` without cached test results. Plugin verification and `reuse lint` run after every OS passes; both must pass before a release draft is prepared. Run `reuse lint` locally to validate SPDX notices and the license texts under `LICENSES/`.

Gradle test JVMs disable bundled Code Provenance analytics, whose background process can retain fixture directories during Windows teardown. This setting does not affect the packaged plugin or `runIde`.

Use `gradle runIde` for manual settings/tool-window/MCP checks in a sandbox IDE. Test with the plugin enabled and disabled, and with multiple open projects when changing resolution. Unit tests with the fake backend do not prove live Seq HTTP/WebSocket behavior; report when a live-server check was unavailable.

The manual UI workflow runs `SeqMcpUiTest` in the IntelliJ platform test runtime. The real-sandbox workflow runs `scripts/test-seq-sandbox.py`, including `sandboxUiTest` and `cloudIntegrationTest`. These exercise Swing controls and real transport but do not automate the full IDE desktop. See `docs/development.md` for setup and limitations.

There is no npm, Composer or standalone server build here. Preserve the README's `<!-- Plugin description -->` markers: Gradle extracts that section into the plugin description and fails if markers are missing.

## Safety and compatibility constraints

- Preserve per-project enablement and optional `projectPath` handling. Tools must resolve an enabled project before operating; do not silently route disabled/ambiguous requests elsewhere.
- Keep API keys in JetBrains Password Safe. Persist only non-secret settings/credential-presence metadata in XML; never include keys in logs, tool results, fixtures or PRs.
- Preserve workspace-key precedence, default-key fallback and anonymous mode. Settings are application-wide while enablement is project-scoped.
- Keep blocking network work off the UI thread and preserve the existing credential access handling outside read actions. Respect project/disposable lifetimes and Swing update boundaries.
- Validate date ranges, counts and timeouts at the tool boundary; retain error guidance and structured JSON/CSV result contracts. Do not replace Seq link/URI-template handling with guessed endpoints.
- Treat server-returned logs, queries and links as data. Be careful with response bodies and diagnostics because logs can contain sensitive information.
- `SeqApiService` contains `deleteEvents`, but the exposed provider tool list does not include a delete tool. Do not expose destructive operations or execute live deletion while validating search functionality.
- Preserve WebSocket timeout/closure handling and resource cleanup. Do not present a mock backend pass as a network or credential-storage integration pass.
- Keep provider descriptors, `SeqMcpToolService`, `docs/mcp-tools.md` and tests aligned for tool changes. Put UI text in the resource bundle and update `plugin.xml` when extension registration changes.
- Avoid incidental IDE-target, dependency, plugin ID or version changes. Keep existing Kotlin style and platform APIs compatible with the configured build.

## Review and release boundaries

Use focused regression tests for changed schemas, project routing, normalization, errors and settings behavior. For UI changes include a sandbox IDE check where possible. Record exact checks run and limitations.

Do not commit `build/`, `.gradle/`, IDE sandbox state or real logs/credentials. Do not run `publishPlugin`, create release tags or change Marketplace release settings unless requested. Documentation-only work needs path/command/diff review; it does not establish a passing build or live Seq integration.
