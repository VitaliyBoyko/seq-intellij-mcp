# Seq MCP tools, query examples and troubleshooting

[Seq MCP](../README.md) exposes the tools below through the JetBrains IDE MCP server. Configure the Seq server URL and credentials in `Settings | Tools | Seq MCP`, enable the plugin for the project, and connect your assistant to the IDE server before trying these examples.

## Available tools

| Tool | Purpose |
| --- | --- |
| `SeqSearch` | Search events with a Seq filter; supports count, time window, signal and pagination arguments. |
| `SeqQuerySql` | Execute Seq SQL and return JSON rows or CSV. |
| `SeqDescribeSqlDialect` | Inspect Seq SQL syntax guidance before composing queries. |
| `SeqToStrictFilter` | Convert fuzzy search text into a strict filter expression. |
| `SeqWaitForEvents` | Wait for new events matching a filter. |
| `SignalList` | List saved signals. |
| `SeqWhoAmI` | Inspect the identity/capability information available for the configured credentials. |
| `SeqCapabilities` | Inspect server capabilities. |
| `SeqSqlQueryList` | List saved SQL queries. |
| `SeqSqlQueryGet` | Read a saved SQL query by ID. |
| `SeqWorkspaceList` | List workspaces. |
| `SeqWorkspaceGet` | Read workspace metadata by ID. |
| `SeqDashboardList` | List dashboards. |
| `SeqDashboardGet` | Read a dashboard by ID. |
| `SeqCreatePermalink` | Create a link for an event; behavior depends on available server capabilities. |

The implementation and argument schemas are in [SeqMcpToolsProvider.kt](../src/main/kotlin/com/vitaliiboiko/seqmcp/mcp/SeqMcpToolsProvider.kt). Tool names may be prefixed by your MCP client when displayed.

## Search errors

Ask your connected assistant to call `SeqSearch` with these arguments:

```json
{
  "filter": "@Level = 'Error'",
  "count": 20
}
```

Use `fromDateUtc` and `toDateUtc` for an explicit ISO-8601 UTC window, or `afterId` to paginate after a returned event ID. Start with a small count when investigating logs.

## Run a Seq SQL query

Pass this object to `SeqQuerySql`:

```json
{
  "query": "select @Timestamp, @Level, @Message from stream where @Level = 'Error' order by @Timestamp desc limit 20",
  "format": "json"
}
```

Seq event queries use `from stream` and `limit`. Use `SeqDescribeSqlDialect` for syntax guidance. Set `format` to `csv` for CSV output; use `rangeStartUtc` and `rangeEndUtc` for a query time window.

## Inspect Magento logs

The optional [Magento Seq module](https://github.com/VitaliyBoyko/magento-seq) sends local Monolog records and custom PHP/browser events to Seq. After installing and enabling that module, reproduce a Magento request and ask your assistant to search its resulting events. This plugin also works with Seq events produced by other applications; it does not install Magento instrumentation.

## FAQ

### Is this a standalone MCP server?

No. It registers tools with the IDE's bundled MCP server. There is no standalone `npx` command or separate MCP server process to configure from this repository.

### Where are credentials configured?

`SEQ_SERVER_URL` and `SEQ_API_KEY` are labels in the plugin settings. API keys are stored in JetBrains Password Safe. A tool's optional `workspace` argument selects a configured credential override; unmatched values use the default key. This argument is not itself a filter restricting events to a Seq workspace.

### Why are tools missing or rejected?

Check the exact IDE build against the [requirements](../README.md#requirements), the bundled MCP server, the assistant connection, project enablement and indexing. For API errors, verify the configured server URL, API key permissions and server capabilities with `SeqCapabilities`. Available server features and credentials can limit operations.

### Is a blank result proof that no error happened?

No. Verify the event producer, query filter, time window and credentials. The plugin only queries events available to the configured Seq server and identity.

For problems, [open an issue](https://github.com/VitaliyBoyko/seq-intellij-mcp/issues) with the IDE build, plugin version, tool name and a reproducible request with private log data and API keys removed.
