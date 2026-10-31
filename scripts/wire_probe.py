#!/usr/bin/env python3
"""Probe: full MCP sequence with raw wire capture to files (survives timeout)."""
import subprocess, json, sys, os

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAR = f"{REPO}/coverage-mcp/target/coverage-mcp-1.0.0-SNAPSHOT.jar"
REPORT = f"{REPO}/coverage-mcp/target/site/jacoco/jacoco.xml"

out_file = open("/tmp/mcp-wire-stdout.log", "wb")
err_file = open("/tmp/mcp-wire-stderr.log", "wb")

proc = subprocess.Popen(
    ["java", "-jar", JAR],
    stdin=subprocess.PIPE, stdout=out_file, stderr=err_file,
    bufsize=1)

def send(msg):
    proc.stdin.write((json.dumps(msg) + "\n").encode())
    proc.stdin.flush()

# raw reader on the file (we reopen for reading separately)
reader = open("/tmp/mcp-wire-stdout.log", "rb")

def request(method, params, mid):
    send({"jsonrpc": "2.0", "method": method, "id": mid, "params": params})
    import time
    deadline = time.time() + 25
    while time.time() < deadline:
        line = reader.readline()
        if not line:
            time.sleep(0.2)
            continue
        try:
            m = json.loads(line)
        except Exception:
            print(f"[wire] non-JSON line: {line[:120]!r}", flush=True)
            continue
        if m.get("id") == mid:
            return m
    return None

r = request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                           "clientInfo": {"name": "probe", "version": "1.0"}}, 1)
print("initialize:", "OK" if r else "TIMEOUT", flush=True)

send({"jsonrpc": "2.0", "method": "notifications/initialized"})

r = request("tools/list", None, 2)
print("tools/list:", "OK" if r else "TIMEOUT", flush=True)

r = request("tools/call", {"name": "analyze_coverage",
                           "arguments": {"repoPath": REPO, "reportPath": REPORT}}, 3)
if r:
    payload = r.get("result", {})
    print("tools/call: OK isError=", payload.get("isError"), flush=True)
    texts = [c.get("text", "") for c in payload.get("content", [])]
    print("text length:", sum(len(t) for t in texts), flush=True)
    print(texts[0][:400] if texts else "(no text)", flush=True)
else:
    print("tools/call: TIMEOUT — no response with id=3", flush=True)

proc.kill()
proc.wait()
out_file.close(); err_file.close(); reader.close()
size = os.path.getsize("/tmp/mcp-wire-stdout.log")
print(f"wire stdout bytes: {size}", flush=True)
