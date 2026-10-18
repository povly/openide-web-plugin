#!/usr/bin/env python3
"""Probe v5: does vue-language-server (hybridMode:false) return semanticTokens/full
for a real .vue file, and with which legend?"""
import json, os, subprocess, sys, time, threading

SERVER = os.path.expanduser(
    "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/.bin/vue-language-server")
ROOT = "file:///var/www/jpbest"
VUE = "/var/www/jpbest/resources/js/pages/Echarts.vue"
URI = "file://" + VUE
text = open(VUE, encoding="utf-8").read()

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

rid = send("initialize", {
    "processId": os.getpid(), "rootUri": ROOT,
    "workspaceFolders": [{"uri": ROOT, "name": "jpbest"}],
    "clientInfo": {"name": "probe5"},
    "capabilities": {"textDocument": {"semanticTokens": {
        "dynamicRegistration": False,
        "requests": {"range": False, "full": True},
        "tokenTypes": [], "tokenModifiers": [], "formats": ["relative"],
        "overlappingTokenSupport": False, "multilineTokenSupport": False}}},
    "initializationOptions": {
        "vue": {"hybridMode": False},
        "typescript": {"tsdk": os.path.expanduser(
            "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib")}},
})
r = wait_id(rid, 60)
caps = (r or {}).get("result", {}).get("capabilities", {})
sem = caps.get("semanticTokensProvider")
if not sem:
    print("NO semanticTokensProvider capability!"); p.kill(); sys.exit(1)
legend = sem.get("legend", {})
print("semanticTokensProvider: full =", bool(sem.get("full")), "; tokenTypes:", legend.get("tokenTypes")[:16])
send("initialized", {}, notify=True)
send("textDocument/didOpen", {"textDocument": {
    "uri": URI, "languageId": "vue", "version": 1, "text": text}}, notify=True)
time.sleep(12)

rid2 = send("textDocument/semanticTokens/full", {"textDocument": {"uri": URI}})
m = wait_id(rid2, 45)
data = (m or {}).get("result", {}).get("data")
if not data:
    print("EMPTY semantic tokens:", json.dumps((m or {}).get("result"))[:200]); p.kill(); sys.exit(1)
# decode relative encoding
tt = legend.get("tokenTypes"); tm = legend.get("tokenModifiers", [])
from collections import Counter
cnt = Counter(); line = 0; char = 0
for i in range(0, len(data), 5):
    dl, dc, ln, ttype, tmod = data[i:i+5]
    if dl: line += dl; char = dc
    else: char += dc
    cnt[tt[ttype] if ttype < len(tt) else f"?{ttype}"] += 1
print(f"tokens total: {len(data)//5}")
for k, v in cnt.most_common(14):
    print(f"  {k}: {v}")
p.kill()
