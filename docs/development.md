<!--
SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
SPDX-License-Identifier: MIT
License: see LICENSE in the repository root.
-->

# Development and testing

Use Java 25 and the Gradle wrapper included in this repository.

```bash
./gradlew runIde
./gradlew check
./gradlew verifyPlugin
```

`verifyPlugin` checks compatibility against the same IDEA build used to compile the plugin (`263.7307.200`).

## Transport tests

`./gradlew check` runs platform tests and the production HTTP/WebSocket client against a local TLS test server. No Seq installation or cloud credentials are needed. Coverage includes base paths, escaped filters, credential selection, SQL, TLS failures, rejected WebSocket upgrades, abnormal closes, response deadlines, cancellation, and credential forwarding protection.

HTTP requests have a 30-second default deadline, including response body transfer. An explicit SQL `timeoutSeconds` sets the query request deadline to that value plus five seconds. WebSocket handshakes have a five-second deadline. Search timeout discards incomplete results and reports `SEARCH_TIMEOUT`; live capture finishes its five-second observation window normally. Search accepts at most 1,000 events. WebSocket text is limited to 1,048,576 characters per event and 4,194,304 characters per result; HTTP responses are limited to 8 MiB. Oversized responses fail with `RESULT_TOO_LARGE` rather than silently truncating data.

## Real sandbox UI connection test

On Linux with Docker, Python 3 and Java 25, run:

```bash
python3 scripts/test-seq-sandbox.py
```

The runner uses pinned official Seq/Seq CLI images, accepts the Seq container EULA, generates temporary credentials, binds the sandbox to loopback on a random port, and removes the container afterward. It runs both `sandboxUiTest` and `cloudIntegrationTest`. No existing Seq data is used or deleted.

`sandboxUiTest` exercises the real Settings controls, Password Safe, the production client and the **Test connection** button against an authenticated Seq server. It checks success, rejection of an invalid key, and recovery after correcting the key. It runs in the IntelliJ platform test runtime; it does not automate the full IDE desktop.

For an existing sandbox, set `SEQ_TEST_SERVER_URL` and `SEQ_TEST_API_KEY` and run `./gradlew sandboxUiTest --no-configuration-cache`. HTTPS is required unless `SEQ_TEST_ALLOW_LOCAL_HTTP=true` explicitly enables loopback HTTP. This UI test uses the production JVM trust store; the client-only cloud tests additionally support `SEQ_TEST_CA_CERT`. Missing configuration fails the test instead of skipping it. The sandbox UI test is excluded from ordinary `test`/`check` and runs in the dedicated real-sandbox CI workflow and the manual cloud workflow.

## Cloud integration tests

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

