# web-frontend-mini — веб-стек для OpenIDE

[English](README.md) | **[Русский](README.ru.md)**

OpenIDE (форк IntelliJ IDEA CE) из коробки не подсвечивает веб-файлы: `.css`, `.js`, `.ts`,
`.vue` открываются как plain text. Этот плагин закрывает пробел: подсветка синтаксиса,
цветовые схемы Catppuccin, спелчек — всё в одном zip, без лицензионной механики web-tools.

Подсказки и переходы даёт не плагин, а LSP-серверы — как их подключить, см. «Настройка».

## Возможности

| Функционал | Что получаешь |
|---|---|
| Подсветка синтаксиса | `.css .scss .less .js .mjs .cjs .ts .tsx .jsx .vue` — ключевые слова, строки, комментарии, теги; в `.vue` красятся template, `<script>` и `<style>` |
| Цветовые схемы | «Catppuccin Mocha / Macchiato / Frappe / Latte + Web» — привычная палитра + все ключи веб-стека |
| Semantic-подсветка | Идентификаторы (типы, функции, пропы) в `.ts`/`.vue` докрашивает LSP-сервер, цвета уже в схемах |
| Спелчек | Работает в веб-файлах, не спотыкается о содержимое тегов |
| Сворачивание кода | Стандартное, по грамматике |

## Требования

| Что | Статус |
|---|---|
| ОС | **Любая** — Linux / Windows / macOS (чистая Java, нативного кода нет) |
| IDE | OpenIDE / IntelliJ-платформа **2026.2+** (`since-build 262`) |
| Зависимости плагина | Одна — **TextMate Bundles**: предустановлен в IDEA и OpenIDE, доустановится сам |
| Для подсказок (опционально) | Плагин **LSP4IJ** (Red Hat) + **Node.js** + LSP-серверы — см. ниже |

После установки окраска, схемы, спелчек и сворачивание работают сразу — грамматики и схемы
едут внутри zip. На ванильном IntelliJ IDEA не тестировалось (там `.css`/`.js` заняты
штатными плагинами — IDE спросит про конфликт расширений).

## Установка

1. OpenIDE → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Выбрать `dist/web-frontend-mini.zip` → перезапустить IDE
3. Выбрать схему: **Settings → Editor → Color Scheme → «Catppuccin \<Flavor\> + Web»**
4. Быстрая проверка: открыть любой `.vue` — script и template разноцветные; в логе

```bash
grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log | tail -1
```

## Настройка подсказок и переходов (LSP)

Плагин не ставит свои серверы — подключаем user-defined серверы через LSP4IJ.
Всё настраивается **в GUI** (Settings → Tools → Language Servers → ⚙ → Add),
поля: Command line, Mapping (паттерны файлов + languageId), вкладки Configuration /
Initialization Options.

### 1. Поставить Node.js и серверы

```bash
npm i -g typescript-language-server vscode-langservers-extracted @vue/language-server
```

### 2. Добавить серверы (по одному на каждый)

| Сервер | Command line | Mapping: паттерны + languageId |
|---|---|---|
| Vue Language Server | `node <путь>/node_modules/.bin/vue-language-server --stdio` | `*.vue` → `vue` |
| TypeScript Language Server | `node <путь>/bin/typescript-language-server --stdio` | `*.ts *.tsx` → `typescript`, `*.js *.mjs *.cjs` → `javascript` |
| CSS Language Server | `node <путь>/bin/vscode-css-language-server --stdio` | `*.css` → `css` (аналогично scss/less) |

### 3. Обязательные JSON-опции (вкладки сервера)

**Vue → Initialization Options** (`tsdk` — свой путь к typescript):

```json
{"typescript":{"tsdk":"/home/<user>/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib"},
 "vue":{"hybridMode":false}}
```

**CSS → Configuration** (иначе валидация падает на `validProperties`):

```json
{"css":{"lint":{"unknownProperties":"ignore","validProperties":[]}},
 "scss":{"lint":{"unknownProperties":"ignore","validProperties":[]}},
 "less":{"lint":{"unknownProperties":"ignore","validProperties":[]}}}
```

