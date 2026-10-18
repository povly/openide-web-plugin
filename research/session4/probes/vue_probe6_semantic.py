#!/usr/bin/env python3
"""Probe v6 (session 4, C1): Volar 2.2.12 classic-mode semanticTokens — full legend,
coverage across several jpbest .vue files, full vs delta registration, latency,
and region split (template/script/style)."""
import json, os, subprocess, sys, time, threading
from collections import Counter

SERVER = os.path.expanduser(
    "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/.bin/vue-language-server")
ROOT = "file:///var/www/jpbest"
FILES = [
    "/var/www/jpbest/resources/js/pages/Echarts.vue",
    "/var/www/jpbest/resources/js/pages/Login.vue",
]
if len(sys.argv) > 1:
    FILES = sys.argv[1:]

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
    "clientInfo": {"name": "probe6"},
    "capabilities": {"textDocument": {"semanticTokens": {
        "dynamicRegistration": False,
        "requests": {"range": False, "full": {"delta": True}},   # accept both full and delta
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
print("== registered semanticTokensProvider (verbatim):")
print(json.dumps(sem, indent=1)[:800])
legend = sem.get("legend", {})
TT = legend.get("tokenTypes", []); TM = legend.get("tokenModifiers", [])
print(f"== legend: {len(TT)} tokenTypes, {len(TM)} tokenModifiers")
print("tokenTypes:", TT)
print("tokenModifiers:", TM)
send("initialized", {}, notify=True)

def region_of(line0, text_lines):
    """region by marker lines: script/style/template via nearest preceding tag line"""
    tag = "template"
    for i in range(0, min(line0, len(text_lines))):
        l = text_lines[i]
        if "</script" in l: tag = "template"
        elif "<script" in l: tag = "script"
        elif "</style" in l: tag = "template"
        elif "<style" in l: tag = "style"
    return tag

for path in FILES:
    uri = "file://" + path
    text = open(path, encoding="utf-8").read()
    lines = text.split("\n")
    send("textDocument/didOpen", {"textDocument": {
        "uri": uri, "languageId": "vue", "version": 1, "text": text}}, notify=True)
    time.sleep(10)

    t0 = time.time()
    rid = send("textDocument/semanticTokens/full", {"textDocument": {"uri": uri}})
    m = wait_id(rid, 45)
    lat = time.time() - t0
    res = (m or {}).get("result")
    if not isinstance(res, dict) or not res.get("data"):
        print(f"\n## {os.path.basename(path)}: EMPTY full result "
              f"({json.dumps(res)[:120] if res is not None else 'no response'}); latency={lat:.1f}s")
        continue
    data = res["data"]
    cnt = Counter(); reg = Counter(); samples = []
    line = 0; char = 0
    for i in range(0, len(data), 5):
        dl, dc, ln, ttype, tmod = data[i:i+5]
        if dl: line += dl; char = dc
        else: char += dc
        tname = TT[ttype] if ttype < len(TT) else f"?{ttype}"
        mnames = [TM[b] for b in range(len(TM)) if tmod & (1 << b)]
        cnt[tname] += 1
        reg[region_of(line, lines) + "/" + tname.split(".")[0]] += 1
        if len(samples) < 8:
            tok = lines[line][char:char + ln] if line < len(lines) else "?"
            samples.append(f"  L{line+1} '{tok[:28]}' {tname} {mnames}")
    print(f"\n## {os.path.basename(path)}: {len(data)//5} tokens, latency(full)={lat:.2f}s, "
          f"resultId={'yes' if res.get('resultId') else 'no'}")
    for k, v in cnt.most_common(12): print(f"    {k}: {v}")
    print("  by region:", dict(reg))
    print("\n".join(samples))

    # delta request (uses resultId if provided)
    if res.get("resultId"):
        t0 = time.time()
        rid = send("textDocument/semanticTokens/full/delta",
                   {"textDocument": {"uri": uri}, "previousResultId": res["resultId"]})
        m2 = wait_id(rid, 45)
        lat2 = time.time() - t0
        r2 = (m2 or {}).get("result")
        if isinstance(r2, dict) and r2.get("edits") is not None:
            print(f"  delta OK: {len(r2.get('edits', []))} edits, latency={lat2:.2f}s, resultId={r2.get('resultId')}")
        elif isinstance(r2, dict) and r2.get("data") is not None:
            print(f"  delta -> server answered FULL ({len(r2.get('data', []))//5} tokens), latency={lat2:.2f}s")
        else:
            print(f"  delta unsupported/other: {json.dumps(r2)[:120] if r2 is not None else 'no response'} (latency={lat2:.2f}s)")
p.kill()
