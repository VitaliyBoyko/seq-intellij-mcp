<!--
SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
SPDX-License-Identifier: MIT
License: see LICENSE in the repository root.
-->

# Seq MCP: structured log search for JetBrains IDEs

<!-- Plugin description -->
`Seq MCP` connects JetBrains IDEs to Seq structured logs through the Model Context Protocol (MCP). A connected AI assistant can search events, run Seq SQL queries, inspect signals and dashboards, and create event permalinks through JetBrains' built-in MCP server.

To work properly, the IDE must include the built-in MCP server, the AI assistant must be configured to use that server, and `Seq MCP` must be enabled for the current project in `Settings | Tools | Seq MCP`.

## Support the project

If you find this plugin helpful and want to support its development, consider buying me a coffee:

<table align="center" style="border-collapse: collapse; width: 100%; text-align: center;">
  <tbody>
  </tbody>
  <tfoot>
    <tr style="background-color: #f9f9f9;">
      <td style="padding: 20px;">
        <a href="https://buymeacoffee.com/vitalii_b" style="text-decoration: none; color: inherit;">
<pre style="display: inline-block; margin: 10px 0; font-family: monospace;">
    ( (
     ) )
  ........
  |      |]
  \      /
   `----'
 Buy Me a Coffee
</pre>
        </a>
      </td>
    </tr>
  </tfoot>
</table>
<!-- Plugin description end -->

[![Version](http://phpstorm.espend.de/badge/31358/version)](https://plugins.jetbrains.com/plugin/31358-seq-mcp)
[![Downloads](http://phpstorm.espend.de/badge/31358/downloads)](https://plugins.jetbrains.com/plugin/31358-seq-mcp)

## Features

- Search structured events, convert fuzzy text to strict filters, and wait for new events.
- Run Seq SQL queries with structured JSON or CSV results.
- Inspect saved queries, signals, workspaces and dashboards.
- Check server capabilities and create event permalinks.
- Configure a default API key and workspace credential overrides, stored in JetBrains Password Safe.

See the [MCP tools and examples](docs/mcp-tools.md) for all 15 tools, query arguments and troubleshooting. The build targets IntelliJ IDEA Ultimate `263.7307.44`; compatibility requires an IDE with the bundled MCP server.

## Requirements

- IntelliJ IDEA Ultimate `2026.3` (build `263.7307.44` or newer) or another compatible JetBrains IDE build that includes the bundled MCP server plugin required by this project
- An AI assistant configured to connect to the IDE MCP server
- The IDE's built-in MCP server added to the AI assistant MCP configuration
- `Seq MCP` enabled for the current project in `Settings | Tools | Seq MCP`
- `SEQ_SERVER_URL` configured; `SEQ_API_KEY` is optional but recommended
- Project indexing finished before invoking MCP-assisted flows

## Installation and setup

1. Open a compatible JetBrains IDE.
2. Install `Seq MCP` from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31358-seq-mcp) or run it from source with `./gradlew runIde`.
3. Make sure the IDE's built-in MCP server is available to the AI assistant you want to use.
4. Add the IDE MCP server entry to the assistant's MCP configuration.
5. Open `Settings | Tools | Seq MCP`.
6. Leave `Enable for this project` checked for the current project.
7. Configure `SEQ_SERVER_URL` and any default or workspace-specific API keys you want to use.
8. Wait for indexing to finish before invoking Seq MCP tools.

## MCP usage notes

- Seq MCP tools are exposed through the built-in JetBrains MCP server, not a standalone server from this repository.
- Tools operate against the currently opened IDE project.
- If the plugin is disabled for the current project, the Seq MCP tool window is hidden and MCP tools are not exposed.

## Development

Use Java 25 and the Gradle wrapper included in this repository.

```bash
./gradlew runIde
./gradlew check
./gradlew verifyPlugin
```

`verifyPlugin` checks compatibility against the same IDEA build used to compile the plugin (`263.7307.44`).

### Transport tests

`./gradlew check` runs platform tests and the production HTTP/WebSocket client against a local TLS test server. No Seq installation or cloud credentials are needed. Coverage includes base paths, escaped filters, credential selection, SQL, TLS failures, rejected WebSocket upgrades, abnormal closes, response deadlines, cancellation, and credential forwarding protection.

HTTP requests have a 30-second default deadline, including response body transfer. An explicit SQL `timeoutSeconds` sets the query request deadline to that value plus five seconds. WebSocket handshakes have a five-second deadline. Search timeout discards incomplete results and reports `SEARCH_TIMEOUT`; live capture finishes its five-second observation window normally. Search accepts at most 1,000 events. WebSocket text is limited to 1,048,576 characters per event and 4,194,304 characters per result; HTTP responses are limited to 8 MiB. Oversized responses fail with `RESULT_TOO_LARGE` rather than silently truncating data.

### Cloud integration tests

This tests the connection **from the plugin's production client to a remote Seq server**. It does not test a cloud assistant's connection to the JetBrains MCP server.

Use a dedicated authenticated test instance. The suite creates uniquely tagged synthetic events with `Application = 'seq-mcp-integration-tests'`; it does not delete events or change server settings. Existing retention policies can remove these events later.

For a disposable local sandbox, use Datalust's [official Seq Docker image and setup guide](https://datalust.co/docs/getting-started?platform=docker), enable authentication, and create separate [Read and Ingest API keys](https://datalust.co/docs/api-keys). Bind the test instance to loopback. Seq's [HTTP API guide](https://datalust.co/docs/using-the-http-api) and [endpoint reference](https://datalust.co/docs/server-http-api) describe authentication and endpoint permissions.

Configure these environment variables:

| Variable | Required value |
| --- | --- |
| `SEQ_TEST_SERVER_URL` | HTTPS base URL for the full Seq UI/API, e.g. `https://seq-test.example.com/` or `https://example.com/seq/` |
| `SEQ_TEST_API_KEY` | API key with **Read** permission |
| `SEQ_TEST_INGEST_API_KEY` | API key with **Ingest** permission for synthetic events |
| `SEQ_TEST_CA_CERT` | Optional path to a PEM CA certificate bundle for a private test deployment; hostname verification remains enabled |

