#!/usr/bin/env python3
"""Probe v7 (session 4, follow-up): does a FRESH vue-language-server with the exact
IDE config (hybridMode:false + tsdk) answer hover/definition/completion on a real
user .vue file? Separates server-side health from IDE-side (lsp4ij) dispatch."""
import json, os, subprocess, sys, time, threading

SERVER = os.path.expanduser(
    "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/.bin/vue-language-server")
ROOT = "file:///var/www/jpbest"
VUE = sys.argv[1] if len(sys.argv) > 1 else "/var/www/jpbest/resources/js/components/AddPhotoDrawer.vue"
if not os.path.exists(VUE):
    # fallback: any page
    import glob
    VUE = sorted(glob.glob("/var/www/jpbest/resources/js/**/*.vue", recursive=True))[0]
URI = "file://" + VUE
text = open(VUE, encoding="utf-8").read()
lines = text.split("\n")

def find_token(tok):
    for i, l in enumerate(lines):
        j = l.find(tok)
        if j >= 0:
            return i, j + len(tok) // 2
    return None

HOVER_TOK = next((t for t in ["usePage", "computed", "defineProps", "defineOptions", "ref", "import"] if find_token(t)), "import")
DEF_TOK = next((t for t in ["usePage", "computed", "ref", "defineProps"] if find_token(t)), None)
hp = find_token(HOVER_TOK)
dp = find_token(DEF_TOK) if DEF_TOK else hp
# completion position: end of an existing word inside script
cp = None
for i, l in enumerate(lines):
    if l.startswith("import ") and "from" in l:
        cp = (i, len(l) - 2)  # near end of module path string
        break
print(f"file: {VUE}\nhover@{HOVER_TOK}{hp} def@{DEF_TOK}{dp} compl{cp}")

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
    "clientInfo": {"name": "probe7"},
    "capabilities": {"textDocument": {
        "hover": {"contentFormat": ["markdown", "plaintext"]},
        "completion": {"completionItem": {"snippetSupport": True}},
        "definition": True,
        "semanticTokens": {"dynamicRegistration": False,
            "requests": {"range": False, "full": True},
            "tokenTypes": [], "tokenModifiers": [], "formats": ["relative"]}}},
    "initializationOptions": {
        "vue": {"hybridMode": False},
        "typescript": {"tsdk": os.path.expanduser(
            "~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib")}},
})
r = wait_id(rid, 60)
caps = (r or {}).get("result", {}).get("capabilities", {})
print("caps: hover=%s completion=%s definition=%s" % (
    bool(caps.get("hoverProvider")), bool(caps.get("completionProvider")),
    bool((caps.get("textDocumentSync") is not None))))
send("initialized", {}, notify=True)
send("textDocument/didOpen", {"textDocument": {
    "uri": URI, "languageId": "vue", "version": 1, "text": text}}, notify=True)
print("didOpen; waiting 20s for TS plugin warm-up...")
time.sleep(20)

def pos(pt): return {"line": pt[0], "character": pt[1]}

t0 = time.time()
rid = send("textDocument/hover", {"textDocument": {"uri": URI}, "position": pos(hp)})
m = wait_id(rid, 25)
hv = (m or {}).get("result")
print(f"hover[{HOVER_TOK}]: {'EMPTY' if not hv else str(hv)[:180]} ({time.time()-t0:.1f}s)")

if dp:
    t0 = time.time()
    rid = send("textDocument/definition", {"textDocument": {"uri": URI}, "position": pos(dp)})
    m = wait_id(rid, 25)
    df = (m or {}).get("result")
    if isinstance(df, list):
        print(f"definition[{DEF_TOK}]: {len(df)} locs, first={str(df[0])[:120] if df else '-'} ({time.time()-t0:.1f}s)")
    else:
        print(f"definition[{DEF_TOK}]: {str(df)[:150]} ({time.time()-t0:.1f}s)")

if cp:
    t0 = time.time()
    rid = send("textDocument/completion", {"textDocument": {"uri": URI}, "position": pos(cp)})
    m = wait_id(rid, 30)
    comp = (m or {}).get("result")
    items = comp.get("items") if isinstance(comp, dict) else comp
    print(f"completion@{cp}: {'NONE' if not items else str(len(items)) + ' items, first=' + str(items[0].get('label'))[:40]} ({time.time()-t0:.1f}s)")
p.kill()
