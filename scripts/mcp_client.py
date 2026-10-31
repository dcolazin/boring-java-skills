#!/usr/bin/env python3
"""Minimal MCP stdio client for dogfooding the coverage-mcp server.

Speaks newline-delimited JSON-RPC over the server's stdio:
initialize -> initialized -> tools/list -> tools/call ...
"""
import json
import subprocess
import sys
import os

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAR = os.path.join(REPO, "coverage-mcp", "target", "coverage-mcp-1.0.0-SNAPSHOT.jar")
REPORT = os.path.join(REPO, "coverage-mcp", "target", "site", "jacoco", "jacoco.xml")


class McpClient:
    def __init__(self, command, cwd=None):
        self._stderr_file = open(os.path.join(REPO, "target", "mcp-server-stderr.log")
                                 if os.path.isdir(os.path.join(REPO, "target")) else "/tmp/mcp-server-stderr.log", "w")
        self.proc = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self._stderr_file,  # file, not PIPE: avoids unread-pipe deadlock
            text=True,
            bufsize=1,
            cwd=cwd,
        )
        self._id = 0

    def _send(self, method, params=None, notify=False):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if not notify:
            self._id += 1
            msg["id"] = self._id
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        return self._id if not notify else None

    def _recv(self, want_id):
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if msg.get("id") == want_id:
                return msg
        raise RuntimeError(f"server closed stdout before answering request {want_id}")

    def request(self, method, params=None):
        rid = self._send(method, params)
        return self._recv(rid)

    def notify(self, method, params=None):
        self._send(method, params, notify=True)

    def initialize(self):
        result = self.request("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "dogfood-script", "version": "1.0"},
        })
        self.notify("notifications/initialized")
        return result

    def list_tools(self):
        return self.request("tools/list")

    def call_tool(self, name, arguments):
        return self.request("tools/call", {"name": name, "arguments": arguments})

    def close(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        self.proc.kill()
        self.proc.wait()
        self._stderr_file.flush()
        self._stderr_file.close()

    def stderr_text(self):
        with open(self._stderr_file.name) as f:
            return f.read()


def tool_text(result):
    """Extract the concatenated text content of a tools/call result."""
    payload = result.get("result", {})
    texts = [c.get("text", "") for c in payload.get("content", []) if c.get("type") == "text"]
    return "\n".join(texts), payload.get("isError", False)


def main():
    sys.stdout.reconfigure(line_buffering=True)
    baseline = sys.argv[1] if len(sys.argv) > 1 else None
    client = McpClient(["java", "-jar", JAR], cwd=REPO)
    try:
        info = client.initialize()["result"]
        print("== initialize:", json.dumps(info.get("serverInfo", {})))

        tools = client.list_tools()["result"]["tools"]
        print("== tools/list:", [t["name"] for t in tools])

        args = {"repoPath": REPO, "reportPath": REPORT}
        if baseline:
            args["baseline"] = baseline
        res = client.call_tool("analyze_coverage", args)
        text, is_error = tool_text(res)
        print(f"== analyze_coverage (baseline={baseline or 'none'}, isError={is_error}):")
        print(text)

        if baseline:
            res = client.call_tool("list_changed_files", {"repoPath": REPO, "baseline": baseline})
            text, is_error = tool_text(res)
            print(f"== list_changed_files (baseline={baseline}, isError={is_error}):")
            print(text)
    finally:
        client.close()
        err = client.stderr_text()
        if err.strip():
            print("== server stderr:", err.strip()[:500], file=sys.stderr)


if __name__ == "__main__":
    main()
