<!--
SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
SPDX-License-Identifier: MIT
License: see LICENSE in the repository root.
-->

# Seq MCP: Datalust Seq logs and SQL for IntelliJ IDEA and JetBrains IDEs

<!-- Plugin description -->
Search Datalust Seq logs from your IDE. **Seq MCP** is an IntelliJ IDEA plugin for structured log search, SQL queries and AI-assisted debugging in compatible JetBrains IDEs. It exposes 18 tools through the IDE's bundled Model Context Protocol (MCP) server, so a connected AI assistant can investigate application events alongside your project.

## Features

- Search structured log events by filter and time range, convert fuzzy text to strict filters, and wait for new matching events.
- Run Seq SQL queries and return structured JSON rows or CSV results.
- Inspect saved SQL queries, signals, workspaces and dashboards.
- Check server capabilities and credential permissions, and create event permalinks when supported by the server and credentials.
- Store default API keys and workspace credential overrides in JetBrains Password Safe.

## Requirements

- IntelliJ IDEA or a compatible JetBrains IDE with the bundled MCP Server plugin (`com.intellij.mcpServer`). The declared minimum IntelliJ Platform build is `263` (2026.3), with no upper bound. The build and Plugin Verifier target IntelliJ IDEA Ultimate `263.7307.200`; this does not establish compatibility with every other IDE or later build.
- A reachable Datalust Seq server containing the logs you want to investigate. Use an API key with permission to read the required data when the server requires authentication; anonymous access depends on server configuration. Available operations depend on server capabilities and credential permissions.
- An MCP-compatible AI assistant connected to the IDE's bundled MCP server. This plugin extends that server; it does not run a standalone MCP server or install Seq.

## Installation and setup

1. Install **Seq MCP** from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31358-seq-mcp) in a compatible IDE.
2. Enable the IDE's bundled MCP server and connect your assistant using the [JetBrains MCP server setup guide](https://www.jetbrains.com/help/idea/mcp-server.html).
3. Ask your assistant to call `SeqEnableIntegration` for the project you want to investigate, or open `Settings | Tools | Seq MCP` and check `Enable for this project`.
4. Ask your assistant to call `SeqConfigureConnection` with the server URL and credentials, or enter them in the `SEQ_SERVER_URL`, `SEQ_API_KEY` and workspace-specific key fields in IDE settings and apply. These fields are plugin settings, not required environment variables.
5. Wait for project indexing to finish, then ask your connected assistant to search recent errors or run a Seq SQL query.

See the [MCP tools, query examples and troubleshooting](https://github.com/VitaliyBoyko/seq-intellij-mcp/blob/master/docs/mcp-tools.md). Visit the [project repository](https://github.com/VitaliyBoyko/seq-intellij-mcp) or [report an issue](https://github.com/VitaliyBoyko/seq-intellij-mcp/issues) with your IDE build, plugin version and failing tool name, omitting API keys and private log data.
<!-- Plugin description end -->

[![Version](http://phpstorm.espend.de/badge/31358/version)](https://plugins.jetbrains.com/plugin/31358-seq-mcp)
[![Downloads](http://phpstorm.espend.de/badge/31358/downloads)](https://plugins.jetbrains.com/plugin/31358-seq-mcp)

## Development

See the [development and testing guide](docs/development.md) for building the plugin and running tests.

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
