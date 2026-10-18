# web-frontend-mini — web stack for OpenIDE

**[English](README.md)** | [Русский](README.ru.md)

OpenIDE (an IntelliJ IDEA Community fork) does not highlight web files out of the box:
`.css`, `.js`, `.ts`, `.vue` open as plain text. This plugin fills the gap — syntax
highlighting, Catppuccin color schemes and spellchecking in a single zip, with none of the
license-gated machinery of the web-tools plugin it replaces.

Code completion and navigation are provided by LSP servers, not by the plugin —
see [Setup](#setting-up-completion--navigation-lsp) below.

## Features

| Feature | What you get |
|---|---|
| Syntax highlighting | `.css .scss .less .js .mjs .cjs .ts .tsx .jsx .vue` — keywords, strings, comments, tags; in `.vue` both template, `<script>` and `<style>` are colored |
| Color schemes | «Catppuccin Mocha / Macchiato / Frappe / Latte **+ Web**» — the familiar palette plus every web-stack key |
| Semantic highlighting | Identifiers (types, functions, props) in `.ts`/`.vue` are painted by the LSP server; colors are already baked into the schemes |
| Spellchecking | Works in web files, doesn't stumble over tag contents |
| Code folding | Standard, grammar-based |

## Requirements

| What | Status |
|---|---|
| OS | **Any** — Linux / Windows / macOS (pure Java, no native code) |
| IDE | OpenIDE / IntelliJ platform **2026.2+** (`since-build 262`) |
| Plugin dependencies | One — **TextMate Bundles**: bundled with IDEA and OpenIDE, auto-installed if missing |
| For completions (optional) | The **LSP4IJ** plugin (Red Hat) + **Node.js** + LSP servers — see below |

Highlighting, schemes, spellchecking and folding work right after installation — grammars
and schemes ship inside the zip. Not tested on vanilla IntelliJ IDEA (there `.css`/`.js`
are owned by the built-in plugins — the IDE will ask about the extension conflict).

## Installation

1. Download `web-frontend-mini.zip` from [Releases](https://github.com/povly/openide-web-plugin/releases)
2. OpenIDE → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the zip → restart the IDE
3. Select the scheme: **Settings → Editor → Color Scheme → «Catppuccin \<Flavor\> + Web»**
4. Quick check: open any `.vue` — script and template are colored; in the log:

```bash
grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log | tail -1
```

## Setting up completion & navigation (LSP)

The plugin ships no servers — connect user-defined servers via LSP4IJ.
Everything is configured **in the GUI** (Settings → Tools → Language Servers → ⚙ → Add):
Command line, Mapping (file patterns + languageId), and the Configuration /
Initialization Options tabs.

### 1. Install Node.js and the servers

```bash
npm i -g typescript-language-server vscode-langservers-extracted @vue/language-server
```

### 2. Add the servers (one entry each)

| Server | Command line | Mapping: patterns + languageId |
|---|---|---|
| Vue Language Server | `node <path>/node_modules/.bin/vue-language-server --stdio` | `*.vue` → `vue` |
| TypeScript Language Server | `node <path>/bin/typescript-language-server --stdio` | `*.ts *.tsx` → `typescript`, `*.js *.mjs *.cjs` → `javascript` |
| CSS Language Server | `node <path>/bin/vscode-css-language-server --stdio` | `*.css` → `css` (same for scss/less) |

### 3. Mandatory JSON options (server tabs)

**Vue → Initialization Options** (`tsdk` — your path to typescript):

```json
{"typescript":{"tsdk":"/home/<user>/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib"},
 "vue":{"hybridMode":false}}
```

**CSS → Configuration** (otherwise validation crashes on `validProperties`):

```json
{"css":{"lint":{"unknownProperties":"ignore","validProperties":[]}},
 "scss":{"lint":{"unknownProperties":"ignore","validProperties":[]}},
 "less":{"lint":{"unknownProperties":"ignore","validProperties":[]}}}
```

4. Restart the IDE. Verify: in a `.vue` hover `computed` (TS doc tooltip), `Ctrl+Space`
   inside script (full list), `Ctrl+B` on an import (jumps to the file).

⚠️ The golden rule: **fill lsp4ij configs through the GUI only, and restart the IDE after
editing them.** Editing the settings XML of a running session is not picked up — the server
silently misses its options (see Troubleshooting).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The whole web stack is monochrome | Wrong theme variant selected (Catppuccin ships a `.theme.json` variant without base colors) | Select the «Catppuccin \<Flavor\> **+ Web**» scheme (or Darcula) |
| In `.vue` the template is colored but `<script>` is a single color | Plugin version < 1.9.0 | Update to 1.9.0+ |
| Vue server "starts but is silent": no completions, LSP console empty, process alive | Initialization Options never reached the server (edited via file / GUI field empty). Volar rejects initialize without `options.typescript` and stays as a zombie | Re-enter Initialization Options **via the GUI** → restart the IDE |
| In `.vue` hover and navigation are empty while template hints work | Volar defaults to hybrid mode: TS features are delegated to an external tsserver that doesn't exist outside VS Code | `"vue":{"hybridMode":false}` in Initialization Options |
| Red error `Cannot read properties of null (reading 'validProperties')` in css | The CSS server got no Configuration (css/scss/less sections) | Paste the Configuration JSON in the CSS server's GUI tab → restart |
| Completions in `.ts`/`.js` are poor or the server doesn't attach to files | No `languageId` in the mapping — the server receives the file language (`textmate`) instead of `typescript` | Set both patterns **and** languageId in each server's Mapping |
| "Package web-frontend-mini has an unknown format" | Bundle layout broken (relevant when editing `resources/bundle/`) | Layout must be `info.plist` + `Syntaxes/*.tmLanguage[.json]` at the bundle root |
| jsx/tsx blocks inside `.js`/`.ts` are uncolored | Upstream textmate-engine limitation (embedded scopes) | Not fixable by settings; same technique as vue 1.9.0 applies if ever needed |

## Building from source

```bash
./build.sh        # → dist/web-frontend-mini.zip (validates plugin.xml)
```

Requirements: JDK 21+, OpenIDE at `/usr/share/openide` (override with `OPENIDE_HOME`).

## Repository layout

```
src/povly/wfm/          # 6 plugin classes: bundle provider, fileTypes, highlighter, spellcheck
resources/plugin.xml    # descriptor (META-INF, since-build 262, depends: TextMate)
resources/bundle/       # info.plist + Syntaxes/ — 9 grammars (MIT sources)
resources/colorScheme/  # 4 «Catppuccin + Web» schemes
research/               # investigation materials: session2 (web-tools), session4 (vue coloring)
build.sh                # build → dist/
```

Change history — `git log`; investigation details — `research/session*/`.

## Licenses & attribution

| Material | Source | License |
|---|---|---|
| `css.tmLanguage` | atom/language-css | MIT |
| `scss/less/html/javascript/typescript.tmLanguage.json` | shiki (textmate-grammars-themes) | MIT-multi |
| `vue.tmLanguage.json`, `vue-directives`, `vue-interpolations` | vuejs/language-tools (Volar) | MIT |
| Scheme color values | catppuccin/jetbrains (`themes/*.xml`) | MIT |

The plugin code is clean-room: web-tools was studied by decompilation and measurements,
no code copied.
