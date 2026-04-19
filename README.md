# Seq MCP Plugin

<!-- Plugin description -->
`Seq MCP` is an IntelliJ IDEA plugin scaffold for bringing Seq observability workflows and Model Context Protocol integration into the IDE. It exposes Seq-focused tools through JetBrains' built-in MCP server so AI assistants can work against the currently opened project.

To work properly, the IDE must include the built-in MCP server, the AI assistant must be configured to use that server, and `Seq MCP` must be enabled for the current project in `Settings | Tools | Seq MCP`.
<!-- Plugin description end -->

## Status

This repository is bootstrapped from the official [JetBrains IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) and targets IntelliJ IDEA Ultimate via the IntelliJ Platform Gradle Plugin 2.x.

The current scaffold includes:

- Gradle Kotlin DSL build configured for IntelliJ IDEA Ultimate `2026.1`
- Dependencies on the bundled JetBrains MCP server and AI assistant integrations
- Plugin metadata for `Seq MCP`
- Project-level enablement setting, placeholder tool window, and project service
- Startup activity and a basic platform test

## Requirements

- IntelliJ IDEA Ultimate `2026.1` or another compatible JetBrains IDE build that includes the bundled MCP server plugin required by this project
- JetBrains AI Assistant available in the IDE
- The IDE's built-in MCP server added to the AI assistant MCP configuration
- `Seq MCP` enabled for the current project in `Settings | Tools | Seq MCP`
- `SEQ_SERVER_URL` configured; `SEQ_API_KEY` is optional but recommended
- Project indexing finished before invoking MCP-assisted flows

## Installation and setup

1. Open the project in a compatible JetBrains IDE.
2. Install the plugin or run it from source with `./gradlew runIde`.
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

## Planned next steps

The next implementation phase is intended to cover the basic Seq MCP flows inspired by the reference server at [willibrandon/seq-mcp-server](https://github.com/willibrandon/seq-mcp-server):

- Seq connection configuration
- Event search
- Live event streaming / tailing
- Basic signal discovery

## Development

Use Java 21 and the Gradle wrapper included in this repository.

```bash
./gradlew runIde
./gradlew check
./gradlew verifyPlugin
```

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

## Project layout

```text
src/main/kotlin/com/vitaliiboiko/seqmcp
src/main/resources/META-INF/plugin.xml
src/main/resources/messages/MyBundle.properties
src/test/kotlin/com/vitaliiboiko/seqmcp
```

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
