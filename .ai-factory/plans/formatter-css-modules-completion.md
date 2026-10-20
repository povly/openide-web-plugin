# Implementation Plan: Форматирование + CSS Modules completion в .vue

Branch: none (git.create_branches=false — работа на main)
Created: 2026-10-20
Mode: full

## Original Request

может тогда форматирование автоматическое сделаем? у меня конечно сделано там и с плагином работает, точно не скажу как. И плюс надо доработать подсказку в vue когда пишу :class=$style и подсказка всех классов из файла или инлайнново модули делаются! может еще каких-то фичей не хватает! поизучай что можно еще улучшить, доработать. Как тестировщик, ссеньор разработчик и инжинер! и потом решим что можем сделать

## Settings

- Testing: yes — лёгкий harness без внешних зависимостей: `tests/` + plain Java asserts (`java -ea`), target `./build.sh test`; платформозависимые классы тестируются вручную в IDE (конвенция проекта)
- Logging: verbose — `com.intellij.openapi.diagnostic.Logger`, префикс `wfm:`, debug в стиле key=value (rules/base.md)
- Docs: yes — обязательный чекпоинт: README.md (EN) и README.ru.md (RU) вместе, version bump в `<version>` plugin.xml + changelog в `<description>`

## Requirements Reconciliation

Authority: запрос пользователя → исходники платформы 262 (тег `idea/262.9437.185`) → parity-материалы `research/session2` (справочные, clean-room).

| Решение / поддерживаемая комбинация | Источник | Верификация |
|---|---|---|
| Форматирование = Reformat (Ctrl+Alt+L / при сохранении) **и** умные отступы на Enter | запрос пользователя (выбор «Оба») | IDE: Ctrl+Alt+L, Enter после `<div>`/`{`, Actions on Save |
| Собственный `FormattingModelBuilder`, не prettier-LSP | платформа 262: эталон закомментирован в textmate-плагине; `research/session2/matrix-D1-parity.md` #7 — пользователь ранее отклонил prettier-on-save; философия «без Node-зависимости» | сборка + IDE |
| `$style.foo` — inline `<style module>` (default) | vuejs.org/api/sfc-css-features; Volar `codegen/style/modules.ts` (key `$style`) | тест задачи 7 + IDE Ctrl+Space |
| `$x.foo` — inline `<style module="x">` | там же (`style.module === true` → `$style`, иначе имя) | тест + IDE |
| `$style.foo` — внешний `<style module src="./x.module.css">` | SFC spec (src-импорт блоков) + Volar `common.ts` (`generateStyleImports`, `style.src`) | тест + IDE |
| `styles.foo` — `import styles from './x.module.css'` в `<script>` | стандарт css-modules (bundler-interop), Volar `resolveStyleImports` | тест + IDE |
| Имена классов: как написано в CSS (оригинал) + camelCase-алиас при отличии | Volar генерирует string-literal ключи без camelize; Vite по умолчанию экспортирует только оригинал; camelCase — конфигурация бандлера (UNVERIFIED для webpack default) | тест: `.foo-bar` → `foo-bar` + `fooBar` |
| `:global(...)`-классы не предлагаются | семантика css-modules (глобальные не экспортируются модулем) | тест |
| Kebab-имена вставляются через строковый ключ: `$style['foo-bar']` | Volar-типизация (string keys) | IDE-сценарий |
| LSP4IJ-подсказки сосуществуют с нашими | исходник `LSPCompletionContributor`: мёрж в общий lookup, дедуп по lookup string | IDE: Ctrl+Space в .vue с настроенным Volar |
| Реальный артефакт в e2e-проверке | requirements gate | прогон на живом .vue с inline+named+import модулями |

## Технический контекст и решения (итоги исследования)

### Форматтер

