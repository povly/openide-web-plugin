# research/session2 — материалы сессии 2 (ночь 18.10.2026)

Скопировано из `/tmp/opencode/wfm-study/` (волатилен) задачей A1 плана
`linux-fix/.ai-factory/plans/openide-webtools-deep-study.md`.
Назначение: фазы B–D (живые замеры web-tools, статическое изучение, гэп-анализ)
не должны зависеть от /tmp.

Окружение (факт S15): OpenIDE 2026.2.3 (262.10968.63.1, JBR 25) в `/usr/share/openide`;
textmate-модули: `/usr/share/openide/plugins/textmate-plugin/lib/modules/`.

## Каталоги

| Каталог | Что это | Git |
|---|---|---|
| `wtjar/` | **web-tools 1.0.1** (`com.haulmont.webtools`) — jar распакован: plugin.xml (594 строки, полная карта EP), классы, `grammars/`, `colorScheme/` — главный объект изучения фаз B/C | ✅ |
| `wt/` | web-tools zip распакован (структура дистрибутива) | ✅ |
| `tmcore/` | `intellij.textmate.core` из modules textmate-plugin (BundleReader, лексер, joni-мост) | ✅ |
| `coreimpl/` | декомпиляция platform-core (EditorColorsManager и пр.) | ✅ |
| `lsp/` | декомпиляция lsp4ij 0.19.4.6 (LanguageServersRegistry и пр.) | ✅ |
| `rupack/` | декомпиляция `ru.openide.*` (updater/license-checker) | ✅ |
| `joni-extract/` | классы joni/jcodings, извлечённые из джарников | ✅ |
| `catppuccin/` | темы Catppuccin: json-варианты (0 из 27 DEFAULT_*) и xml-варианты (все 27) — факт S6 | ✅ |
| `bundle-repro/`, `bundle-wt/` | тест-бандлы: наш layout (`info.plist + Syntaxes/*`) vs layout web-tools (VSCODE) | ✅ |
| `ehf/`, `ehp/` | пробы EditorHighlighterFactory / EditorHighlighterProvider | ✅ |
| `ref/` | эталонные дескрипторы чужих плагинов: `license.mf/xml` (updater), `lsp4ij.mf/xml`, `php.mf/xml` | ✅ |
| `tm/`, `tmjar/` | textmate-джарники (META-INF манифесты) | ✅ |
| `jar-x/`, `zip-extracted/` | наш web-frontend-mini jar/zip в распакованном виде | ✅ |
| `nuxt/` | реальные минифицированные JS-бандлы (тест js-грамматики на живом коде) | ✅ |
| `web-tools-1.0.1-signed.zip` | подписанный дистрибутив web-tools, **142M** | ❌ gitignore (>100M — лимит GitHub). Лежит на диске рядом; пере-скачать: см. ниже |
| `ideimpl/` | декомпиляция intellij-платформы `/usr/share/openide`, **107M** | ❌ gitignore. Переизвлекаемо локально (см. ниже) |

## Харнессы (`.java`; `.class` восстанавливаются javac)

- `Repro.java` — непрерывный лексинг бандла. Args: `<bundleDir> <sampleFile> [targetScope=text.html.vue]`
- `LinesRepro.java` — построчный лексинг (режим редактора; имитация переразбора видимых строк). Args те же
- `RegexAudit.java` — joni-аудит: вход — файл со строками base64(regex), печатает FAIL-строки. Args: `<b64file>`
- `dbg.java` — отладочные запуски лексера

Сборка/запуск (тот же CP, что в `build.sh`):

```bash
CP="/usr/share/openide/plugins/textmate-plugin/lib/modules/*:/usr/share/openide/lib/*"
javac --release 21 -cp "$CP" -d /tmp/hr Repro.java LinesRepro.java RegexAudit.java dbg.java
java -cp "/tmp/hr:$CP" Repro  <bundleDir> <sample.vue> [targetScope]
java -cp "/tmp/hr:$CP" LinesRepro <bundleDir> <sample.vue> [targetScope]
java -cp "/tmp/hr:$CP" RegexAudit regexes.b64
```

## Файлы

- `regexes.b64`, `regexes2.b64` — все regex'ы грамматик (base64, по строке на regex) — вход `RegexAudit`
- `audit-out.txt`, `audit-report.txt` — итоги joni-аудита (факт S4: variable-length look-behind не компилируется)
- `lexer-core.txt` — дамп декомпиляции лексера textmate-core
- `tmsi.txt` — дамп декомпиляции TextMateSyntaxMatcherImpl
- `mp-367.html`, `mp-367.json`, `api-367.json`, `updates-367.json`, `updates-367.xml`, `list-367.xml` — marketplace-метаданные web-tools (plugin id 367)
- `sample.vue`, `sample-ts.vue` — тестовые файлы (646/646 source-токенов, факт S7)
- `web-frontend-mini.zip` — наш дистрибутив сессии 1–2 (v1.7.0)
- `strapi.json`, `nuxt-js.txt` — материалы тестов подсветки/подсказок на живом коде

## Пере-получение того, что не в git

web-tools 1.0.1 (рецепт S14, проверен):

```bash
# 1) взять downloadUrl из API marketplace (plugin id 367)
curl -s 'https://plugins.openide.ru/api/plugins/367/updates?channel=&isIDEs=false' | jq -r '.[0].downloads[0].link' # поле downloadUrl (rel)
# 2) скачать через file-редиректор (S3)
curl -L -o web-tools-1.0.1-signed.zip 'https://plugins.openide.ru/files?path=<urlencode(downloadUrl)>'
```

`ideimpl/` — декомпиляция классов из `/usr/share/openide/lib/*.jar` и `/usr/share/openide/plugins/*/lib/*.jar`
(инструмент сессии 2: fernflower/cfr — тот же проход, что для `coreimpl/`, `lsp/`, `rupack/`).

`jar-x/`, `zip-extracted/`, `wt/` — распаковка `web-frontend-mini.zip` / `web-tools-…-signed.zip` (`unzip`).
