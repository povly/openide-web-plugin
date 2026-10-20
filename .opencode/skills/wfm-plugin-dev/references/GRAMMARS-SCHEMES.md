# Грамматики и цветовые схемы

## Грамматики (resources/bundle/Syntaxes/)

Текущие 9 файлов + `info.plist` (все едут в списке `WebFrontendBundleProvider.BUNDLE_FILES`):

| Файл | Язык | Источник | Лицензия |
|---|---|---|---|
| `css.tmLanguage` | CSS | atom/language-css | MIT |
| `scss.tmLanguage.json` | SCSS | shiki (textmate-grammars-themes) | MIT-multi |
| `less.tmLanguage.json` | LESS | shiki | MIT-multi |
| `html.tmLanguage.json` | HTML | shiki | MIT-multi |
| `javascript.tmLanguage.json` | JavaScript | shiki | MIT-multi |
| `typescript.tmLanguage.json` | TypeScript | shiki | MIT-multi |
| `vue.tmLanguage.json` | Vue SFC | vuejs/language-tools (Volar) | MIT |
| `vue-directives.tmLanguage.json` | Vue directives | vuejs/language-tools (Volar) | MIT |
| `vue-interpolations.tmLanguage.json` | Vue interpolations | vuejs/language-tools (Volar) | MIT |

### Добавление новой грамматики (обязательные шаги)

1. Положить файл в `resources/bundle/Syntaxes/`; имя должно удовлетворять фильтру
   TEXTMATE-reader'а: `*.tmLanguage`, `*.plist` или `*.tmLanguage.json`
   (case-sensitive).
2. Добавить путь (относительно корня бандла, с префиксом `Syntaxes/`) в
   `WebFrontendBundleProvider.BUNDLE_FILES`.
3. Если язык должен открываться своим fileType — проверить, что расширение покрыто
   бином из plugin.xml (см. `references/PLUGIN-XML.md` §1); shiki-грамматики без
   собственных `fileTypes` требуют явный fileType-бин.
4. `./build.sh` → переустановка zip в IDE → проверка окраски.

Кэш бандла извлекается заново на каждый boot (REPLACE_EXISTING), ручная чистка
не нужна.

### Правила правки грамматик

- Embedded-контейнеры (обёртки над `include`) обязаны носить scope-имена с
  `.embedded.` (`source.ts.embedded.vue` и т.п.); `include`-строки не переименовывать
  (механика — `references/TEXTMATE-QUIRKS.md` §4).
- Joni не компилирует variable-length look-behind (`references/TEXTMATE-QUIRKS.md` §6).
- Грамматики — сторонние MIT-материалы: минимальные патчи допустимы (в js/ts уже
  патчены `fileTypes`), сохранять атрибуцию в README (таблица Licenses).
- После правки scope-имён проверять соседние слои: спелчек-стратегия, highlighter-стек
  и brace-matching не зависят от имени контейнера (проверено при v1.9.0), но новые
  зависимости от scope-имён нужно перепроверять.

## Цветовые схемы (resources/colorScheme/)

4 файла: `WfmMocha.xml`, `WfmMacchiato.xml`, `WfmFrappe.xml`, `WfmLatte.xml` —
«Catppuccin \<Flavor\> + Web». Регистрация с явными id в plugin.xml
(`povly.wfm.<flavor>.web`) — path-derived ключ даёт startup WARN.

Правила содержимого:

1. **Self-contained**: `parent_scheme` = Darcula + полная палитра Catppuccin из
   theme-xml. Cross-plugin parents не резолвятся надёжно в этом форке (урок v1.8.1).
2. **Все DEFAULT_\*-ключи** TextMate-подсветки обязаны присутствовать (json-вариант
   Catppuccin содержит 0 из 27 — на нём подсветка монохромна).
3. **`DEFAULT_TEMPLATE_LANGUAGE_COLOR` без собственного FOREGROUND**
   (`baseAttributes="TEXT"`, как Darcula) — иначе бледный fg перекрывает семантические
   цвета во embedded-областях (RC10, фикс F1; детали `references/TEXTMATE-QUIRKS.md` §4).
4. **LSP_\*-ключи** для lsp4ij semantic tokens — semantic-подсветка идентификаторов в
   `.ts`/`.vue` докрашивается LSP-сервером, цвета уже запечены в схемы (v1.8).
5. **Структура атрибутов валидна**: v1.7 ловила duplicated value-wrapper
   (`<option><value>…</value></option>` задвоился), из-за которого схемы вообще не
   грузились. После правки схем — `./build.sh` (валидация XML) и установка.

Значения цветов — из `catppuccin/jetbrains` (`themes/*.xml`, MIT): атрибуция
обязательна, «изобретать» цвета не нужно — брать палитру флэвора.
