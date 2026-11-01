<!--
SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
SPDX-License-Identifier: MIT
License: see LICENSE in the repository root.
-->

<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Seq MCP Changelog

## [Unreleased]

### Added

- HTTPS and WebSocket transport tests using the production Seq client, covering authentication, proxy failures, TLS, timeouts, cancellation, base paths, and escaped filters.
- Opt-in `cloudIntegrationTest` task and manual GitHub Actions workflow for authenticated search, SQL, live capture, and invalid-credential checks against a remote Seq instance.
- Cloud test setup instructions and explicit remote connection diagnostics.

### Changed

- Added HTTP response deadlines and cancellable network requests; SQL request deadlines honor the configured query timeout.
- Treat incomplete searches and abnormal WebSocket closes as failures instead of successful partial results, and avoid waiting indefinitely during stream cleanup.
- Preserve percent-encoded paths and filters when converting HTTP URLs to WebSocket URLs.
- Reject API discovery links outside the configured server origin before forwarding credentials.
- Limit searches to 1,000 events, bound HTTP responses and WebSocket results, and apply stream backpressure.
- Preserve MCP cancellation and correct SQL row output schemas.
- Update the build to Gradle 9.8.2 with distribution checksum verification and IntelliJ Platform Gradle Plugin 2.19.0.

### Fixed

- Accept HTTP(S) server URLs and API links regardless of scheme letter casing.
- Make the untrusted-certificate test independent of platform test execution order.
- Refresh the IntelliJ IDEA 2026.3 build used for packaging after the previous EAP expired.

## [2026.1.6] - 2026-09-13

### Changed

- Updated compatibility for IntelliJ Platform build `263.3889.65` and removed deprecated and experimental API usages.

## [2026.1.5] - 2026-05-31

### Changed

- Raised the minimum JetBrains IDE compatibility to IntelliJ Platform build `262` and left the upper bound open so future incompatibilities are caught by verification.

## [2026.1.4] - 2026-05-23

### Changed

- Simplified the Seq MCP tool window action layout by removing the `Open Settings` button from the top row, keeping `Clear Log` in the log header, and aligning the remaining actions with more consistent button styling.

## [2026.1.3] - 2026-05-08

### Changed

- Fixed `SeqCreatePermalink` capability detection for anonymous Seq servers so the plugin no longer advertises permalink creation as supported when `/api/users/current` resolves a display identity but `/api/permalinks` still rejects the request with `A valid user id is required.`
- Improved permalink failure diagnostics to explain the anonymous-auth limitation directly and steer users toward an authenticated user-scoped credential or API key when permalink creation is required.

## [2026.1.2] - 2026-04-27

### Changed

- Added optional `projectPath` to every Seq MCP tool input schema and taught runtime project resolution to honor it when provided, so JetBrains MCP clients like PhpStorm can resolve the target project before dispatch instead of failing at schema validation.

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
