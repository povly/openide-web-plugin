# web-frontend-mini — web stack for OpenIDE

**[English](README.md)** | [Русский](README.ru.md)

OpenIDE (an IntelliJ IDEA Community fork) does not highlight web files out of the box:
`.css`, `.js`, `.ts`, `.vue` open as plain text. This plugin fills the gap — syntax
highlighting, Catppuccin color schemes and spellchecking in a single zip, with none of the
license-gated machinery of the web-tools plugin it replaces.

Code formatting (via Prettier / blade-formatter), smart indent, CSS-modules completion and
external-resource navigation are built into the plugin; general code completion is provided by
LSP servers — see [Setup](#setting-up-completion--navigation-lsp) below.

## Features

| Feature | What you get |
|---|---|
| Syntax highlighting | `.css .scss .less .js .mjs .cjs .ts .tsx .jsx .vue` — keywords, strings, comments, tags; in `.vue` both template, `<script>` and `<style>` are colored |
| Color schemes | «Catppuccin Mocha / Macchiato / Frappe / Latte **+ Web**» — the familiar palette plus every web-stack key |
| Semantic highlighting | Identifiers (types, functions, props) in `.ts`/`.vue` are painted by the LSP server; colors are already baked into the schemes |
| Spellchecking | Works in web files, doesn't stumble over tag contents |
| Code folding | Standard, grammar-based |
| Code formatting | Reformat (`Ctrl+Alt+L`) and format-on-save via real formatters — Prettier (css/scss/less/js/ts/vue, project `.prettierrc` honored) and blade-formatter (`.blade.php`); indent-on-Enter built in; see [Formatting](#code-formatting--smart-typing) |
| Smart typing | Enter indents after `{`, `[`, `(` and tags; `Ctrl+/` comments with the region's syntax (`/* */` in css, `//` in scss/js/ts, `<!-- -->` in vue) |
| CSS Modules | `:class="$style.\|"` completion: classes from inline `<style module>` blocks, named modules and imported `*.module.css` files; `Ctrl+B` jumps to the class definition; see [CSS Modules](#css-modules) |
| File navigation | `Ctrl+B` / Ctrl+click on `<style\|script src="./file">` paths in `.vue` opens the file — no LSP provider exists for these attributes, so the plugin ships its own handler |

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

## Code formatting & smart typing

`Ctrl+Alt+L` (Reformat) and Settings → Tools → Actions on Save → Reformat code run **real
formatters** through the plugin (v1.15.0):

- **Prettier** formats `.css .scss .less .js .mjs .cjs .ts .tsx .jsx .vue`. The project's own
  `.prettierrc` / `package.json` config is honored (the process runs with the project dir as cwd);
  prettier is resolved from the project's `node_modules` first, then from the npm global root.
- **blade-formatter** formats `.blade.php` (npm global install: `npm i -g blade-formatter`).
- Requirements: Node.js on the machine — an nvm layout (`~/.nvm/versions/node/*`) is discovered
  automatically; otherwise point the IDE's VM options at it: `-Dwfm.node.path=…`,
  `-Dwfm.npm.global.root=…` (Help → Edit Custom VM Options). To format without any config file,
  prettier still applies its defaults.
- When no tool is found, Reformat is a safe no-op (a balloon shows the error). The built-in
  lexeme formatter (v1.10–v1.14) is retired: it was never fully idempotent and its model fed the
  platform's startup indent auto-adjust, which kept re-indenting files on open.
- Enter indents after `{`, `[`, `(` (js/ts) and opening tags (vue template); a line
  starting with `}`/`)`/a closing tag de-indents — via bundled `.tmPreferences`
  indentation rules. Defaults: Settings → Editor → Code Style → TextMate (2 spaces, no tabs;
  one shared profile — platform limitation of the singleton TextMate language).
- `Ctrl+/` comments with the syntax of the region: `/* */` in css, `//` and `/* */` in
  scss/less/js/ts, `<!-- -->` in vue templates.

LSP servers keep working side by side; Reformat is delegated to the external tools above.

## CSS Modules

In `.vue` files the plugin completes and resolves CSS-module class references:

- `:class="$style.|"` — classes of the default inline `<style module>` block
- `:class="$named.|"` — classes of `<style module="named">`
- `<style module src="./x.module.css">` and `import styles from './x.module.css'` —
  classes of external module files, resolved relative to the `.vue`
- kebab-case classes insert as `$style['foo-bar']` (Volar-compatible string-key access);
  a camelCase alias is offered alongside when it differs
- `Ctrl+B` on `$style.foo` / `styles.foo` jumps to the class definition — the selector in
  the inline block or the external module file

Parsing is regex-based over the document (same layer as the `src="…"` navigation) and
covered by a plain-Java test harness: `./build.sh test`.

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
./build.sh test   # plain-Java harness: css-modules parser (45 checks) + real-engine lab (idempotency, fuzzer)
```

Requirements: JDK 21+, OpenIDE at `/usr/share/openide` (override with `OPENIDE_HOME`).

## Repository layout

```
src/povly/wfm/          # 12 plugin classes: bundle provider, fileTypes, highlighter, spellcheck, formatter, code style, css modules (completion + goto)
tests/povly/wfm/        # plain-Java test harness (./build.sh test)
resources/plugin.xml    # descriptor (META-INF, since-build 262, depends: TextMate)
resources/bundle/       # info.plist + Syntaxes/ (9 grammars, MIT) + Preferences/ (indent + comment rules)
resources/colorScheme/  # 4 «Catppuccin + Web» schemes
research/               # investigation materials: session2 (web-tools), session4 (vue coloring)
build.sh                # build → dist/; `test` target runs the harness
```

Change history — `git log`; investigation details — `research/session*/`.

## Licenses & attribution

| Material | Source | License |
|---|---|---|
| `css.tmLanguage` | atom/language-css | MIT |
| `scss/less/html/javascript/typescript.tmLanguage.json` | shiki (textmate-grammars-themes) | MIT-multi |
| `vue.tmLanguage.json`, `vue-directives`, `vue-interpolations` | vuejs/language-tools (Volar) | MIT |
| Scheme color values | catppuccin/jetbrains (`themes/*.xml`) | MIT |
| Formatter block-model reference | JetBrains/intellij-community (commented-out `TextMateFormattingModelBuilder` — studied as reference; the lexeme implementation is clean-room) | Apache 2.0 |

The plugin code is clean-room: web-tools was studied by decompilation and measurements,
no code copied; `.tmPreferences` rules are written for this project.
