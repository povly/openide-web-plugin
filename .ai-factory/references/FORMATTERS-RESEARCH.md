# FORMATTERS-RESEARCH — готовые форматтеры и API интеграции (итоги исследования 2026-10-20)

> Контекст: собственный лексемный форматтер (v1.10–v1.14) после шести итераций всё ещё
> неидеален: css дрейфует отступами (стартовый `DetectAndAdjustIndentOptionsTask` игнорирует
> `FileIndentOptionsProvider` — третий слой проблемы), vue почти не меняется (правила v1
> консервативны). Пользователь решил изучить готовые форматтеры и, возможно, встроить один.

## 1. Что уже bundled в OpenIDE 262.10968.63.1 (проверено unzip -l по jar'ам)

| Язык | Форматтер | Где |
|---|---|---|
| Движок | `com.intellij.formatting.*` + `psi.formatter.common.AbstractBlock/SpacingBuilder` | `lib/intellij.platform.codeStyle.jar` |
| XML/HTML | `XmlTagBlock`, `XmlBlock`, `AbstractXmlBlock`, `XmlPolicy/HtmlPolicy`, `Html/Xml/XhtmlFormattingModelBuilder` | `lib/intellij.xml.ui.common.jar` |
| JSON | `JsonBlock`, `JsonFormattingBuilderModel` | `plugins/json/lib/intellij.json.jar` |
| Properties | `PropertiesRootBlock`, `PropertyBlock` | `plugins/properties/lib/intellij.properties.jar` |
| EditorConfig | `EditorConfigFormattingBlock` | `plugins/editorconfig-plugin/...` |
| **CSS/SCSS/LESS** | **нет нигде** (Ultimate/closed) | — |
| textmate | нет (только LineIndentProvider) | — |
| markdown/yaml/toml | нет; sh → внешний shfmt | — |

К СВОИМ файлам (textmate PSI) эти форматтеры не применимы — но их исходники (Apache 2.0) —
эталоны блочных моделей.

## 2. Канонические паттерны штатных форматтеров (JSON/XML, со строками исходников)

Изучены в локальном чекауте 271 (SHA b67a3fd2b0e4…):

- **Блок охватывает ВЕСЬ конструктор**: JSON-объект `{…}` целиком; XML-тег `<div…>…</div>`
  целиком (синтетические под-блоки: заголовок тега / контент / закрывающий).
- **Delimiter-дети ( `{`, `}`, `,` ) — `Indent.getNoneIndent()`; серединные — `getNormalIndent()`**
  (JsonBlock.getChildIndent; XmlPolicy.indentChildrenOf ? normal : none).
- **Spacing — декларативный `SpacingBuilder`**, фиксированные правила min==max (жёсткий клэмп),
  keepLineBreaks=true — пользовательские переносы сохраняются, пробелы детерминированы.
- **Движок**: `AbstractBlockWrapper.CAN_USE_FIRST_CHILD_INDENT_AS_BLOCK_INDENT` (default ON):
  когда у блока с LF в whitespace первый ребёнок на том же offset с пустым отступом —
  движок НЕ пересчитывает отступ, а ПОВТОРНО ИСПОЛЬЗУЕТ существующий
  (`childIndent.add(getWhiteSpace())`) — источник нашего бесконечного роста при кривом дереве.
- `getChildAttributes` контейнеров → `ChildAttributes(getNormalIndent(), null)`;
  `isIncomplete` — по отсутствию закрывающего токена.

## 3. Prettier (главный кандидат)

- Архитектура: parse → AST → printer → **Doc IR** (алгоритм Wadler) → текст; ширина строки —
  первичный ограничитель.
- **Языки из коробки**: css/scss/less, js/jsx, ts/tsx, **vue (SFC)**, html, json, yaml, md…;
  **blade** — community: `prettier-plugin-blade` (= Chisel, fortephp; peer prettier^3, опционально
  `@prettier/plugin-php` для встраиваемого PHP) или `@shufo/prettier-plugin-blade` (regex-based).
