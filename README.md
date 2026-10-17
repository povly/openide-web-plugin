# openide-web-plugin (web-frontend-mini)

Собственный clean-room плагин веб-стека для **OpenIDE** (форк IntelliJ IDEA CE, платформа 262):
TextMate-грамматики для css / scss / less / vue / html / js / ts — без единой строки лицензионной механики.

Противопоставление `web-tools` (com.haulmont.webtools, AGPL): функциональная парность там, где она
достигается грамматиками и LSP-серверами пользователя; вся LicenseService/Pro-обвязка не воспроизводится.

## Что делает (v1.2)

- Регистрирует TextMate-бандл через EP `com.intellij.textmate.bundleProvider`
  (`WebFrontendBundleProvider`): 9 грамматик открытых источников, распаковка в
  `~/.cache/OpenIDE/OpenIDE2026.2/wfm-bundle-v2/` (идемпотентно, обновляется при каждом старте)
- Layout бандла — **TextMate-формат**: `info.plist` + `Syntaxes/*.tmLanguage[.json]`
  (детектор `BundleType.detectBundleType` в textmate-плагине различает форматы по содержимому
  корня бандла; неверный layout даёт «Пакет … имеет неизвестный формат»)

## Сборка

```bash
./build.sh          # → dist/web-frontend-mini.zip
```

Требования: JDK 21+, OpenIDE в `/usr/share/openide` (переопределяется `OPENIDE_HOME`).

## Установка

OpenIDE → Settings → Plugins → ⚙ → **Install Plugin from Disk…** → `dist/web-frontend-mini.zip` → рестарт.

## Структура

```
src/povly/wfm/WebFrontendBundleProvider.java   # провайдер бандла
resources/META-INF/plugin.xml                  # дескриптор
resources/bundle/info.plist                    # TextMate-бандл: имя
resources/bundle/Syntaxes/                     # грамматики (см. атрибуцию ниже)
```

## Атрибуция грамматик

| Файл | Источник | Лицензия |
|---|---|---|
| `css.tmLanguage` | atom/language-css (textmate canonical) | MIT |
| `scss/less/html/javascript/typescript.tmLanguage.json` | shiki (github.com/shikijs/textmate-grammars-themes) | MIT-мульти (см. shiki-репозиторий) |
| `vue.tmLanguage.json`, `vue-directives`, `vue-interpolations` | vuejs/language-tools (Volar) | MIT |

Файлы переименованы только там, где этого требует фильтр textmate-ридера
(`*.tmLanguage`, `*.plist`, `*.tmLanguage.json`, case-sensitive).

## Статус

- v1.2 — «Пакет имеет неизвестный формат» устранён (причина: layout бандла; см. коммиты)
- Подсветка: html — авто-ассоциация работает; css/js-семейство требуют явных `<fileType>`
  (в форке расширения заняты бандловыми AbstractFileType) — в разработке
- Подсказки/диагностика — LSP-серверы пользователя (lsp4ij user-defined), плагин их не трогает
