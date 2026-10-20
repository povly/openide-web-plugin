# Clean-room политика и research/

## Clean-room правило

Плагин — clean-room замена web-tools (`com.haulmont.webtools`): web-tools изучался
**декомпиляцией и замерами**, ни строчки кода и ни одного ресурса не копировалось.
Это закреплено в README (EN/RU) и должно сохраняться:

- **Можно**: изучать распакованный web-tools (архитектура, набор extension points,
  паттерны поведения — например, `WebToolsTextMateBundleProvider` подсказал layout,
  `JsEditorHighlighterProvider` — устройство editor highlighter'а), замерять его
  поведение харнессами, сравнивать результаты.
- **Нельзя**: переносить код (в т.ч. переписанный с декомпиляции дословно), копировать
  его грамматики/схемы/иконки/строки в `src/` или `resources/`.
- Сторонние материалы берутся только из их первоисточников с их лицензиями (таблица
  ниже), не из web-tools.

## Каталог research/ — ТОЛЬКО ЧТЕНИЕ

Не изменять, не включать в сборку, gitignore больших кусков уже настроен.

### session2 — deep-study web-tools (фазы B–D)

| Что | Зачем |
|---|---|
| `wtjar/` | web-tools 1.0.1 распакован: plugin.xml (594 строк, полная карта EP), классы, grammars/, colorScheme/ — главный объект изучения |
| `tmcore/` | `intellij.textmate.core` (BundleReader, lexer, joni-мост) |
| `coreimpl/`, `lsp/`, `rupack/`, `ideimpl/` | декомпиляции platform-core, lsp4ij, ru.openide.* |
| `catppuccin/` | json-варианты тем (0 из 27 DEFAULT_\*) vs xml (все 27) — факт S6 |
| `findings-C1-color.md` … `findings-C4-inventory.md`, `matrix-D1-parity.md`, `baseline-ours.md` | выводы сессии |
| `Repro.java`, `LinesRepro.java`, `RegexAudit.java`, `dbg.java` | харнессы (лексинг непрерывный/построчный, joni-аудит) |
| `README.md` (session2) | карта каталога + рецепты пере-получения (web-tools 142M zip и ideimpl 107M в git не входят) |

### session4 — расследование окраски vue (RC10 → фикс v1.9.0)

| Что | Зачем |
|---|---|
| `findings-A1-rules.md` | полная карта правил краски TextMate-конвейера (источники селекторов: TextMateTheme$1/$2, TextMateDefaultColorsProvider — 36 generic-правил) |
| `findings-A2-pipeline.md` | рут-коз RC10 + решающий харнесс по стадиям конвейера |
| `findings-A3-live.md` | визуальное подтверждение фикса на чистой v1.9.0 |
| `findings-C1/C2-*.md` | semantic-путь (volar semanticTokens, lsp4ij) |
| `decision-D1.md` | матрица решения: принят вариант «textmate основной + semantic добавка», фиксы F1+F2 |
| `PipelineRepro.java` + `pipeline-f2.txt` | харнесс полного конвейера; им доказан F2 |
| `probes/vue_probe*.py` | зонды живой IDE |
| `tmx/` | декомпиляция bundles-reader'а textmate (BundleReaderCore, VSCBundleReader) |

Факты сессий, на которые опирается код: S4 (joni variable-length look-behind не
компилируется), S6 (json vs xml DEFAULT_\* ключи), S7 (эталонные 646 source-токенов
sample.vue), S9 (web-tools: textmate основной + semantic добавка), S15 (окружение
OpenIDE 2026.2.3 / 262.10968.63.1 / JBR 25).

## Лицензии и атрибуция (обязательны в README при использовании материалов)

| Материал | Источник | Лицензия |
|---|---|---|
| `css.tmLanguage` | atom/language-css | MIT |
| `scss/less/html/javascript/typescript.tmLanguage.json` | shiki (textmate-grammars-themes) | MIT-multi |
| `vue.tmLanguage.json`, `vue-directives`, `vue-interpolations` | vuejs/language-tools (Volar) | MIT |
| Значения цветов схем | catppuccin/jetbrains (`themes/*.xml`) | MIT |

Код плагина — авторский (clean-room). Новые сторонние материалы добавлять в эту
таблицу в README.md И README.ru.md одновременно.
