# SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
# SPDX-License-Identifier: MIT
# License: see LICENSE in the repository root.

"""Run real Seq integration tests in a disposable authenticated Docker sandbox (Linux)."""

import os
import secrets
import subprocess
import time
import urllib.request
from pathlib import Path

SEQ_IMAGE = "datalust/seq@sha256:76f6ecdf9f742442f5b7e31eb9b94084b952af0575049d5c1e9d9101a6c8100f"
CLI_IMAGE = "datalust/seqcli@sha256:09cf303a4d4c635a583bea9e28595bfe85b08ed158a845576698816a00739ef0"


def capture(args, **kwargs):
    result = subprocess.run(args, text=True, capture_output=True, **kwargs)
    if result.returncode:
        # CLI output and arguments can contain generated credentials.
        raise RuntimeError(f"Sandbox command failed: {args[0]} {args[1]} (credential output suppressed)")
    return result.stdout.strip()


def main():
    os.chdir(Path(__file__).resolve().parent.parent)
    name = "seq-mcp-ui-" + secrets.token_hex(5)
    password = secrets.token_urlsafe(32)
    started = False
    try:
        password_hash = capture(["docker", "run", "--rm", "-i", SEQ_IMAGE, "config", "hash"], input=password)
        environment = os.environ.copy()
        environment["SEQ_FIRSTRUN_ADMINPASSWORDHASH"] = password_hash
        capture([
            "docker", "run", "-d", "--rm", "--name", name,
            "-e", "ACCEPT_EULA=Y", "-e", "SEQ_FIRSTRUN_ADMINPASSWORDHASH",
            "-e", "SEQ_FIRSTRUN_REQUIREAUTHENTICATIONFORHTTPINGESTION=true",
            "-p", "127.0.0.1::80", SEQ_IMAGE,
        ], env=environment)
        started = True
        port = capture(["docker", "port", name, "80/tcp"]).split(":")[-1]
        url = "http://127.0.0.1:" + port
        for _ in range(60):
            try:
                with urllib.request.urlopen(url + "/health", timeout=2):
                    break
            except (OSError, urllib.error.URLError):
                time.sleep(1)
        else:
            raise RuntimeError("Seq sandbox did not become healthy")

        def create_key(title, permissions):
            return capture([
                "docker", "run", "--rm", "--network", "host", "-i", CLI_IMAGE,
                "apikey", "create", "-s", url, "-t", title, "--permissions", permissions,
                "--connect-username", "admin", "--connect-password-stdin",
            ], input=password)

        environment = os.environ.copy()
        environment.update(
            SEQ_TEST_SERVER_URL=url,
            SEQ_TEST_API_KEY=create_key("UI test read", "read"),
            SEQ_TEST_INGEST_API_KEY=create_key("Integration test ingest", "ingest"),
            SEQ_TEST_ALLOW_LOCAL_HTTP="true",
        )
        environment.pop("SEQ_TEST_CA_CERT", None)
        print("Authenticated disposable Seq sandbox ready; running real-server tests.", flush=True)
        result = subprocess.run([
            "gradle", "sandboxUiTest", "cloudIntegrationTest", "--no-configuration-cache",
        ], env=environment)
        return result.returncode
    finally:
        if started:
            subprocess.run(["docker", "stop", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print("Disposable Seq sandbox stopped and removed.", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
