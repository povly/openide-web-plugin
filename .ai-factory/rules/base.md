# Базовые правила проекта

> Автоопределено анализом кодовой базы. Правьте по мере необходимости.

## Именование

- Файлы: PascalCase, имя файла = имя единственного public-класса (`WfmCssFileType.java`)
- Классы интеграции с платформой — префикс `Wfm` (WebFrontendBundleProvider — исключение,
  точка входа бандла)
- Переменные/методы: camelCase
- Константы: `UPPER_SNAKE_CASE`, `static final` (`BUNDLE_DIR_NAME`, `TAG_BODY`, `LINE_LIMIT`)
- Regex-шаблоны — всегда именованные static final `Pattern` с Javadoc-комментарием о семантике групп

## Структура модулей

- Один плоский пакет `povly.wfm` — без подпакетов; каждая интеграция с платформой = отдельный класс
- `resources/META-INF/plugin.xml` — центральный реестр extension points; каждое расширение
  снабжено inline-комментарием «зачем оно существует»
- `resources/bundle/` (грамматики) и `resources/colorScheme/` (схемы) — самодостаточные ресурсы;
  список файлов бандла дублируется в `WebFrontendBundleProvider.BUNDLE_FILES` — новый файл
  грамматики добавляется в ОБОИХ местах
- `research/` — только для чтения: материалы исследования web-tools (clean-room: изучать,
  код не копировать) и экспериментов подсветки vue

## Обработка ошибок

- Мягкие отказы: guard clause + ранний `return null` / `return` из метода (`@Nullable` в сигнатуре)
- Потоки — try-with-resources
- Фатальные (распаковка бандла): `LOG.error(...)` + unchecked-исключение (`UncheckedIOException`)
- Проверки границ перед использованием: `isEmpty()`, `length()`, `isValid()`, null-проверки
  до обращения

## Поток управления

- Плоское, читаемое управление вместо глубоких вложенностей: guard clauses, ранние
  `return`/`continue`, маленькие именованные static-хелперы (`isRelativePath`,
  `baseLanguage`, `createHighlighter`). Крайние случаи и нерелевантные ветки — в начало метода,
  чтобы основной путь оставался видимым.

## Логирование

- `com.intellij.openapi.diagnostic.Logger.getInstance(<Class>.class)`
- Префикс `wfm:` в info/error-сообщениях; debug — в стиле key=value
- Проверка установки плагина: `grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log`

## Документация кода

- Javadoc каждого класса объясняет root-cause («почему так, а не иначе») со ссылками на историю
  версий (v1.x) и внутренности платформы — это институциональные знания проекта, не удалять

## Сборка и коммиты

- Сборка: `./build.sh` (JDK 21+, OpenIDE в `/usr/share/openide` или `OPENIDE_HOME`) — единственный
  способ проверить компиляцию; тестов нет
- Версия: только в `<version>` файла `resources/META-INF/plugin.xml` (+ changelog-абзац в
  `<description>`); build.sh читает её оттуда
- Коммиты: conventional commits (`feat:`, `docs:`), версия в сообщении при релизе
  (`feat: v1.9.1 - ...`)
- README.md (EN) и README.ru.md (RU) обновляются вместе