For example, in Bash:

```bash
export SEQ_TEST_SERVER_URL='https://seq-test.example.com/'
read -rsp 'Seq read API key: ' SEQ_TEST_API_KEY; echo
read -rsp 'Seq ingest API key: ' SEQ_TEST_INGEST_API_KEY; echo
export SEQ_TEST_API_KEY SEQ_TEST_INGEST_API_KEY
./gradlew cloudIntegrationTest --no-configuration-cache
unset SEQ_TEST_API_KEY SEQ_TEST_INGEST_API_KEY
```

The task always runs when requested and fails if configuration is missing. It is excluded from `test` and `check`. Credentials are read from the test process environment; use `--no-configuration-cache` to avoid persisting the task's environment in Gradle's configuration cache.

The suite verifies authenticated search, SQL counts for known events, live WebSocket capture, and rejection of invalid credentials. Reports are written to `build/reports/tests/cloudIntegrationTest/index.html`. The live test emits events repeatedly while connecting to avoid a handshake/ingestion race.

For a loopback-only local Seq instance, HTTP can be enabled explicitly with `SEQ_TEST_ALLOW_LOCAL_HTTP=true`. Remote endpoints require HTTPS. A reverse proxy must forward WebSocket upgrades; set Seq's `SEQ_API_CANONICALURI` to its external base URL, including any path prefix. Discovery links pointing to another origin are rejected before credentials are sent. See [Seq configuration](https://datalust.co/docs/json-config) and [NGINX WebSocket proxying](https://nginx.org/en/docs/http/websocket.html).

To run against a cloud deployment in GitHub Actions, create an environment named `seq-cloud-test` with variable `SEQ_TEST_SERVER_URL` and secrets `SEQ_TEST_API_KEY` and `SEQ_TEST_INGEST_API_KEY`, then manually run the **Cloud integration** workflow. The endpoint must be reachable from the runner. Ordinary pull-request builds only run the local transport tests.

## License

Author contributions are licensed under the [MIT License](LICENSE). Third-party code from the JetBrains template and Gradle wrapper retains its [Apache-2.0 license](LICENSES/Apache-2.0.txt); see [NOTICE](NOTICE).

## Related projects and support

[Magento Seq](https://github.com/VitaliyBoyko/magento-seq) sends local Magento logs and custom events into Seq. It is an optional event producer; this plugin can query Seq data from other applications too. For Magento code navigation and generation, see the separate [Magento 2 and Adobe Commerce PhpStorm plugin](https://github.com/VitaliyBoyko/magento2-phpstorm-plugin).

See [releases](https://github.com/VitaliyBoyko/seq-intellij-mcp/releases), the [changelog](CHANGELOG.md), or [report an issue](https://github.com/VitaliyBoyko/seq-intellij-mcp/issues) with your IDE build, plugin version and failing tool name.

## Repository navigation for AI agents

See [AGENTS.md](AGENTS.md) for the architecture map, source entry points, validation commands and contribution constraints.
