# web-frontend-mini — веб-подсветка для OpenIDE

**OpenIDE** (форк IntelliJ IDEA CE) после удаления плагина web-tools не умеет подсвечивать
веб-файлы: `.css`, `.js`, `.ts`, `.vue` открывались как plain text. Этот плагин возвращает
веб-стек: полноценную подсветку, цветовые схемы Catppuccin и проверку орфографии —
без лицензионной механики web-tools (AGPL, LicenseService/Pro-обвязка).

Подсказки и переходы даёт не плагин, а твои LSP-серверы через lsp4ij — плагин лишь
гарантирует, что файлы правильно распознаются (см. раздел «Подсказки»).

> Полная история разработки с разбором всех найденных корней — в runbook
> `linux-fix/docs/openide-web-plugin.md` (RC1–RC11). Здесь — только суть.

## Что получается после установки

| Возможность | Что видишь |
|---|---|
| Подсветка синтаксиса | `.css .scss .less .js .mjs .cjs .ts .tsx .jsx .vue` — ключевые слова, строки, комментарии, теги; в `.vue` красятся и template, и `<script>`, и `<style>` |
| Цветовые схемы | «Catppuccin Mocha/Macchiato/Frappe/Latte **+ Web**» — привычная палитра + все ключи веб-стека (выбор в Settings → Editor → Color Scheme) |
| Semantic-подсветка | Идентификаторы (типы, функции, пропсы) в `.ts`/`.vue` докрашивает LSP-сервер — цвета уже зашиты в схемы |
| Спелчек | Работает в веб-файлах, не спотыкается о содержимое тегов `<...>` |
| Сворачивание кода | Стандартное, по грамматике |

## Установка

1. OpenIDE → **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Выбрать `dist/web-frontend-mini.zip` → перезапуск IDE
3. Проверка:

```bash
grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log | grep -a version
# ожидаем версию установленной сборки; грамматика: "wfm: bundle ready ... (10 files)"
```

Схемы: Settings → Editor → Color Scheme → «Catppuccin <Flavor> + Web».

## Требования и переносимость

| Вопрос | Ответ |
|---|---|
| Какие ОС? | **Любые** (Linux / Windows / macOS) — плагин на чистой Java, нативного кода нет |
| Какая IDE? | Платформа **262+** (`since-build 262`). Единственная зависимость — плагин **TextMate Bundles**: он предустановлен в IntelliJ IDEA и OpenIDE, при необходимости доустановится сам |
| Что работает сразу после установки? | Окраска, цветовые схемы, спелчек, сворачивание — грамматики и схемы едут внутри zip, ничего донастраивать не нужно. Кэш бандла (`~/.cache/OpenIDE/OpenIDE2026.2/wfm-bundle-v2/`) создаётся автоматически (путь фиксированный — на другой IDE это просто папка в `user.home`) |
| А подсказки/переходы? | **С плагином не переносятся** — LSP-серверы и их настройки живут на каждой машине (см. раздел ниже): плагин LSP4IJ + Node.js + серверы + JSON-опции с машинозависимыми путями |
| Ванильный IntelliJ IDEA? | Технически установится (262 + TextMate bundled), но `.css`/`.js` там уже заняты штатными CSS/JavaScript-плагинами — IDE спросит про конфликт расширений (штатная подсветка там и так есть), `.ts`/`.vue` свободны и достанутся нашему. **Не тестировалось** |

## Подсказки и переходы (LSP) — настроить один раз

Плагин не ставит свои серверы. Нужны user-defined серверы в **Settings → Tools → Language Servers**
(всё настраивается в GUI, поле Mapping — паттерны файлов + languageId):

| Сервер | Файлы / languageId | Обязательные настройки |
|---|---|---|
| Vue Language Server (Volar) | `*.vue` / `vue` | **Initialization Options** — JSON ниже |
| TypeScript Language Server | `*.ts *.tsx *.js …` / `typescript`, `javascript` | — |
| CSS Language Server | `*.css …` / `css` | **Configuration** — см. runbook, грабля №9 |
| HTML / JSON — по желанию | | |

Initialization Options для Vue (путь к tsdk — свой):

```json
{"typescript":{"tsdk":"/home/povly/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/typescript/lib","disableAutoImportCache":false},
 "vue":{"hybridMode":false}}
```

⚠️ **Две проверенные грабли (RC6/RC11):**

- `hybridMode: false` обязателен: в hybrid-режиме (по умолчанию) Volar отдаёт TypeScript-фичи
  внешнему tsserver, которого вне VS Code нет — hover/переходы молча пустые.
- **Истина для работающей IDE — GUI, а не XML на диске.** Конфиг, правленный файлом
  (`UserDefinedLanguageServerSettings.xml`, `.idea/LanguageServersSettings.xml`), сессия может
  не подхватить: Volar тогда отвергает initialize (без `options.typescript` — ResponseError),
  сервер остаётся «зомби» — процесс жив, подсказок нет. Лечение: вставить JSON заново в GUI и
  перезапустить IDE.

## Как устроено (для программистов)

- **TextMate-бандл** (`WebFrontendBundleProvider`, EP `textmate.bundleProvider`): 9 грамматик
  открытых источников, распаковка в `~/.cache/OpenIDE/OpenIDE2026.2/wfm-bundle-v2/`. Layout —
  классический TextMate (`info.plist` + `Syntaxes/`), иначе детектор `BundleType` даёт
  «неизвестный формат».
