# openide-web-plugin (web-frontend-mini)

Собственный clean-room плагин веб-стека для **OpenIDE** (форк IntelliJ IDEA CE, платформа 262):
TextMate-грамматики для css / scss / less / js / ts / jsx / tsx / vue, схемы «Catppuccin + Web»
и спелчек — без единой строки лицензионной механики.

Противопоставление `web-tools` (com.haulmont.webtools, AGPL): функциональная парность там, где она
достигается грамматиками и LSP-серверами пользователя; вся LicenseService/Pro-обвязка не воспроизводится.
Полная история разработки и разбор web-tools — в runbook `linux-fix/docs/openide-web-plugin.md`.

## Что делает (v1.8.3)

- **Языки/подсветка**: TextMate-бандл через EP `com.intellij.textmate.bundleProvider`
  (`WebFrontendBundleProvider`): 9 грамматик открытых источников, распаковка в
  `~/.cache/OpenIDE/OpenIDE2026.2/wfm-bundle-v2/` (идемпотентно). Layout — TextMate-формат:
  `info.plist` + `Syntaxes/*.tmLanguage[.json]` (детектор `BundleType` различает форматы по
  корню бандла; неверный layout = «Пакет … имеет неизвестный формат»).
- **fileType-бины** (`WfmCssFileType` / `WfmJsTsFileType` / `WfmVueFileType`): забирают css и
  js;mjs;cjs;ts;tsx;jsx;vue у бандловых AbstractFileType форка («syntax highlighting only»),
  язык textmate резолвится лениво с PlainText-фолбэком; `WfmEditorHighlighterProvider` —
  LexerEditorHighlighter + TextMateLexerDataStorage (образец — web-tools).
- **Схемы «Catppuccin \<Flavor\> + Web»** (EP `bundledColorScheme`, id `povly.wfm.<flavor>.web`):
  parent = соответствующий flavor; 25 `DEFAULT_*`-ключей (textmate-fallback: без них весь стек
  монохром — .theme.json-вариант Catppuccin их не содержит) + 21 `LSP_*`-ключ (semantic tokens
  lsp4ij: Volar/tsserver красят типы/функции/переменные). Значения дословно из MIT-xml темы
  (catppuccin/jetbrains).
- **Спелчек** (`WfmSpellcheckingStrategy`, EP `spellchecker.support language="textmate"`):
  тела тегов `<...>` исключаются, остальное — платформенный `PlainTextSplitter`.
- **Патчи грамматик** (joni-совместимость): variable-length look-behind vscode js/ts → fixed-width
  (`(?<=\bKW)`); 4× `while:` у Volar vue → `end: (?=</script[\s>])`; `fileTypes` дописаны в js/ts.
- Подсказки/диагностика — LSP-серверы пользователя (lsp4ij user-defined), плагин их не трогает;
  корректные `languageId` для них — в runbook ( правка `UserDefinedLanguageServerSettings.xml`).

## Сборка

```bash
./build.sh          # → dist/web-frontend-mini.zip
```

Требования: JDK 21+, OpenIDE в `/usr/share/openide` (переопределяется `OPENIDE_HOME`).

## Установка

OpenIDE → Settings → Plugins → ⚙ → **Install Plugin from Disk…** → `dist/web-frontend-mini.zip` → рестарт.

Проверка: `grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log | tail -1`
→ `Web Frontend Mini (1.8.0)`; схемы — в Settings → Editor → Color Scheme.

## Структура

```
src/povly/wfm/
  WebFrontendBundleProvider.java    # провайдер бандла (распаковка Syntaxes/ в кэш IDE)
  WfmCssFileType.java               # css
  WfmJsTsFileType.java              # js;mjs;cjs;ts;tsx;jsx
  WfmVueFileType.java               # vue
  WfmEditorHighlighterProvider.java # highlighter для всех WFM fileTypes
  WfmHighlighterStack.java          # стек матчера для харнессов (CaffeineCaching + Joni)
  WfmSpellcheckingStrategy.java     # спелчек textmate-файлов
resources/
  META-INF/plugin.xml               # дескриптор (idea-version since-build=262, depends textmate)
  bundle/info.plist + Syntaxes/     # 9 грамматик (см. атрибуцию)
  colorScheme/Wfm{Mocha,Macchiato,Frappe,Latte}.xml   # «+ Web»: DEFAULT_* + LSP_*
research/session2/                  # материалы изучения web-tools 1.0.1 (см. README там):
                                   # wtjar, декомпиляции lsp4ij/платформы, харнессы
                                   # Repro/LinesRepro/RegexAudit, findings C1–C4, матрица D1
```

## Атрибуция

| Материал | Источник | Лицензия |
|---|---|---|
| `css.tmLanguage` | atom/language-css (textmate canonical) | MIT |
| `scss/less/html/javascript/typescript.tmLanguage.json` | shiki (github.com/shikijs/textmate-grammars-themes) | MIT-мульти (см. shiki-репозиторий) |
| `vue.tmLanguage.json`, `vue-directives`, `vue-interpolations` | vuejs/language-tools (Volar) | MIT |
| значения `DEFAULT_*` / `LSP_*` схем `Wfm*.xml` | catppuccin/jetbrains (`themes/*.xml`) | MIT |

Файлы грамматик переименованы только там, где этого требует фильтр textmate-ридера
(`*.tmLanguage`, `*.plist`, `*.tmLanguage.json`, case-sensitive).

## Статус

- v1.2 — layout бандла: «Пакет имеет неизвестный формат» устранён
- v1.3 — явные fileType-бины (css/js-семейство у форковых AbstractFileType), fileTypes в js/ts-грамматики
- v1.4.1 — module-deps на intellij.textmate.core убраны (ломают установку; классы видны через `<depends>`)
- v1.5.x (diag) — диагностическая активность: 646/646 source-токенов vue-script, RGB-резолв схем (вычищена в v1.6)
- v1.6–v1.7 — продакшн-сборка; схемы «+ Web» (v1.7 не появились в списке — см. v1.8)
- v1.8.0 — схемы перегенерированы: починен двойной `<value>` (почему v1.7 не появлялись), добавлены
  `LSP_*` semantic-ключи и `id` в EP; спелчек
- v1.8.1 — схемы self-contained: parent_scheme=Darcula + полная палитра Catppuccin из MIT-xml
  (cross-plugin parent в этом форке не резолвится — v1.8.0 в списке схем так и не появился)
- v1.8.2 — полный ре-лекс документа на изменение (страховка инкрементальности) + временная
  живая диагностика WFM-DIAG2 (доказала: script-токены в редактере есть — 1496/1507)
- v1.8.3 — vue-грамматика: контейнерные скопы без подстроки `.embedded.`
  (`source.ts.vuehtml.html.vue` и др.) — upstream `TextMateHighlighter.getTokenHighlights`
  обрезает scope-цепочку на первом embedded-скопе, из-за чего script не матчится ни одним
  правилом краски; с полными цепочками script красится как обычный .ts

Известные ограничения:

- WFM-DIAG2 (WfmLiveDiagStartup) — временная диагностика, удалить после подтверждения фикса
- jsx/tsx-вставки в js/ts-грамматиках всё ещё содержат `.embedded.`-скопы (та же обрезка
  upstream; при воспроизведении лечится аналогично — пока вне жалоб)
- Живой эталон web-tools (замеры B1/B2 плана сессии 3) отложен — статический анализ в `research/session2/`
