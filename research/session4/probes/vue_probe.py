#!/usr/bin/env python3
"""Raw LSP probe for vue-language-server (Volar 2.2.12): does textDocument/definition
answer for a '@/...' import in a real project file? Separates server health from
lsp4ij-side issues. Read-only: spawns its own server process."""
import json, os, subprocess, sys, time, threading

SERVER = os.path.expanduser(
    "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/.bin/vue-language-server")
ROOT = "file:///var/www/jpbest"
VUE = "/var/www/jpbest/resources/js/pages/Echarts.vue"

text = open(VUE, encoding="utf-8").read()
lines = text.split("\n")
# line 4 (1-based) = index 3: import Layout from '@/layouts/Layout.vue';
target = lines[3]
col = target.index("@/layouts/Layout.vue") + 3  # middle of the path string
print(f"probe target: line 4 char {col}: …{target[max(0,col-12):col+16]}…", flush=True)

p = subprocess.Popen(["/usr/bin/node", SERVER, "--stdio"],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True, bufsize=0)

_id = [0]
def send(method, params, is_notification=False):
    msg = {"jsonrpc": "2.0", "method": method, "params": params}
    if not is_notification:
        _id[0] += 1
        msg["id"] = _id[0]
    data = json.dumps(msg)
    p.stdin.write(f"Content-Length: {len(data.encode())}\r\n\r\n{data}")
    p.stdin.flush()
    return msg.get("id")

def read_msg(timeout):
    got = {}
    def reader():
        headers = {}
        while True:
            line = p.stdout.readline()
            if not line:
                return
            line = line.strip()
            if not line:
                break
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
        n = int(headers.get("content-length", 0))
        if n:
            got["body"] = p.stdout.read(n)
    t = threading.Thread(target=reader, daemon=True)
    t.start()
    t.join(timeout)
    return json.loads(got["body"]) if "body" in got else None

def wait_for(rid, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        m = read_msg(min(5, deadline - time.time()))
        if m is None:
            continue
        if m.get("id") == rid:
            return m
    return None

# 1) initialize
rid = send("initialize", {
    "processId": os.getpid(),
    "rootUri": ROOT,
    "workspaceFolders": [{"uri": ROOT, "name": "jpbest"}],
    "clientInfo": {"name": "probe", "version": "0"},
    "capabilities": {"textDocument": {"definition": {"linkSupport": False}}},
    "initializationOptions": {"typescript": {"tsdk": os.path.expanduser(
        "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib")}},
})
r = wait_for(rid, 60)
if r is None:
    print("FAIL: no initialize response in 60s"); p.kill(); sys.exit(1)
caps = list((r.get("result") or {}).get("capabilities", {}).keys())
print(f"initialize OK; server capabilities: {sorted(caps)[:12]}", flush=True)

send("initialized", {}, is_notification=True)

# 2) didOpen
send("textDocument/didOpen", {"textDocument": {
    "uri": "file://" + VUE, "languageId": "vue", "version": 1, "text": text}},
    is_notification=True)
time.sleep(8)  # let project load

# 3) definition on the import path
rid2 = send("textDocument/definition", {
    "textDocument": {"uri": "file://" + VUE},
    "position": {"line": 3, "character": col},
})
r2 = wait_for(rid2, 45)
if r2 is None:
    print("FAIL: no definition response in 45s (server hung or feature dead)")
else:
    res = r2.get("result")
    print("definition result:", json.dumps(res, ensure_ascii=False)[:400])
    if res:
        print("VERDICT: SERVER RESPONDS → problem is on lsp4ij/IDE side")
    else:
        print("VERDICT: server answered EMPTY → server-side resolution issue")

# drain stderr for hints
try:
    p.kill()
    err = p.stderr.read(4000)
    tail = [l for l in err.split("\n") if l.strip()][-8:]
    print("--- stderr tail ---")
    print("\n".join(tail))
except Exception:
    pass