- API: CLI `prettier --stdin-filepath <file>` (текст в stdin), range-форматирование
  (`rangeStart/rangeEnd`), конфиг `.prettierrc`/`package.json`, `--no-config` для принудительных опций.
- Требует Node (у пользователя уже стоит для LSP4IJ).

## 4. API платформы для внешних форматтеров (проверено по исходникам IC)

### Путь 1: `ExternalFormatProcessor` (синхронный)
- Пакет `com.intellij.psi.codeStyle` (НЕ formatting.service!), EP **`com.intellij.externalFormatProcessor`**,
  `@ApiStatus.Experimental`.
- Методы: `activeForFile(PsiFile)`, `format(source, range, canChangeWhiteSpacesOnly, keepLineBreaks,
  enableBulkUpdate, cursorOffset) → TextRange`, `indent(file, offset, lang)`, `createConfiguration`,
  `getId()`; статические хелперы `useExternalFormatter(file)`, `formatElement`, `formatRangeInFile`.
- **Правки применяет сама реализация** (write action; образец — intellij-cue `ExternalCueFormatter`:
  `document.setText(...)` в `runUndoTransparentAction`).

### Путь 2: `AsyncDocumentFormattingService` (для процесс-спавна — наш случай)
- `com.intellij.formatting.service.AsyncDocumentFormattingService` extends
  `AbstractDocumentFormattingService`: `createFormattingTask(AsyncFormattingRequest)`,
  `getNotificationGroupId()`, `getName()`, `getTimeout()`; `prepareForFormatting` сохраняет документ.
- `AsyncFormattingRequest.onTextReady(text, ranges)` / `onError(message, code)` — результат
  применяется платформой.
- Образцы: intellij-elixir (MixFormatFormattingService), JB Prettier-плагин.

### Официальный JB Prettier-плагин (образец архитектуры)
- Исходники: JetBrains/intellij-plugins, каталог `prettierJS/`, пакет `com.intellij.prettierjs`
  (например `PrettierCodeStyleInstaller` — копирует tabWidth/useTabs/printWidth из .prettierrc
  в CodeStyle). Node-процесс через platform/execution.

## 5. Прецеденты open-source (рабочий код для изучения)

| Проект | Что реализует |
|---|---|
| intellij-rust | `RustfmtExternalFormatProcessor` (ExternalFormatProcessor) |
| cue-lang/intellij-cue | `ExternalCueFormatter` — минимальный чистый образец |
| intellij-elixir | `MixFormatFormattingService` (AsyncDocumentFormattingService) |
| intellij-csv-validator | ExternalFormatProcessor + useExternalFormatter |

## 6. Альтернативы Prettier

| | Prettier | dprint | js-beautify |
|---|---|---|---|
| css/scss/less | ✅ встроено | ✅ malva (g-plane) | ~ (css только) |
| js/ts | ✅ | ✅ official plugin | ~ |
| **vue** | ✅ встроено | ✅ markup_fmt | ~ (как html) |
| **blade** | ✅ 2 community-плагина | ❌ | ❌ |
| Range-форматирование | ✅ | ❌ | ~ |
| Рантайм | Node (уже есть) | 1 бинарник без Node | чистый JS (можно в GraalVM JS внутри JVM) |
| Зрелость | стандарт де-факто | растущий | легаси |

## 7. Опции для решения

- **A. Prettier через AsyncDocumentFormattingService/ExternalFormatProcessor** — «просто работает»
  для всех наших типов; требует Node + prettier в системе/проекте; blade через плагин Chisel.
- **B. dprint** — без Node, но без blade и range.
- **C. Переписать свой форматтер по канону JSON/XML** (блок = весь конструктор, SpacingBuilder
  min==max, корректная работа с CAN_USE_FIRST_CHILD) — без внешних зависимостей; движок-лаборатория
  уже готова для проверки; но это 7-я итерация собственной разработки, и остаётся стартовый
  DetectAndAdjust- слой, который придётся глушить отдельно.

Решение пользователя: A / B / C / гибрид (например: A для всего + дефолт C при отсутствии Node).
