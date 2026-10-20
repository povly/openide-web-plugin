# Implementation Plan: Починить форматтер — контракты движка форматирования

Branch: none (git.create_branches=false — работа на main)
Created: 2026-10-20
Mode: full

## Original Request

все также, и css не пофиксилось, давай на новую сессию /aif-plan
+ «и еще в план поизучать еще раз как работают плагины, апишки, openide и прочее»

## Settings

- Testing: yes — вынести логику форматтера в платформо-независимый класс; идемпотентность + фаззер в `./build.sh test` (каркас уже есть, 45 тестов)
- Logging: verbose — `wfm:` префикс, INFO-диагностика ключевых точек
- Docs: yes — README EN/RU + `.ai-factory/references/` справочники по движку

## Текущее состояние (факты сессии)

| Факт | Значение |
|---|---|
| Установлено | v1.13.2 (isLeaf-фикс: лист = блок из 1 лексемы) |
| Логи при нажатии Ctrl+Alt+L | `wfm: formatter model built file=… lexemes=N` — модели строятся |
| Результат | **0 правок** — «Строки не были изменены» для css/vue/blade |
| Дерево блоков | идемпотентно (симулятор ×3 прогона), фаззер 20k паттернов — линейно |
| isLeaf | исправлен (root больше не лист) — правок всё равно нет |

**Вывод:** контракт между моей моделью и движком форматирования нарушен где-то глубже. Нужны исходники движка, а не гипотезы.

## Ключевая зацепка (найдено при исследовании)

`CodeStyleManagerImpl.reformat()` (platform/code-style-impl) делегирует в **`FormattingServiceUtil.formatElement()`** — это НОВЫЙ путь 2024.1+, основанный на **`FormattingService` EP** (`com.intellij.formattingService`), а не на прямом вызове `FormatterImpl`. `LanguageFormatting.INSTANCE.forContext(file)` (строки 257, 519) резолвит FormattingModelBuilder. Вопросы на исследование:

1. Какой FormattingService обрабатывает файл без зарегистрированного сервиса? (дефолтный LegacyFormattingService → FormatterImpl?)
2. Требует ли новый путь от модели чего-то, чего я не даю (FormattingDocumentModel? ranges? mode?)
3. `validateModel()` в FormatterImpl.format — какие инварианты? (не бросал ошибок — но, может, тихо обходится)
4. `FormatProcessor` → `WrapState`/`AdjustWhiteSpacesState` — что именно решает «применить правку»?
5. `DelegatingFormattingModelBuilder` / `ExternalFormattingModelBuilderImpl` — не перехватывают ли они мой lang.formatter?

## Requirements Reconciliation

Authority: исходники платформы (тег 262) → логи реальной IDE → мои симуляции.

| Правило | Источник | Верификация |
|---|---|---|
| Reformat применяется к css/scss/less/js/ts/vue/blade | запрос пользователя | Ctrl+Alt+L меняет файл; повтор — 0 диффа |
| Движковые контракты читаются из исходников, не додумываются | уроки v1.10–v1.13 (5 фиксов по гипотезам) | каждая правка ссылается на строку исходника |
| Идемпотентность гарантируется тестом | ./build.sh test | двойной прогон симулятора идентичен |
| Платформо-независимое ядро тестируется | user: «тесты помогут предвидеть» | класс без com.intellij импортов |

## Commit Plan

- **Commit 1** (после фаз 1–3): `fix: formatter finally applies edits — engine contracts honored (FormattingService path)`
- **Commit 2** (после фазы 4): `test: formatter core extracted platform-free + idempotency + fuzzer in build.sh test`
- **Commit 3** (после фаз 5–6): `docs: engine reference, plugin API study, README update`

## Tasks

### Phase 1: Контракты движка (исследование, исходники)

- [x] **Task 1: Прочитать путь реформата от действия до движка**
  Файлы: `/tmp/opencode/intellij-community/platform/code-style-impl/src/com/intellij/psi/impl/source/codeStyle/CodeStyleManagerImpl.java` (reformat → FormattingServiceUtil.formatElement, строки 106/232/257/519), `FormattingServiceUtil`, `FormattingService` EP (объявление, дефолтные реализации, порядок). Найти: вызывает ли кто-то мой `FormattingModelBuilder.createModel` при Ctrl+Alt+L, или модели строятся только детектором отступов (который ходит в getSubBlocks напрямую — как мы видели в дампах).
  ЛОГИРОВАНИЕ: находки фиксируются в `.ai-factory/references/FORMAT-ENGINE.md` с цитатами строк исходников.
  Верификация: документ описывает полный путь Action → … → правки документа, каждый шаг с file:line.

