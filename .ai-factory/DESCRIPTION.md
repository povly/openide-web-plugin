# web-frontend-mini — веб-стек для OpenIDE

## Обзор

Плагин для OpenIDE (форк IntelliJ IDEA Community), который добавляет поддержку веб-файлов,
отсутствующую в базовой поставке IDE: `.css`, `.scss`, `.less`, `.js`, `.mjs`, `.cjs`, `.ts`,
`.tsx`, `.jsx`, `.vue` открываются как plain text. Плагин закрывает этот пробел — syntax
highlighting через TextMate-грамматики, цветовые схемы Catppuccin «+ Web», spellchecking и
навигация `Ctrl+B` по внешним ресурсам `.vue` — в одном zip, без лицензионных ограничений
заменяемого плагина web-tools.

Код плагина clean-room: web-tools изучен декомпиляцией и замерами (материалы в `research/`),
код не копировался. Completion и навигация по коду предоставляются LSP-серверами через LSP4IJ
(настраиваются пользователем в GUI), а не самим плагином.

## Ключевые возможности

- Syntax highlighting для `css/scss/less/js/ts/jsx/vue` (+ html-грамматика как зависимость vue);
  в `.vue` раскрашиваются template, `<script>` и `<style>`
- 4 цветовые схемы «Catppuccin Mocha/Macchiato/Frappe/Latte + Web» — палитра Catppuccin плюс все
  ключи веб-стека и `LSP_*`-ключи для semantic tokens lsp4ij (self-contained, parent_scheme=Darcula)
- Spellchecking для TextMate-веб-файлов с исключением тел тегов (`<...>`) из проверки
- Code folding — стандартный, грамматический
- Навигация `Ctrl+B`/Ctrl+click по путям `src="..."` в `<style|script>` внутри `.vue`
  (GotoDeclarationHandler — ни Volar, ни TextMate эти атрибуты не разрешают)
- Whole-document re-lex при изменениях: обходит бинарность состояния TextMate-лексера во
  embedded-контекстах (vue `<script>` → `source.ts`)

## Технологический стек

- **Язык программирования:** Java 21 (`javac --release 21`, без системы сборки)
- **Платформа:** IntelliJ Platform SDK / OpenIDE fork, `since-build="262"`, зависимость
  `org.jetbrains.plugins.textmate` (единственная)
- **Сборка:** `build.sh` (bash, `set -euo pipefail`): валидация plugin.xml → javac → jar → zip
  в `dist/web-frontend-mini.zip`;classpath из установленной OpenIDE (`/usr/share/openide`,
  override через `OPENIDE_HOME`)
- **Ресурсы:** `resources/META-INF/plugin.xml` (дескриптор, версия в `<version>`),
  `resources/bundle/` (TextMate-бандл: `info.plist` + `Syntaxes/*.tmLanguage[.json]`, 9 грамматик,
  MIT-источники), `resources/colorScheme/` (4 XML-схемы)
- **БД / ORM:** не используются
- **Тесты:** отсутствуют; проверка — сборка + ручные проверки в IDE (grep `idea.log`)

## Архитектурные заметки

- Один плоский пакет `povly.wfm`, 8 классов; каждая интеграция с платформой — отдельный класс
  с префиксом `Wfm` (WebFrontendBundleProvider — точка входа бандла)
- `plugin.xml` — центр реестра расширений: fileType-бины перехватывают расширения у
  AbstractFileType платформы; language решается в коде (`findLanguageByID("textmate")` с
  fallback на PlainText) — не через XML-атрибут
- TextMate-бандл распаковывается в `<user.home>/.cache/OpenIDE/OpenIDE2026.2/wfm-bundle-v2/`
  при каждом старте (идемпотентно, REPLACE_EXISTING) — layout обязан быть
  `info.plist` + `Syntaxes/`, иначе регистратор бандла вернёт «неизвестный формат»
- Платформенные quirks задокументированы в Javadoc классов (root-cause + история версий v1.x):
  scope-цепочки `.embedded.`, обрезка embedded-скоупов, ре-лексинг документа — это
  институциональные знания проекта
- Грамматики и схемы — внешние MIT-материалы (atom, shiki, vuejs/language-tools,
  catppuccin/jetbrains); при обновлении сохранять атрибуцию в README

## Нефункциональные требования

- **Логирование:** `com.intellij.openapi.diagnostic.Logger` с префиксом `wfm:`;
  проверка установки — `grep -a 'Web Frontend Mini' idea.log`
- **Обработка ошибок:** мягкие отказы — `null`/пустой результат (guard clauses); фатальные
  при распаковке бандла — `LOG.error` + `UncheckedIOException`
- **Безопасность:** без нативного кода (pure Java, OS-agnostic); бандл только из собственных
  ресурсов плагина
- **Совместимость:** OpenIDE / IntelliJ platform 2026.2+; на ванильной IDEA возможен конфликт
  расширений с встроенными плагинами
- **Лицензии:** код плагина clean-room; все заимствованные грамматики/схемы — MIT с
  атрибуцией в README

## Архитектура

Подробные архитектурные рекомендации вынесены в `.ai-factory/ARCHITECTURE.md`.
Паттерн: Layered (модель extension-point реестра IntelliJ-платформы).
