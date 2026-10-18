#!/usr/bin/env python3
"""Probe v2: definition resolution over time — separates 'project not ready yet'
from 'never resolves'. Tests node_modules import vs tsconfig-paths import."""
import json, os, subprocess, sys, time, threading

SERVER = os.path.expanduser(
    "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/.bin/vue-language-server")
ROOT = "file:///var/www/jpbest"
VUE = "/var/www/jpbest/resources/js/pages/Echarts.vue"
URI = "file://" + VUE

text = open(VUE, encoding="utf-8").read()
line4 = text.split("\n")[3]
pos_paths = {"line": 3, "character": line4.index("@/layouts/Layout.vue") + 3}
line2 = text.split("\n")[1]
pos_pkg = {"line": 1, "character": line2.index("@inertiajs/vue3") + 4}

p = subprocess.Popen(["/usr/bin/node", SERVER, "--stdio"],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True, bufsize=0)
_id = [0]

def send(method, params, notify=False):
    msg = {"jsonrpc": "2.0", "method": method, "params": params}
    if not notify:
        _id[0] += 1
        msg["id"] = _id[0]
    d = json.dumps(msg)
    p.stdin.write(f"Content-Length: {len(d.encode())}\r\n\r\n{d}"); p.stdin.flush()
    return msg.get("id")

def read_one(timeout):
    got = {}
    def rd():
        h = {}
        while True:
            l = p.stdout.readline()
            if not l: return
            l = l.strip()
            if not l: break
            k, _, v = l.partition(":"); h[k.strip().lower()] = v.strip()
        n = int(h.get("content-length", 0))
        if n: got["b"] = p.stdout.read(n)
    t = threading.Thread(target=rd, daemon=True); t.start(); t.join(timeout)
    return json.loads(got["b"]) if "b" in got else None

def wait_id(rid, timeout):
    dl = time.time() + timeout
    while time.time() < dl:
        m = read_one(dl - time.time())
        if m and m.get("id") == rid: return m
    return None

rid = send("initialize", {
    "processId": os.getpid(), "rootUri": ROOT,
    "workspaceFolders": [{"uri": ROOT, "name": "jpbest"}],
    "clientInfo": {"name": "probe2"},
    "capabilities": {},
    "initializationOptions": {"typescript": {"tsdk": os.path.expanduser(
        "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib")}},
})
r = wait_id(rid, 60)
print("initialize:", "OK" if r else "TIMEOUT", flush=True)
send("initialized", {}, notify=True)
send("textDocument/didOpen", {"textDocument": {
    "uri": URI, "languageId": "vue", "version": 1, "text": text}}, notify=True)

def defi(pos):
    rid2 = send("textDocument/definition", {"textDocument": {"uri": URI}, "position": pos})
    m = wait_id(rid2, 30)
    return (m or {}).get("result")

for attempt in range(5):
    time.sleep(6 if attempt else 15)
    rp = defi(pos_pkg)
    rs = defi(pos_paths)
    print(f"[t+{15 + attempt*6}s] node_modules import: "
          f"{json.dumps(rp, ensure_ascii=False)[:120] if rp is not None else 'NO REPLY'}")
    print(f"[t+{15 + attempt*6}s] @/paths import:     "
          f"{json.dumps(rs, ensure_ascii=False)[:120] if rs is not None else 'NO REPLY'}", flush=True)
    if rp and rs:
        break
p.kill()
