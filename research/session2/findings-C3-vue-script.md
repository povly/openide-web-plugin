# C3: Чем web-tools красит vue-скрипт (статический разбор, 18.10.2026)

Вопрос плана: «если у них скрипт красится — найти механизм; если не красится —
зафиксировать». B1 (визуальный замер) отложен юзером — ниже статический ответ
по байткоду/plugin.xml; визуальное подтверждение снять при B1.

## Цепочка web-tools для .vue

1. **.vue НЕ заявлен их fileType** (`fileType` заявляет только css; js;mjs;cjs;ts;tsx;jsx
   — plugin.xml 431–432). Файл .vue получает язык **только через textmate-грамматику**
   (vue.tmLanguage.json из их бандла) → язык = textmate → дефолтный EditorHighlighter
   textmate-плагина.
2. Их vue-грамматика — старая Vue2-style (consuming begin/end, fixed-width look-behind) —
   **в построчном режиме теряет script-интерьеры** (факт S12, харнесс LinesRepro).
3. Их `JsEditorHighlighterProvider` (editorHighlighterProvider для filetype
   «JavaScript»/«JS/TS») — ТОЛЬКО для их собственных JS/TS-файлов: TextMateLexerEditorHighlighter
   c `TextMateService.getLanguageDescriptorByFileName(fileName)` — к .vue не применяется.
4. ⇒ textmate-грамматикой script-интерьер .vue у них скорее всего **монохром** (как и у нас
   до фикса схем) — НО у них работает второй слой:

## Второй слой: LSP semantic tokens (главный механизм их script-цвета)

- fileNamePatternMapping `*.vue → vue-language-server, languageId="vue"` (C2) — сервер
  получает корректный язык → vue LS (Volar) стартует и шлёт **semanticTokens**.
- Их схема WebToolsDefaultDark содержит полный LSP_*-набор (C1, строки 968–1079:
  LSP_CLASS/FUNCTION/METHOD/VARIABLE/PROPERTY/PARAMETER/… с палитрой) — semantic-токены
  РАСКРАШИВАЮТСЯ в editor.
- colorSettingsPage = WebToolsLspColorSettingsPage — страница настроек именно этих цветов.

**Вывод: script-интерьер .vue у web-tools красит lsp4ij semantic highlighting от
vue-language-server; textmate у них отвечает за template/теги/стили.** Наш подход
(починенные vscode-грамматики S4/S5 + Volar-грамматика) на textmate-уровне даёт БОЛЬШЕ
их; недостающие куски у нас — (1) цветовые ключи LSP_* в схеме юзера (→ фикс C1),
(2) корректный languageId для Volar-сервера (→ фикс C2-а/б).

## Соответствие фактам

- S12 подтверждён архитектурно (нет механизма textmate-окраски script у web-tools).
- B1 при выполнении проверит: script .vue под Darcula у web-tools — ожидаем
  monochrome-template + colored-script(semantic); если script БЕЗ цветов — значит их
  semantic tokens не взлетели в замере (отдельный интерес).
