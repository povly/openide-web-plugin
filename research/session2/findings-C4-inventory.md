# C4: Инвентаризация фич web-tools 1.0.1 (18.10.2026, вход для D1)

Полный счётчик EP/plugin.xml (594 строки) + категории. `IMPL` опущены; детали в
`wtjar/META-INF/plugin.xml`.

## Сводка EP ( count → что это )

| EP | n | Категория |
|---|---|---|
| fileNamePatternMapping | 32 | LSP: маппинги файлов→серверы+languageId |
| projectService | 14 | Кэши/контексты (Angular, Nest, CSS-цвета, Prettier, тесты, debug) |
| action | 11 | Генераторы (Angular/Nest CLI), прочее |
| server (lsp4ij) | 9 | LSP-серверы: vue, ts, tsgo, css, angular, eslint, stylelint, prettier, tailwind |
| registryKey | 9 | Фича-флаги (см. ниже) |
| postStartupActivity | 8 | Индексаторы/иниты (Angular selectors, CSS-переменные, тесты…) |
| internalFileTemplate | 8 | New → файлы (component/service/…) |
| projectConfigurable | 7 | Settings-страницы |
| editorNotificationProvider | 7 | Баннеры: Vue/Pro/Angular Pro/CSS-module/InvalidNodePath |
| configurationType | 7 | Run-конфигурации (npm-стайл) |
| newProjectWizard.generator | 5 | Новые проекты (Angular/Nest/…) |
| fileType | 5 | CSSFile(css), JSTSFile(js;mjs;cjs;ts;tsx;jsx), JSON5/JSON(.babelrc…), JSTSInlay |
| applicationService | 5 | Глобальные сервисы |
| typedHandler/enterHandlerDelegate/extendWordSelectionHandler | 5 | Редактор-поведение (авто-закрытие, Word-селект) |
| completion.contributor (language=any) | 3 | Комплишены (в т.ч. CSS-module import) |
| copyPastePostProcessor | 3 | Paste-autoimport (TS + Angular templates) |
| consoleFilterProvider | 3 | Консольные ссылки |
| actionOnSave | 3 | On-save (в т.ч. Prettier on save) |
| gotoDeclarationHandler | 3 | Навигация (CSS modules v2-definition) |
| configurationType+runConfigurationProducer+programRunner+projectAttachProcessor | 11 | Запуск/аттач |
| toolWindow | 2 | Playwright (codegen), TestCafe |
| highlightingPassFactory / annotator | 4 | Подсветка-пассы (CSS-цвета и др.) |
| editorHighlighterProvider | 2 | JS/TS textmate-highlighter |
| bundledColorScheme / colorSettingsPage | 2/1 | Схемы Frontend/Web + LSP-цвета (см. C1) |
| textmate.bundleProvider / spellchecker.support | 1/1 | Грамматики + спелчек textmate |
| debugAdapterServer + xdebugger.breakpointType | 2 | JS/TS DAP-дебаг (Node) |
| inlay/codeVision/lineMarker | 3 | Inlay-провайдеры, code vision, line markers |
| polySymbols.webTypes | 1 | Web-types (Angular bindings) |
| registryKey has.license | 1 | Лицензирование (НЕ воспроизводим — clean-room) |

## registryKey флага-флаги (9)

- `com.haulmont.webtools.lspLog` — LSP-логи в `lspLogs/` (для B1!)
- `vue.templateComponentAutoImport` (default **false** — «costs ~36MB project-wide fetch»)
- `ts.pasteAutoImport`, `angular.pasteAutoImport` (true)
- `testing.useReporter/main`, `runLineMarkers`, `cssModuleGotoDeclaration`
- `has.license` (false)

## Категории для матрицы D1

1. **Языки/подсветка**: css/js/ts/jsx/tsx/vue fileTypes+грамматики; editorHighlighterProvider;
   схемы; LSP-цвета. ← наш core-parity
2. **Подсказки**: 32 маппинга → 9 серверов; semantic tokens; completion-contributors;
   paste-autoimport; goto-declaration. ← C2-вывод: язык+маппинги решают
3. **Форматирование**: prettier-ls on-save + codeStyleSettingsModifier. ← юзер закрыл Ctrl+Alt+F
4. **Lint**: eslint-ls, stylelint-ls. ← опционально
5. **Run/Debug**: 7 конфиг-типов, DAP Node-дебаг, Playwright/TestCafe toolwindows. ← вне scope
6. **Генерация**: newProjectWizard×5, internalFileTemplate×8, actions×11 (Angular/Nest CLI). ← вне scope
7. **Редактор-мелочи**: typedHandler, zenCoding(emmet×2), spellcheck, word-selection. ← cherry-pick
8. **Лицензирование**: has.license, Pro-баннеры, BasePremiumAction. ← НЕ воспроизводим (clean-room)

Вне инвентаря, но важно: depends optional `ru.openide.pro.updater` + web-tools-licensing.xml
(конфиг-файл с licenseProvider) — та самая «панцирная» связка, отмеченная в AGENTS.md.