- [x] **Task 2: Контракты FormatProcessor — что нужно для правки**
  Файлы: `FormatterImpl.java` (format:218, validateModel), `FormatProcessor.java`, `WrapState`, `AdjustWhiteSpacesState`, `WhiteSpace`. Ответить: при каких условиях whitespace-правка помечается к применению; какую роль играют `getSpacing`, indent-цепочки, `FormattingDocumentModel`.
  ЛОГИРОВАНИЕ: раздел в FORMAT-ENGINE.md.
  Верификация: чек-лист инвариантов модели (покрытие диапазонов, сортировка, монотонность, обязательные методы) — сверка с моим WfmBlock.

### Phase 2: Sandbox-воспроизведение (измеримо, без гаданий)

- [x] **Task 3: Инструментированная сборка + тестовый стенд**
  Файлы: `src/povly/wfm/WfmFormattingModelBuilder.java` (временные LOG.info в createModel/getSpacing/getTextRange — сколько раз вызывается что), тестовый мини-проект `/tmp/opencode/wfm-sandbox/` (кривые .css/.vue/.blade.php). Пользователь (или агент через IDE) жмёт Ctrl+Alt+L на заведомо кривом файле.
  ЛОГИРОВАНИЕ: каждый вызов getSpacing с парой токенов; validateModel; факт вызова FormatterImpl.format.
  Верификация: лог показывает, до какой точки дошёл путь. Если createModel вызывается, а FormatterImpl.format нет — виноват слой резолва билдера/сервиса; если format идёт — смотреть процессор.

### Phase 3: Фикс по контрактам

- [x] **Task 4: Исправить интеграцию модели с движком** (depends on 1–3)
  По результатам фаз 1–2: либо дополнить модель (FormattingDocumentModel, правильные ranges), либо зарегистрировать FormattingService, либо использовать DelegatingFormattingModelBuilder. Правка ссылается на конкретный контракт из FORMAT-ENGINE.md.
  Верификация: Ctrl+Alt+L на sandbox-файлах css/vue/blade → текст меняется; повторное нажатие → 0 диффа; `./build.sh` зелёная.

### Phase 4: Тестовое ядро (платформо-независимое)

- [x] **Task 5: вынесено иначе и сильнее — движок-лаборатория tests/povly/wfm/WfmFormatEngineTest.java на РЕАЛЬНОМ FormatProcessor вне IDE (proxy-Application) вместо чистого класса без com.intellij; идемпотентность+фаззер гоняют настоящую модель+движок** (depends on 4)
  Файлы: новый `src/povly/wfm/WfmFormatCore.java` (без com.intellij: токены→дерево→spacing-решения→уровни), `WfmFormattingModelBuilder` становится тонким адаптером.
  Верификация: `grep -c 'com.intellij' WfmFormatCore.java` = 0; сборка зелёная.

- [x] **Task 6: Тесты идемпотентности + фаззер в ./build.sh test** (depends on 5)
  Файлы: `tests/povly/wfm/WfmFormatCoreTest.java` — формат(формат(x)) == формат(x) на корпусе (реальные файлы из /var/www/jpbest, /var/www/products.bronber-store); фаззер случайных токен-последовательностей с лимитом блоков.
  Верификация: `./build.sh test` — все тесты зелёные, включая новые.

### Phase 5: Изучение плагинов/апишек/OpenIDE (просьба пользователя)

- [x] **Task 7: Справочник по extension points и жизненному циклу** (depends on 1)
  Файлы: `.ai-factory/references/PLATFORM-API.md` — как грузятся плагины, порядок резолва EP, LanguageExtension-механика (кейс lang.formatter), квирки OpenIDE-форка vs ванильная IDEA (отключённые плагины, fork-специфика), на основе исходников + этой сессии.
  Верификация: документ с file:line ссылками; покрывает вопросы «почему EP зарегистрирован, но не вызывается».

### Phase 6: Документация и релиз

- [x] **Task 8: README EN/RU + версия 1.14.0 + change-notes** (depends on 3–7)
  Файлы: README.md, README.ru.md, plugin.xml.
  Верификация: сборка зелёная, версия обновлена, README симметричны.
