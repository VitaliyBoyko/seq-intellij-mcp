<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Seq MCP Changelog

## [Unreleased]

### Added

- Nothing yet.

## [2026.1.1] - 2026-04-19

### Added

- `SeqWhoAmI` and `SeqCapabilities` MCP tools for inspecting the resolved Seq auth context, current user identity, and permalink/query capability checks.
- `SeqDescribeSqlDialect` MCP tool with working Seq SQL examples and guidance for common query pitfalls.

### Changed

- Improved Seq permalink handling to preflight auth capabilities, normalize missing-user failures, and return structured diagnostics plus fallback event metadata instead of raw backend errors.
- Improved Seq SQL ergonomics with clearer tool descriptions, embedded example queries, and structured parser guidance including Seq `Suggestion` and `Reasons` details when queries fail.
- Made workspace credential selection explicit in MCP tool schemas and diagnostics so it is clear when a workspace override or the default API key is being used.

## [2026.1.0] - 2026-04-19

### Added

- Initial public release of the Seq MCP JetBrains plugin.
- Built-in JetBrains MCP server integration exposing Seq tools for search, live event capture, SQL querying, filter conversion, signals, saved SQL queries, workspaces, dashboards, and event permalinks.
- Seq HTTP and WebSocket backend integration for event search, live streaming, SQL execution, metadata discovery, and permalink generation.
- Project-scoped `Enable for this project` toggle so Seq MCP tools are exposed only for the current IDE project when enabled.
- Seq settings UI with `SEQ_SERVER_URL`, default API key storage, and workspace-specific API key overrides backed by the IDE password store.
- Seq tool window with connection status, supported tool visibility, activity log, settings shortcut, fetch-last-10-events action, and clear-events action.
- Baseline platform test coverage for settings, project status reporting, and MCP tool request validation.

### Changed

- Target the IntelliJ Platform Gradle Plugin 2.x on IntelliJ IDEA Ultimate `2026.1`.
- Document built-in MCP server requirements, AI assistant MCP configuration, and current-project enablement in the README.
