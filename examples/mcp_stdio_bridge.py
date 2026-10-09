#!/usr/bin/env python3
"""
stdio <-> HTTP bridge for the Local LLM RE Assistant's MCP server.

Some MCP clients (e.g. Claude Desktop) can only launch MCP servers as stdio subprocesses. This tiny, dependency-free
script lets such a client talk to the Ghidra plugin's loopback HTTP endpoint:

    client  --(stdio, newline-delimited JSON-RPC)-->  this script  --(HTTP POST + bearer token)-->  Ghidra plugin

Configuration (environment variables, so the token never appears in a process list):
    GHIDRA_MCP_URL    default http://127.0.0.1:8765/mcp
    GHIDRA_MCP_TOKEN  required; copy it from Ghidra: Settings > MCP server > Copy token

It only ever connects to the URL you give it; refuses non-loopback hosts unless GHIDRA_MCP_ALLOW_REMOTE=1.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

URL = os.environ.get("GHIDRA_MCP_URL", "http://127.0.0.1:8765/mcp")
TOKEN = os.environ.get("GHIDRA_MCP_TOKEN", "")


def log(msg):
    sys.stderr.write("[ghidra-mcp-bridge] %s\n" % msg)
    sys.stderr.flush()


def main():
    host = urllib.parse.urlparse(URL).hostname or ""
    if host not in ("127.0.0.1", "localhost", "::1") and os.environ.get("GHIDRA_MCP_ALLOW_REMOTE") != "1":
        log("refusing non-loopback URL %s" % URL)
        return 2
    if not TOKEN:
        log("GHIDRA_MCP_TOKEN is not set")
        return 2
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # never use a proxy for loopback
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = urllib.request.Request(
            URL, data=line.encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json",
                     "Authorization": "Bearer " + TOKEN})
        try:
            with opener.open(req, timeout=660) as resp:
                body = resp.read().decode("utf-8")
                if resp.status == 202 or not body.strip():
                    continue
                sys.stdout.write(body.replace("\r", "").replace("\n", " ") + "\n")
                sys.stdout.flush()
        except urllib.error.HTTPError as e:
            log("HTTP %d from Ghidra plugin%s" % (e.code, " (check GHIDRA_MCP_TOKEN)" if e.code == 401 else ""))
            _reply_error(line, -32000, "Ghidra MCP endpoint returned HTTP %d" % e.code)
        except (urllib.error.URLError, OSError) as e:
            log("cannot reach %s: %s" % (URL, e))
            _reply_error(line, -32000, "Cannot reach Ghidra. Is the program open and the MCP server enabled?")
    return 0


def _reply_error(line, code, message):
    try:
        msg = json.loads(line)
    except ValueError:
        return
    if isinstance(msg, dict) and "id" in msg and "method" in msg:
        out = {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": code, "message": message}}
        sys.stdout.write(json.dumps(out) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    sys.exit(main())