- **fileType-бины** (`WfmCss/WfmJsTs/WfmVueFileType`): забирают расширения у бандловых
  AbstractFileType форка; язык — `Language.findLanguageByID("textmate")` с ленивым резолвом
  и PlainText-фолбэком (тайминг загрузки плагинов).
- **Схемы** (EP `bundledColorScheme`): self-contained, parent=Darcula + полная палитра
  Catppuccin из MIT-xml: 25 `DEFAULT_*` (textmate-фолбэк) + 21 `LSP_*` (semantic tokens).
  `DEFAULT_TEMPLATE_LANGUAGE_COLOR` намеренно без своего foreground (`baseAttributes="TEXT"`):
  бледный fg у этого ключа перекрашивает весь встроенный код одним цветом (RC10).
- **Грамматика vue**: контейнеры script/style названы с `.embedded.`
  (`source.ts.embedded.vue`, `source.css.embedded.vue`) — штатная обрезка платформы
  (`trimEmbeddedScope`) отрезает `text.html.vue`-предков, и generic-селектор `text source`
  больше не подмешивает template-слой поверх семантики. Это фикс RC10 — работает с любой темой.
- **Патчи грамматик под joni**: variable-length look-behind vscode js/ts → fixed-width;
  `while:` у Volar (движок не читает) → `end:`-lookahead; `fileTypes` дописаны в js/ts.
- **Спелчек** (`WfmSpellcheckingStrategy`): тела тегов исключены.

Архитектура подсветки: **textmate — основной слой синтаксиса, LSP semantic tokens — добавка
по идентификаторам** (путь web-tools). Semantic-слой lsp4ij включается сам, кода не требует.

## Сборка из исходников

```bash
./build.sh        # → dist/web-frontend-mini.zip (валидация plugin.xml внутри)
```

Требования: JDK 21+, OpenIDE в `/usr/share/openide` (переопределяется `OPENIDE_HOME`).

## Структура репозитория

```
src/povly/wfm/                       # 6 классов плагина (провайдер, fileTypes, highlighter, спелчек)
resources/META-INF/plugin.xml        # дескриптор (since-build 262, depends textmate)
resources/bundle/                    # info.plist + Syntaxes/ — 9 грамматик (атрибуция ниже)
resources/colorScheme/Wfm*.xml       # 4 схемы «Catppuccin + Web»
research/session2/                   # изучение web-tools 1.0.1: декомпиляции, харнессы, findings
research/session4/                   # окраска vue-script: карта правил, PipelineRepro, зонды, решение
dist/                                # собираемый zip (в git не входит)
```

## История версий

| Версия | Что изменилось |
|---|---|
| **1.9.0** (текущая) | **vue `<script>` окрашен.** Корень RC10: селектор `text source` добавлял `DEFAULT_TEMPLATE_LANGUAGE_COLOR` последним слоем, и его бледный fg из Catppuccin-тем перекрывал всю семантику. Фикс: `.embedded.`-контейнеры в грамматике (штатная обрезка платформы) + нейтрализация ключа в схемах. Доказано харнессом `research/session4/PipelineRepro` |
| 1.8.4–1.8.5 (diag) | Диагностика per-token ключей/RGB в живом редакторе; в релиз не входит |
| 1.8.3 | Переименование embedded-скопов — механизм понят, эффекта не дал (почему — RC10) |
| 1.8.0–1.8.2 | Схемы починены (двойная `<value>`) и переведены на self-contained; спелчек; ре-лекс как страховка |
| 1.2–1.7 | Layout бандла, fileType-бины, module-deps, первый продакшн |

Подробности каждой строки — runbook.

## Известные ограничения

- jsx/tsx-вставки внутри js/ts-грамматик всё ещё содержат `.embedded.`-скопы upstream —
  при жалобах лечится тем же приёмом, что и vue (см. RC10).
- LSP-подсказки требуют корректных маппингов и опций (раздел выше); симптомы «сервера нет» —
  почти всегда RC6/RC11, а не плагин.
- Живой замер web-tools не выполнялся (скипнуто решением юзера) — сравнение статическое,
  `research/session2/`.

## Атрибуция и лицензии

| Материал | Источник | Лицензия |
|---|---|---|
| `css.tmLanguage` | atom/language-css | MIT |
| `scss/less/html/javascript/typescript.tmLanguage.json` | shiki (textmate-grammars-themes) | MIT-мульти |
| `vue.tmLanguage.json`, `vue-directives`, `vue-interpolations` | vuejs/language-tools (Volar) | MIT |
| Значения `DEFAULT_*`/`LSP_*` схем | catppuccin/jetbrains (`themes/*.xml`) | MIT |

Код плагина — clean-room (изучали web-tools декомпиляцией и замерами, код не копировали).
Имена файлов грамматик переименованы только там, где этого требует фильтр textmate-ридера
(`*.tmLanguage`, `*.plist`, `*.tmLanguage.json`, case-sensitive).

## Ссылки

- **Runbook** `linux-fix/docs/openide-web-plugin.md` — вся история: 11 корневых причин,
  диагностика, рецепты, «частые грабли»
- `research/session2/README.md` — материалы изучения web-tools
- `research/session4/findings-*.md` — карта правил краски, конвейер, semantic-путь, решение
