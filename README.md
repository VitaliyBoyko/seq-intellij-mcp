<!--
SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
SPDX-License-Identifier: MIT
License: see LICENSE in the repository root.
-->

# Seq MCP: Datalust Seq logs and SQL for IntelliJ IDEA and JetBrains IDEs

<!-- Plugin description -->
Search Datalust Seq logs from your IDE. **Seq MCP** is an IntelliJ IDEA plugin for structured log search, SQL queries and AI-assisted debugging in compatible JetBrains IDEs. It exposes 15 tools through the IDE's bundled Model Context Protocol (MCP) server, so a connected AI assistant can investigate application events alongside your project.

## Features

- Search structured log events by filter and time range, convert fuzzy text to strict filters, and wait for new matching events.
- Run Seq SQL queries and return structured JSON rows or CSV results.
- Inspect saved SQL queries, signals, workspaces and dashboards.
- Check server capabilities and credential permissions, and create event permalinks when supported by the server and credentials.
- Store default API keys and workspace credential overrides in JetBrains Password Safe.

## Requirements

- IntelliJ IDEA or a compatible JetBrains IDE with the bundled MCP Server plugin (`com.intellij.mcpServer`). The declared minimum IntelliJ Platform build is `263` (2026.3), with no upper bound. The build and Plugin Verifier target IntelliJ IDEA Ultimate `263.7307.44`; this does not establish compatibility with every other IDE or later build.
- A reachable Datalust Seq server containing the logs you want to investigate. Use an API key with permission to read the required data when the server requires authentication; anonymous access depends on server configuration. Available operations depend on server capabilities and credential permissions.
- An MCP-compatible AI assistant connected to the IDE's bundled MCP server. This plugin extends that server; it does not run a standalone MCP server or install Seq.

## Installation and setup

1. Install **Seq MCP** from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31358-seq-mcp) in a compatible IDE.
2. Enable the IDE's bundled MCP server and connect your assistant using the [JetBrains MCP server setup guide](https://www.jetbrains.com/help/idea/mcp-server.html).
3. Open `Settings | Tools | Seq MCP` and check `Enable for this project` for the project you want to investigate.
4. Enter your Seq server's base URL in `SEQ_SERVER_URL` and configure `SEQ_API_KEY` or workspace-specific keys as needed. These are plugin settings fields, not required environment variables. Apply the settings.
5. Wait for project indexing to finish, then ask your connected assistant to search recent errors or run a Seq SQL query.

See the [MCP tools, query examples and troubleshooting](https://github.com/VitaliyBoyko/seq-intellij-mcp/blob/master/docs/mcp-tools.md). Visit the [project repository](https://github.com/VitaliyBoyko/seq-intellij-mcp) or [report an issue](https://github.com/VitaliyBoyko/seq-intellij-mcp/issues) with your IDE build, plugin version and failing tool name, omitting API keys and private log data.
<!-- Plugin description end -->

[![Version](http://phpstorm.espend.de/badge/31358/version)](https://plugins.jetbrains.com/plugin/31358-seq-mcp)
[![Downloads](http://phpstorm.espend.de/badge/31358/downloads)](https://plugins.jetbrains.com/plugin/31358-seq-mcp)

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

## Related projects

[Magento Seq](https://github.com/VitaliyBoyko/magento-seq) sends local Magento logs and custom events into Seq. It is an optional event producer; this plugin can query Seq data from other applications too. For Magento code navigation and generation, see the separate [Magento 2 and Adobe Commerce PhpStorm plugin](https://github.com/VitaliyBoyko/magento2-phpstorm-plugin).

See [releases](https://github.com/VitaliyBoyko/seq-intellij-mcp/releases) and the [changelog](CHANGELOG.md) for updates.

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

## Repository navigation for AI agents

See [AGENTS.md](AGENTS.md) for the architecture map, source entry points, validation commands and contribution constraints.
