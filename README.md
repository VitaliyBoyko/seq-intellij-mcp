# Seq MCP Plugin

<!-- Plugin description -->
`Seq MCP` is an IntelliJ IDEA Community plugin scaffold for bringing Seq observability workflows and Model Context Protocol integration into the IDE. The current state is intentionally minimal: a clean JetBrains template-based project with Seq MCP naming, plugin wiring, a placeholder tool window, and baseline tests so feature work can start on a solid IntelliJ Platform foundation.
<!-- Plugin description end -->

## Status

This repository is bootstrapped from the official [JetBrains IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) and targets IntelliJ IDEA Community via the IntelliJ Platform Gradle Plugin 2.x.

The current scaffold includes:

- Gradle Kotlin DSL build configured for IntelliJ IDEA Community `2025.2.6.1`
- Plugin metadata for `Seq MCP`
- Placeholder tool window and project service
- Startup activity and a basic platform test

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

## Project layout

```text
src/main/kotlin/com/vitaliiboiko/seqmcp
src/main/resources/META-INF/plugin.xml
src/main/resources/messages/MyBundle.properties
src/test/kotlin/com/vitaliiboiko/seqmcp
```