4. Перезапустить IDE. Проверка: в `.vue` навести мышь на `computed` (tooltip TS-документации),
   `Ctrl+Space` в script (полный список), `Ctrl+B` на импорте (переход к файлу).

⚠️ Главное правило: **конфиги lsp4ij заполнять только через GUI и перезапускать IDE после
правок**. Правка XML-файлов настроек «на живую» не подхватывается работающей сессией —
сервер молча не получает опции (подробнее — в «Решении проблем»).

## Решение проблем

| Симптом | Причина | Решение |
|---|---|---|
| Весь веб-стек монохромный | Выбран не тот вариант темы (у Catppuccin есть `.theme.json`-вариант без базовых цветов) | Выбрать схему «Catppuccin \<Flavor\> **+ Web**» (или Darcula) |
| В `.vue` красится template, а `<script>` одним цветом | Версия плагина < 1.9.0 | Обновиться до 1.9.0+ |
| Vue-сервер «запускается, но тишина»: подсказок нет, LSP-консоль пустая, процесс жив | Initialization Options не дошли до сервера (правились файлом / поле в GUI пустое). Volar без `options.typescript` отвергает initialize и остаётся «зомби» | Вставить Initialization Options **заново через GUI** → перезапустить IDE |
| В `.vue` пустые hover и переходы при живых подсказках в template | Volar в hybrid-режиме по умолчанию: TS-фичи отданы внешнему tsserver, которого вне VS Code нет | `"vue":{"hybridMode":false}` в Initialization Options |
| Красная ошибка `Cannot read properties of null (reading 'validProperties')` в css | CSS-сервер не получил Configuration (секции css/scss/less) | Вставить Configuration JSON в GUI у CSS-сервера → перезапуск |
| Подсказки в `.ts`/`.js` неполные или сервер не цепляется к файлам | В маппинге нет `languageId` — сервер получает язык файла (`textmate`) вместо `typescript` | В Mapping каждого сервера указать паттерны **и** languageId |
| «Пакет web-frontend-mini имеет неизвестный формат» | Нарушен layout бандла (актуально при правке `resources/bundle/`) | Формат: `info.plist` + `Syntaxes/*.tmLanguage[.json]` в корне бандла |
| jsx/tsx-вставки внутри `.js`/`.ts` обесцвечены | Upstream-ограничение textmate-движка (embedded-скопы) | Не лечится настройкой; при необходимости — тот же приём, что в vue 1.9.0 |

## Сборка из исходников

```bash
./build.sh        # → dist/web-frontend-mini.zip (валидация plugin.xml внутри)
```

Требования: JDK 21+, OpenIDE в `/usr/share/openide` (переопределяется `OPENIDE_HOME`).

## Структура репозитория

```
src/povly/wfm/          # 6 классов плагина: провайдер бандла, fileTypes, highlighter, спелчек
resources/plugin.xml    # дескриптор (META-INF, since-build 262, depends: TextMate)
resources/bundle/       # info.plist + Syntaxes/ — 9 грамматик (MIT-источники)
resources/colorScheme/  # 4 схемы «Catppuccin + Web»
research/               # материалы расследований: session2 (web-tools), session4 (окраска vue)
build.sh                # сборка → dist/
```

История изменений — `git log`; технические детали расследований — в `research/session*/`.

## Лицензии и атрибуция

| Материал | Источник | Лицензия |
|---|---|---|
| `css.tmLanguage` | atom/language-css | MIT |
| `scss/less/html/javascript/typescript.tmLanguage.json` | shiki (textmate-grammars-themes) | MIT-мульти |
| `vue.tmLanguage.json`, `vue-directives`, `vue-interpolations` | vuejs/language-tools (Volar) | MIT |
| Значения цветов схем | catppuccin/jetbrains (`themes/*.xml`) | MIT |

Код плагина — clean-room: web-tools изучался декомпиляцией и замерами, код не копировался.