- Платформа 262 **не поставляет** активный TextMate-форматтер: `plugins/textmate/src/org/jetbrains/plugins/textmate/editor/TextMateFormattingModelBuilder.kt` полностью закомментирован, как и регистрация в `plugins/textmate/resources/intellij.textmate.xml:38`: `<!--<lang.formatter language="textmate" implementationClass="...TextMateFormattingModelBuilder"/>-->`. Это готовый эталон адаптации (intellij-community — Apache 2.0, атрибуция в README).
- Регистрация `<lang.formatter language="textmate">` из нашего плагина валидна: LanguageExtension, зависимость `org.jetbrains.plugins.textmate` уже объявлена. После регистрации Reformat (Ctrl+Alt+L) и Actions on Save → Reformat code работают автоматически (`FormatOnSaveAction.kt` → `ReformatCodeProcessor` → `LanguageFormatting`, EP `com.intellij.lang.formatter`).
- Блоки строить по **одному полному TextMate-проходу** (`WfmHighlighterStack.MATCHER`, паттерн `WfmEditorHighlighterProvider`): состояние лексера бинарное, инкрементальные рестарты внутри embedded-контекстов запрещены (quirk v1.8.2, Javadoc `WfmEditorHighlighterProvider`).
- Умные отступы: `TextMateLineIndentProvider` (EP `com.intellij.lineIndentProvider`) **уже активен** в 262 и читает indent-rules (`increaseIndentPattern`/`decreaseIndentPattern`/`onEnterRules`) из language-configuration бандла. Наш бандл их не содержит → добавить `language-configuration.json` файлы (VSCode-формат; заодно дают комментатор Ctrl+/ через `comments.lineComment/blockComment`). Почти без Java-кода.
- Code Style: `LanguageCodeStyleSettingsProvider` (lang-api, EP проверить по исходникам — `langCodeStyleSettingsProvider`) даст страницу Tabs and Indents. Ограничение: язык `textmate` один на все наши типы файлов — один профиль Code Style (задокументировать в Javadoc).

### CSS Modules completion

