#!/usr/bin/env python3
"""Probe v3: is the TS layer alive at all? hover (syntax) vs definition on the
import BINDING identifier; watch for spawned tsserver children."""
import json, os, subprocess, sys, time, threading

SERVER = os.path.expanduser(
    "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/.bin/vue-language-server")
ROOT = "file:///var/www/jpbest"
VUE = "/var/www/jpbest/resources/js/pages/Echarts.vue"
URI = "file://" + VUE

text = open(VUE, encoding="utf-8").read()
lines = text.split("\n")
# line 4 (idx 3): import Layout from '@/layouts/Layout.vue';
pos_binding = {"line": 3, "character": lines[3].index("Layout") + 1}
# line 3 (idx 2): import { computed } from 'vue';
pos_named = {"line": 2, "character": lines[2].index("computed") + 2}

p = subprocess.Popen(["/usr/bin/node", SERVER, "--stdio"],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True, bufsize=0)
_id = [0]

def send(method, params, notify=False):
    msg = {"jsonrpc": "2.0", "method": method, "params": params}
    if not notify:
        _id[0] += 1; msg["id"] = _id[0]
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
        m = read_one(max(0.5, dl - time.time()))
        if m and m.get("id") == rid: return m
    return None

def tsserver_children():
    out = subprocess.run(["pgrep", "-P", str(p.pid), "-a"], capture_output=True, text=True).stdout
    return out.strip().replace("\n", " | ") or "(none)"

rid = send("initialize", {
    "processId": os.getpid(), "rootUri": ROOT,
    "workspaceFolders": [{"uri": ROOT, "name": "jpbest"}],
    "clientInfo": {"name": "probe3"},
    "capabilities": {},
    "initializationOptions": {"vue": {"hybridMode": False}, "typescript": {"tsdk": os.path.expanduser(
        "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib")}},
})
print("initialize:", "OK" if wait_id(rid, 60) else "TIMEOUT", flush=True)
send("initialized", {}, notify=True)
send("textDocument/didOpen", {"textDocument": {
    "uri": URI, "languageId": "vue", "version": 1, "text": text}}, notify=True)

for attempt in range(4):
    time.sleep(12)
    rh = send("textDocument/hover", {"textDocument": {"uri": URI}, "position": pos_named})
    mh = wait_id(rh, 20)
    hover = (mh or {}).get("result")
    rd_ = send("textDocument/definition", {"textDocument": {"uri": URI}, "position": pos_binding})
    md = wait_id(rd_, 20)
    d = (md or {}).get("result")
    print(f"[t+{(attempt+1)*12}s] children: {tsserver_children()[:80]}")
    print(f"         hover 'computed': {json.dumps(hover, ensure_ascii=False)[:100] if hover else 'empty/null'}")
    print(f"         def 'Layout' binding: {json.dumps(d, ensure_ascii=False)[:140] if d else 'empty/null'}", flush=True)
    if d:
        break
p.kill()
