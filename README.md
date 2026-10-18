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

See the [MCP tools and examples](docs/mcp-tools.md) for all 15 tools, query arguments and troubleshooting. The build targets IntelliJ IDEA Ultimate `263.3889.65`; compatibility requires an IDE with the bundled MCP server.

## Requirements

- IntelliJ IDEA Ultimate `2026.3` (build `263.3889.65` or newer) or another compatible JetBrains IDE build that includes the bundled MCP server plugin required by this project
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

`verifyPlugin` checks compatibility against the same IDEA build used to compile the plugin (`263.3889.65`).

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Related projects and support

[Magento Seq](https://github.com/VitaliyBoyko/magento-seq) sends local Magento logs and custom events into Seq. It is an optional event producer; this plugin can query Seq data from other applications too. For Magento code navigation and generation, see the separate [Magento 2 and Adobe Commerce PhpStorm plugin](https://github.com/VitaliyBoyko/magento2-phpstorm-plugin).

See [releases](https://github.com/VitaliyBoyko/seq-intellij-mcp/releases), the [changelog](CHANGELOG.md), or [report an issue](https://github.com/VitaliyBoyko/seq-intellij-mcp/issues) with your IDE build, plugin version and failing tool name.