- EP `com.intellij.completion.contributor` с `language="textmate"` (Language ID подтверждён: `intellij.textmate.xml` — fileType `language="textmate"`); фильтрация `CompletionContributor.forParameters` берёт язык на offset; страховочный guard в коде — только наши fileTypes/.vue.
- TextMate PSI существует (`lang.parserDefinition language="textmate"` → плоский PSI, `TextMateFile`), но contributor работает по паттерну `WfmVueResourceGoToHandler`: regex по тексту документа, не по AST.
- Навигация Ctrl+B: интерфейс `GotoDeclarationHandler.getGotoDeclarationTargets(PsiElement, offset, Editor)` (EP `com.intellij.gotoDeclarationHandler`, тот же класс EP что у существующего handler'а); цель — `PsiElement`/`OpenFileDescriptor(project, vfile, offset)`.
- Parity: web-tools имел только `cssmodule.GotoDeclarationHandler` (custom LSP request) и **не имел** completion — наш completion превосходит parity. Чистая логика извлечения классов выделяется в класс **без platформенных импортов** → тестируется plain-Java harness'ом.

### Риски (проверить при реализации)

1. **Конфликт с LSP4IJ-форматированием**: если LSP4IJ регистрирует свой formatting-builder для textmate — определить победителя (порядок EP). Fallback: registry-ключ `wfm.formatter.enabled` или guard в `createModel`. Проверить в IDE с настроенным typescript-language-server.
2. **Layout бандла**: добавление language-configuration файлов не должно ломать требование «info.plist + Syntaxes/ на верхнем уровне» (история v1.2 «unknown format»). Верифицировать лоадер по исходникам `plugins/textmate` (как обнаруживаются language-configuration).
3. **Два goto-handler'а с order="last"** (`WfmVueResourceGoToHandler` + новый): уточнить порядок через `order="last, after ..."` или ids; оба обязаны возвращать `null` на «не мой случай».
4. **Форматтер .vue v1**: template/script/style вперемешку — v1 ограничивается отступами и пробелами вокруг `{ } : ;` (CSS-подобное) + скобочная вложенность для js/ts; глубокая семантика — итеративно.
5. Volar сам типизирует `$style` при полностью рабочем LSP — наш completion обязан не дублировать (дедуп по lookup string уже есть в LSP4IJ) и работать **без** LSP.

## Commit Plan

- **Commit 1** (после задач 1–3): `feat: v1.10.0 - formatter for textmate web files (reformat, smart indent, code style)`
- **Commit 2** (после задач 4–6): `feat: css modules completion and Ctrl+B navigation for $style in .vue`
- **Commit 3** (после задач 7–8): `test: plain-java harness for css modules parser; docs: README EN/RU`

## Tasks

### Phase 1: Форматтер

- [x] **Task 1: WfmFormattingModelBuilder — Reformat (Ctrl+Alt+L)**
  Файлы: `src/povly/wfm/WfmFormattingModelBuilder.java` (новый), `resources/META-INF/plugin.xml` (регистрация `<lang.formatter language="textmate" implementationClass="povly.wfm.WfmFormattingModelBuilder"/>` с inline-комментарием «зачем»).
  Реализовать `com.intellij.formatting.FormattingModelBuilder` (code-style-api): модель блоков поверх одного полного TextMate-прохода через `WfmHighlighterStack.MATCHER` (никаких инкрементальных рестартов — бинарное состояние лексера, quirk v1.8.2). Эталон адаптации: закомментированный `org.jetbrains.plugins.textmate.editor.TextMateFormattingModelBuilder` на теге `idea/262.9437.185` (Apache 2.0, атрибуция — задача 8). Правила v1: отступы по вложенности (CSS-блоки, vue-теги), пробелы вокруг `{ } : ;` для css/scss/less, скобочная вложенность для js/ts. Javadoc класса — root-cause (почему полный проход, почему не prettier-LSP).
  LOGGING: `LOG.debug("wfm: formatter createModel file=<name> blocks=<n>")`; INFO «wfm: formatter active» при первом успешном вызове; WARN при пустой грамматике (мягкий отказ — модель без правок).
  Верификация: `./build.sh`; в IDE Ctrl+Alt+L на кривом `.css`/`.vue`; Actions on Save → Reformat code; `grep -a 'wfm:' idea.log`.
  Risks: п.1 (LSP4IJ interplay) — обязательная проверка в IDE до закрытия задачи.

### Phase 2: Умные отступы и Code Style

- [x] **Task 2: language-configuration.json в бандл — Enter-отступы + комментатор** (depends on 1 для сквозной проверки)
  Файлы: `resources/bundle/language-configuration/{css,scss,less,vue}.json` (+ js/ts при возможности — вкладка с `increaseIndent/decreaseIndentPattern`, `onEnterRules`, `comments`), `src/povly/wfm/WebFrontendBundleProvider.java` (`BUNDLE_FILES` — запись в ОБА местах: файл + список; правило single point of truth).
  Сначала по исходникам `plugins/textmate` (тег 262) выяснить, как бандл обнаруживает language-configuration (путь/имя файла), чтобы не сломать layout «info.plist + Syntaxes/» (риск п.2). Файлы пишем сами (regex-паттерны отступов css/vue/jsx-синтаксис) — не заимствуем web-tools.
  Эффект: платформенный `TextMateLineIndentProvider` (уже активен) начинает работать: Enter после `<div>`, `{`, `:` — увеличить отступ; после `</div>`, `}` — уменьшить; Ctrl+/ — комментатор.
  LOGGING: debug «wfm: language-configuration applied grammar=<name>» в провайдере при распаковке.
  Верификация: `./build.sh`; IDE: Enter/Ctrl+/ в `.vue`/`.css`; подсветка не сломалась (бандл пере-распаковывается при старте).
- [x] **Task 3: WfmCodeStyleSettingsProvider** (depends on 1)
  Файлы: `src/povly/wfm/WfmCodeStyleSettingsProvider.java`, `resources/META-INF/plugin.xml` (`langCodeStyleSettingsProvider` — точное имя EP сверить с `LanguageCodeStyleSettingsProvider.java` на теге 262).
  Страница Code Style для TextMate: Tabs and Indents (use tab, tab size, indent, continuation). Javadoc: язык один на все wfm-типы — ограничение платформы.
  LOGGING: debug «wfm: code-style defaults indent=<n>».
  Верификация: Settings → Editor → Code Style появляется TextMate; смена tab size влияет на Reformat (задача 1).

### Phase 3: CSS Modules

- [x] **Task 4: WfmCssModules — чистая логика (без platформенных импортов)**
  Файл: `src/povly/wfm/WfmCssModules.java` (новый).
  Static pure API: (а) парсинг SFC → список `StyleModule{moduleName, inlineText | externalPath}` (default `$style`, named `module="x"`, `src="..."`); (б) `extractClasses(cssText)` → `List<ClassName{original, camel}>`: селекторы `.name` с учётом вложенности SCSS/LESS (`&`), исключая `:global(...)`; camel-алиас только при отличии; (в) парсинг импортов `<script>` → map `identifier → module-файл` (`import s from './x.module.css'`); (г) regex-детект триггера `$style.`/`$x.`/`ident.` на позиции каретки в строке. Именованные `Pattern` static final с Javadoc о группах (конвенция). Плоские guard clauses.
  LOGGING: без платформенного Logger (чистая функция; логируют вызывающие задачи 5–6) — допустимо, side effects отсутствуют.
  Верификация: компиляция `./build.sh`; полная — тестами задачи 7.
- [x] **Task 5: WfmCssModulesCompletionContributor — подсказка классов** (depends on 4)
  Файлы: `src/povly/wfm/WfmCssModulesCompletionContributor.java`, `resources/META-INF/plugin.xml` (`<completion.contributor language="textmate" .../>`).
  `fillCompletionVariants`: guard — только наши файлы (`.vue`); определить префикс у каретки (задача 4г); источник классов — все комбинации таблицы Requirements Reconciliation (inline default/named/external src/import). `LookupElementBuilder` с `typeText` (модуль/файл) и insertHandler: kebab-имена — строкочный ключ `$style['foo-bar']`, camel — `$style.fooBar`. Возврат без паники на чужих файлах.
  LOGGING: debug «wfm: css-modules completion file=<..> module=<..> classes=<n>»; WARN (мягкий) при ошибках парсинга блока.
  Верификация: `./build.sh`; IDE: Ctrl+Space во всех комбинациях (живой .vue с inline+named+import); сосуществование с Volar-подсказками (дедуп).
- [x] **Task 6: WfmCssModulesGotoHandler — Ctrl+B на `$style.foo`** (depends on 4)
  Файлы: `src/povly/wfm/WfmCssModulesGotoHandler.java`, `resources/META-INF/plugin.xml` (`gotoDeclarationHandler`, порядок согласовать с `WfmVueResourceGoToHandler` — риск п.3; guard → `null` на «не мой случай»).
  Цели: inline-модуль → offset селектора в текущем файле; внешний файл → резолв как в `WfmVueResourceGoToHandler` (`findFileByRelativePath`) + `OpenFileDescriptor(project, vfile, offset)` на первое вхождение селектора.
  LOGGING: debug «wfm: css-modules goto target=<file:offset>».
  Верификация: `./build.sh`; IDE: Ctrl+B во всех комбинациях; навигация по `<style src>` из v1.9.1 не сломалась.

### Phase 4: Тесты и документация

- [x] **Task 7: Тестовый harness (plain Java, без зависимостей)** (depends on 4)
  Файлы: `tests/povly/wfm/WfmCssModulesTest.java`, `build.sh` (новый target `test`: javac `tests/` → `out-test/`, запуск `java -ea`; дефолтная сборка не меняется).
  Покрытие: селекторы css/scss (вложенность, `&`), less; kebab→camel (`foo-bar`→`fooBar`); `:global` исключение; SFC-блоки (default/named/src); импорты `*.module.css`; детект триггеров на позициях. Вывод: построчные PASS/FAIL + итог; ненулевой exit при FAIL.
  LOGGING: результаты в stdout (тестовый раннер, не idea.log).
  Верификация: `./build.sh test` зелёный; `./build.sh` не изменился.
- [x] **Task 8: Документация и версия** (depends on 1–7)
  Файлы: `README.md`, `README.ru.md` (вместе, симметрично): Features += Formatter (Reformat + on-save), Smart indent + комментатор, CSS Modules completion/navigation; раздел Formatting (включая note про coexistence с LSP-форматированием); атрибуция адаптации `TextMateFormattingModelBuilder` (JetBrains, Apache 2.0); обновить «Repository layout» (8 → N классов). `resources/META-INF/plugin.xml`: `<version>` → 1.10.0, changelog-абзац в `<description>`.
  LOGGING: n/a (доки).
  Верификация: `./build.sh`; grep версии; diff-симметрия README EN/RU.

## Кандидаты вне скоупа (решение пользователя после/вместе с core)

По итогам исследования как «тестировщик / сеньор / инженер» (gap-лист к web-tools + платформе):

| Кандидат | Ценность | Стоимость | Рекомендация |
|---|---|---|---|
| Color swatches / пикер цветов в CSS (по образцу `CSSColorHighlightingPassFactory` + documentColor) | высокая (P2 в parity-матрице) | средняя (highlighting pass + LSP client feature) | следующий после core |
| Enter между vue-тегами + авто-закрытие тега на `>` (`VueEnterBetweenTagsHandler`-подобные) | средняя | низкая (enterHandlerDelegate/typedHandler) | дёшево, брать пачкой |
| Восстановление word-selection (LSP4IJ отключает) | низкая-средняя | низкая | опционально |
| Unused CSS-module class аннотатор (класс определён, не используется) | средняя | средняя (нет parity-эталона, свой дизайн) | после color swatches |
| Quick-fix «создать класс» из `$style.newOne` | средняя | средняя | потом |
| Emmet (zenCoding) для vue/jsx | средняя | высокая | backlog |

Не является gap'ом (web-tools тоже не имел): brace matcher EP, lang.commenter EP (приходит через language-configuration — задача 2), structure view, intentions.
