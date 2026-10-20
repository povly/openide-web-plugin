# PLATFORM-API — загрузка плагинов, extension points, LanguageExtension-механика

> Справочник «как платформа достаёт наш код»: от plugin.xml до вызова `createModel`
> нашего `WfmFormattingModelBuilder`. Форматирование здесь — сквозной пример;
> контракты самого движка форматирования — в [FORMAT-ENGINE.md](FORMAT-ENGINE.md)
> (не дублируется).
>
> Обозначения источников (те же, что в FORMAT-ENGINE.md):
>
> - `[S271]` — исходники intellij-community, частичный чекаут `/tmp/opencode/intellij-community`
>   (build.txt `271.SNAPSHOT`; модули `platform/code-style-impl`, `platform/lang-impl`,
>   `plugins/textmate`). Ссылки вида `файл:строка`.
> - `[B262]` — байт-код/содержимое jar'ов установленной OpenIDE **262.10968.63.1**:
>   `/usr/share/openide/lib/*.jar`, `/usr/share/openide/plugins/textmate-plugin/lib/modules/*.jar`.
>   Получено `javap -p/-c/-constants`, `unzip -l/-p` (команды воспроизводимы).
>
> Если 271 и 262 могут расходиться — указано явно. Факты, которые не удалось
> подтвердить ни тем, ни другим, помечены `UNVERIFIED`.

---

## 1. Загрузка плагина: как plugin.xml попадает в реестр

Глубокая декомпиляция не проводилась; ниже — подтверждаемый уровень: имена классов,
их jar-расположение и публичный API (по `javap`), плюс роли из [S271], где исходник есть.

### 1.1 Классы и их расположение (262)

| Класс / ресурс | Jar (262) | Роль |
|---|---|---|
| `com.intellij.ide.plugins.PluginManagerCore` | `intellij.platform.core.impl.jar` | Точка входа загрузки плагинов: чтение дескрипторов, включённые плагины, ошибки |
| `com.intellij.ide.plugins.IdeaPluginDescriptorImpl` | `intellij.platform.core.impl.jar` | Дескриптор плагина, разобранный из plugin.xml |
| `com.intellij.ide.plugins.PluginSetBuilder`, `PluginDescriptorLoadingContext`, `PluginDescriptorLoadingError` | `intellij.platform.core.impl.jar` | Построение `PluginSet`: резолв зависимостей, причины невыгрузки |
| `com.intellij.platform.pluginSystem.parser.impl.XmlReader` | `intellij.platform.core.impl.jar` | XML-парсер дескрипторов |
| `com.intellij.openapi.extensions.impl.ExtensionPointImpl`, `ExtensionsAreaImpl`, `XmlExtensionAdapter`, `ExtensionComponentAdapter` | `util.jar` | Хранилище extension points и адаптеров расширений |
| `com.intellij.openapi.extensions.ExtensionPointName`, `SortingException`, `LoadingOrder` | `util.jar` | API доступа к EP и сортировка |

Источник: `unzip -l` по jar'ам + `javap` (классы найдены именно в этих jar'ах) — [B262].

### 1.2 Что видно о конвейере загрузки

- Константы `PluginManagerCore` (javap `-constants`, [B262]):
  `META_INF = "META-INF/"`, `PLUGIN_XML = "plugin.xml"`, `PLUGIN_XML_PATH =
  "META-INF/plugin.xml"`, `CORE_PLUGIN_ID = "com.intellij"` — дескриптор каждого
  плагина ищется по этому пути внутри jar/каталога плагина.
- Публичный API (javap `-p`, [B262]) рисует конвейер:
  - `scheduleDescriptorLoading(scope, ...)` → `Deferred<PluginSet>` — асинхронное
    чтение дескрипторов (запускается на старте, до полного init);
  - `initializePlugins(...)` / `initializeAndSetPlugins$intellij_platform_core_impl(...)`
    → `PluginManagerState` — построение и установка `PluginSet`;
  - `PluginSetBuilder.createPluginSetWithEnabledModulesMap(...)` и
    `getTopologicalComparator()` — включённые плагины + топологический порядок
    (зависимости `<depends>` грузятся раньше зависимых);
  - чтение XML — `PluginDescriptorLoadingContext` + `XmlReader` (классы в том же
    jar'е, [B262]).
- Доступ после инициализации (javap, [B262]): `PluginManagerCore.getPlugins()`,
  `getLoadedPlugins()`, `isLoaded(PluginId)`, `getPluginSet()`.
- Расширения из `<extensions>` каждого plugin.xml складываются в
  `ExtensionPointImpl` как адаптеры `XmlExtensionAdapter`/`ExtensionComponentAdapter`
  (один адаптер на строку XML; класс адаптера хранит `orderId` и `LoadingOrder` —
  javap, [B262]). Инстанцирование `implementationClass` — ленивое, при первом
  обращении к EP (см. §2.2).

Наш плагин: `<id>povly.webfrontendmini</id>` (resources/META-INF/plugin.xml:2),
`<depends>com.intellij.modules.platform</depends>` и
`<depends>org.jetbrains.plugins.textmate</depends>` (plugin.xml:41-42) — textmate
обязателен, потому что язык `textmate` регистрирует именно textmate-плагин
(§4.в). Каталоги плагинов этой установки: `/usr/share/openide/plugins/*`
(продуктовые, напр. textmate-plugin — TEXTMATE-QUIRKS.md §7).

### 1.3 Граница знаний

Полный порядок фаз init (classpath, dynamic load, компоненты) по 262 байт-коду
не снимался — глубокая декомпиляция не нужна по заданию. Для отладки достаточно:
плагин не грузится → `PluginManagerCore.getPluginNonLoadReason(id)` /
`getAndClearPluginLoadingErrors()` (javap, [B262]) и секция Plugins в Settings.

---

## 2. Extension points: механика и порядок

### 2.1 Декларация EP и бин LanguageExtensionPoint

EP объявляются платформенными модулями. Кейс `lang.formatter`
(S271 `platform/code-style-impl/resources/intellij.platform.codeStyle.impl.xml:19-21`):

```xml
<extensionPoint name="lang.formatter" beanClass="com.intellij.lang.LanguageExtensionPoint" dynamic="true">
  <with attribute="implementationClass" implements="com.intellij.formatting.FormattingModelBuilder"/>
</extensionPoint>
```

- Полное имя EP = `defaultExtensionNs` ("com.intellij") + `name` ("lang.formatter") →
  квалифицированное имя **`com.intellij.lang.formatter`** — то, что стоит в
  конструкторе `LanguageFormatting` (§3).
- `beanClass="...LanguageExtensionPoint"` — язык-зависимый бин: у класса два
  публичных строковых поля `language` и `implementationClass` и метод `getKey()`
  (javap `-p intellij.platform.core.jar`, [B262]). `language="textmate"` в нашем
  plugin.xml (plugin.xml:73) — это `getKey()` = `"textmate"` = `Language.getID()`
  текстмейт-языка.
- `dynamic="true"` — EP допускает регистрацию расширений после старта
  (нужно для dynamic plugins).

Пара типичных форматов EP:
- **interface-EP** (`<extensionPoint ... interface="X">`): расширение = класс,
  реализующий интерфейс; в XML только `implementation`/`implementationClass`.
- **bean-EP** (`beanClass="LanguageExtensionPoint"` и наследники
  `CustomLoadingExtensionPointBean`): расширение = bean с полями из XML
  (`language`, `implementationClass`, `order`, `id`...), класс инстанцируется
  лениво из `implementationClass`.

### 2.2 Чтение расширений: ExtensionPointName.getExtensionList

`ExtensionPointName.getExtensionList()` → `getRootPoint$intellij_platform_extensions()`
→ `ExtensionPointImpl.getExtensionList()` (javap `-c util.jar`, [B262]) — т.е.
имя EP резолвится в корневой `ExtensionPointImpl`, и оттуда отдаются адаптеры.
`ExtensionPointImpl` хранит `volatile List<...> adapters` и флаг
`adaptersAreSorted`; `getSortedAdapters()` сортирует лениво, при первом чтении
(javap, [B262]). До сортировки адаптеры лежат в **порядке регистрации**:
порядок загрузки дескрипторов плагинов (топологический по `<depends>`, §1.2) +
порядок строк внутри plugin.xml. Замечание: точную дисциплину «порядок загрузки
плагинов → порядок адаптеров» байт-код 262 напрямую не показывает — это следует
из устройства `PluginSetBuilder` + `adapters`-списка; против [S271] не
противоречит (исходники PluginManagerCore в чекауте отсутствуют).

### 2.3 order-атрибуты: first / last / before / after

Механику сортировки ведёт `LoadingOrder` (javap `-constants util.jar`, [B262]):

- константы атрибута `order`: `FIRST_STR = "first"`, `LAST_STR = "last"`,
  `BEFORE_STR = "before "` / `BEFORE_STR_OLD = "before:"`,
  `AFTER_STR = "after "` / `AFTER_STR_OLD = "after:"`,
  `ORDER_RULE_SEPARATOR = ','` — т.е. допустимо `order="before A, after B"`;
- парсинг — `LoadingOrder.readOrder(String)`; значения по умолчанию — `LoadingOrder.ANY`;
- сортировка — `LoadingOrder$Companion.sortByLoadingOrder(List<Orderable>)`,
  при противоречивых правилах выбрасывается `SortingException`
  (javap `-c`: `new SortingException` в этом методе, [B262]);
- у каждого адаптера свой `orderId` (обычно `id`-атрибут строки XML) —
  на него ссылаются чужие `before`/`after` (javap `ExtensionComponentAdapter`,
  [B262]).

Примеры из нашего/plugin'ных дескрипторов:

- наш плагин: `gotoDeclarationHandler ... order="last"` (plugin.xml:68, 88) —
  наши Ctrl+B-обработчики не должны перехватывать чужую навигацию;
- textmate-плагин (262, `intellij.textmate.xml` внутри intellij.textmate.jar):
  `fileTypeDetector ... order="last"` (стр. 22), `fileIconProvider ... order="last"`
  (стр. 41) — типирование «по детектору» и иконки сознательно последние;
- платформа сама активно использует порядок в EP `com.intellij.formattingService`:
  `ExternalFormatProcessorAdapter order="first"` (стр. 85) против
  `CoreFormattingService order="last"` (стр. 76) файла
  intellij.platform.codeStyle.impl.xml [S271] — внешние (LSP-стайл) форматтеры
  получают первый шанс, PSI-движок — последний fallback.

Практическое правило: `first`/`last` — про позицию в отсортированном списке EP,
**не** про приоритет вызова; кто «выигрывает», решает потребитель списка
(например, `FormattingServiceUtil.findService` берёт первого подходящего — §2.4).

### 2.4 «EP зарегистрирован, но не вызывается» — типовые причины

Все причины ниже — из кода/байт-кода цепочки Reformat; общеместо для любых EP.

1. **Язык файла ≠ `language`-атрибут EP.** Язык-зависимые EP резолвятся от
   `file.getLanguage()` — обход цепочки `getBaseLanguage()` с точным матчем по
   `Language.getID()` (§3.2). Если файл открылся как `TEXT`/plaintext (fileType
   не заявлен) — EP с `language="textmate"` никогда не спросили. Вариация
   тайминга: язык в fileType-бине задаётся кодом, не XML — история v1.0
   «Incorrect language … should be textmate, actual null» (TEXTMATE-QUIRKS.md §2;
   src/povly/wfm/WfmCssFileType.java:38-39).
2. **Обёртки-перехватчики.** До/вместо вызова билдера платформа может обернуть
   его: `ExternalFormatProcessor.useExternalFormatter(file)` →
   `Formatter.createExternalFormattingModelBuilder(...)` (§3.1, шаг 4), а сам
   `ExternalFormattingModelBuilderImpl.createModel` в режиме REFORMAT возвращает
   `DummyFormattingModel`, полностью игнорируя дефолтный билдер
   (S271 ExternalFormattingModelBuilderImpl.java:18-24). Аналогично — обёртка
   virtual formatting (§3.1, шаг 5).
3. **Suppressor'ы / restriction'ы.**
   - `FormattingServiceSuppressor` (EP `com.intellij.formattingServiceSuppressor`,
     S271 FormattingServiceSuppressor.java:19) отключает **сервис** для файла:
     проверяется в `FormattingServiceUtil.isSuppressed`
     (S271 FormattingServiceUtil.java:47-55);
   - `LanguageFormattingRestriction` (EP `com.intellij.lang.formatter.restriction`,
     [B262] javap `ldc`) возвращает `null` из `forContext`, если хоть один
     restriction сказал `isFormatterAllowed == false` (§3.1, шаг 1).
4. **Порядок / «первый выиграл».** `FormattingServiceUtil.findService` берёт
   первого EP `com.intellij.formattingService`, прошедшего фильтр
   (S271 FormattingServiceUtil.java:31-37); CoreFormattingService с
   `canFormat() == true` (S271 CoreFormattingService.java:30-32) стоит `order="last"`
   и потому fallback'ом ловит всё. Чужой EP `order="first"` с широким `canFormat`
   может перехватить запрос раньше вашего.
5. **Тихий no-op при `builder == null`.** Если резолв дал null —
   `CodeFormatterFacade.processRange/processText` молча выходят: `if (builder != null)
   {...}` без else (S271 CodeFormatterFacade.java:91 и :156) — ни лога, ни правок.
   Симптом «зарегистрировано, но ничего не происходит» чаще всего = резолв вернул
   null (причины 1-3) или правки применились в никуда (FORMAT-ENGINE.md §4).

---

## 3. LanguageExtension-резолв: кейс `com.intellij.lang.formatter`

### 3.1 Полная цепочка forContext (262, байт-код)

`LanguageFormatting` — singleton: `public final class LanguageFormatting extends
LanguageExtension<FormattingModelBuilder>`; конструктор вызывает
`super("com.intellij.lang.formatter")` — имя EP — и **без** defaultImplementation
(javap `-c/-p intellij.platform.codeStyle.jar`, [B262]). Класс лежит именно в
**intellij.platform.codeStyle.jar** (unzip -l, [B262]); там же
`LanguageFormattingRestriction`, `ExternalFormatProcessor`, `Formatter`.
Примечание: в частичном чекауте 271 исходника `LanguageFormatting.java` нет —
логика ниже процитирована по байт-коду 262 (offset'ы инструкций) и совпадает
с описанием в FORMAT-ENGINE.md §1.

`forContext(Language, PsiElement)` (javap `-c`, [B262]) — строгий порядок:

| # | Шаг | Байт-код 262 (метод/offset'ы) |
|---|---|---|
| 1 | Перебор `LanguageFormattingRestriction.EP_NAME.getExtensionList()` → `isFormatterAllowed(element)`; хоть один `false` → **`return null`** | `forContext(Language,PsiElement)`: offsets 16-60 |
| 2 | `findCustomFormattingModelBuilder(language, element)` — из всех расширений EP берёт только `instanceof CustomFormattingModelBuilder`, у которого `isEngagedToFormat(element) == true`; иначе null | вызов на offsets 64-67; тело метода: `allForLanguage` → цикл → `instanceof`/`isEngagedToFormat` → return, else null (offsets 16-80) |
| 3 | Если шаг 2 дал null → `forLanguage(language)` — обычный bean из EP `com.intellij.lang.formatter` (наш `WfmFormattingModelBuilder`) | offsets 72-83 |
| 4 | `ExternalFormatProcessor.useExternalFormatter(file)` → обёртка `Formatter.createExternalFormattingModelBuilder(file, builder)` | offsets 95-119 |
| 5 | `Formatter.isEligibleForVirtualFormatting(element)` → `Formatter.wrapForVirtualFormatting(element, builder)` | offsets 120-146 |

Методы шагов 4-5 — интерфейс `com.intellij.formatting.Formatter` (javap, [B262],
intellij.platform.codeStyle.jar); сигнатуры `ExternalFormatProcessor` —
`activeForFile(PsiFile)`, static `useExternalFormatter(PsiFile)` (javap, [B262]).
В нашем случае шаги 4-5 не активны (внешних LSP-форматтеров через этот EP не
подключено) — см. FORMAT-ENGINE.md §1.

Точка вызова: `CodeFormatterFacade.processRange` —
`LanguageFormatting.INSTANCE.forContext(fileToFormat)` (S271
CodeFormatterFacade.java:90); `processText` — то же для файла (:155).

### 3.2 Резолв forLanguage: точный язык → базовые dialect'и → default

`LanguageExtension` (262, `intellij.platform.core.jar`, javap `-c`, [B262]):

- `forLanguage(lang)` — кэш в `Language.putUserDataIfAbsent` + `findForLanguage`;
- `findForLanguage(lang)` — цикл: `forKey(current)` не пуст → `get(0)`;
  иначе `current = current.getBaseLanguage()` — поднимаемся по цепочке dialect'ов;
  цепочка кончилась → `defaultImplementation` (у `LanguageFormatting` — null,
  см. конструктор выше). Т.е. **точный язык → базовые языки → null**;
- ключ строковый: `keyToString(Language)` = `language.getID()`; ID у
  TextMate-языка — `"textmate"` (javap `-c intellij.textmate.jar`:
  `TextMateLanguage.<init>` → `ldc "textmate"`, [B262]);
- «ANY»: `Language.ANY` — анонимный `Language` с пустым ID (javap: `ldc ""`,
  [B262]); в `LanguageExtension` есть `allForLanguageOrAny(lang)` =
  `allForLanguage(lang)` **конкатенированные** с `allForLanguage(Language.ANY)`
  (javap `-c`, [B262]) — так пишутся расширения «для всех языков». Замечание:
  для `lang.formatter` в 262-пути используется `forLanguage` (шаг 3) —
  ANY-ветка в наш резолв не вовлечена.

Пример обхода dialect-цепочки для нашего плагина: файл `.css` открыт с языком
`textmate` (fileType-бины §4.в), `findForLanguage` сразу попадает в
`language="textmate"` (plugin.xml:73). Для `.blade.php` язык `Blade` принадлежит
стороннему PHP-плагину — потому отдельная регистрация
`<lang.formatter language="Blade" .../>` (plugin.xml:77): `findForLanguage`
идёт от языка файла, и если Blade не наследует textmate (базовый язык Blade
в этой установке по байт-коду не проверялся — UNVERIFIED), билдер на `textmate`
для Blade-файлов не нашёлся бы.

### 3.3 Карта классов по jar'ам (262)

| Класс | Jar |
|---|---|
| `LanguageFormatting`, `LanguageFormattingRestriction`, `ExternalFormatProcessor`, `Formatter` | `intellij.platform.codeStyle.jar` |
| `LanguageExtension`, `LanguageExtensionPoint`, `Language`, `KeyedExtensionCollector` | `intellij.platform.core.jar` |
| `ExtensionPointName`, `ExtensionPointImpl`, `LoadingOrder`, `SortingException` | `util.jar` |
| `CodeFormatterFacade`, `CoreFormattingService`, `FormattingServiceUtil`, `CodeStyleManagerImpl` | `intellij.platform.ide.impl.jar` |

Все строки — unzip -l по jar'ам 262 [B262]; исходники impl-классов — [S271]
code-style-impl (`CoreFormattingService.java:23`, `FormattingServiceUtil.java:20`,
`CodeFormatterFacade.java:51`). Согласуется с jar-картой FORMAT-ENGINE.md (шапка).

---

## 4. Quirks форка OpenIDE vs ванильная IDEA

### (а) Платформа не поставляет активный TextMate-форматтер

Проверено в обеих версиях:

- **262 (установленная OpenIDE)**: внутри
  `/usr/share/openide/plugins/textmate-plugin/lib/modules/intellij.textmate.jar`
  лежит `intellij.textmate.xml`; строка 43 (unzip -p, [B262]):

  ```xml
  <!--<lang.formatter language="textmate" implementationClass="org.jetbrains.plugins.textmate.editor.TextMateFormattingModelBuilder"/>-->
  ```

  Класса `TextMateFormattingModelBuilder` нет ни в одном из 5 jar'ов
  textmate-плагина (unzip -l + grep, [B262]) — не просто выключен, а отсутствует.
- **271 (исходники)**: та же закомментированная строка — S271
  plugins/textmate/resources/intellij.textmate.xml:71.

Итог: слот `com.intellij.lang.formatter` для `textmate` **свободен** — его и
занимает наш `<lang.formatter language="textmate">` (plugin.xml:69-73;
комментарий там же). В ванильной IDEA Community этот EP для textmate тоже
закомментирован [S271] — quirk не форка, а upstream; для нас важно, что в 262
это подтверждено байт-кодом/архивом. Упоминаемый в README «эталон» модели
изучался именно по этому закомментированному классу (README.md, Licenses).

### (б) TextMateParserDefinition.createLexer() == EmptyLexer → плоский PSI

- Байт-код 262 (javap `-c intellij.textmate.jar`, [B262]):
  `createLexer` → `new EmptyLexer` (три инструкции, безусловно).
- Исходник 271: plugins/textmate/.../psi/TextMateParserDefinition.java:21-23.
- Парсер строит ОДИН root-маркер и просто проталкивает лексер до конца:
  TextMateParser.java:9-18 [S271] — плоское AST без структуры.
- Следствие: TextMate PSI никогда не содержит `TokenType.WHITE_SPACE`-узлов →
  платформенный `PsiBasedFormattingModel` не может применять правки — root cause
  кейса §5, детально в FORMAT-ENGINE.md §4 (там же — почему
  `DocumentBasedFormattingModel` лечит).

### (в) fileType-бины обязаны резолвить язык кодом

fileType-бины могут инстанцироваться раньше, чем textmate-плагин зарегистрирует
язык `textmate`; XML-атрибут `language=` в этот момент даёт
«Incorrect language … should be textmate, actual null» (наблюдалось в v1.0).
Решение — `Language.findLanguageByID("textmate")` c fallback
`PlainTextLanguage.INSTANCE` в конструкторе каждого бина (WfmCssFileType.java:38-39
и аналоги; политика — TEXTMATE-QUIRKS.md §2). Это проектный quirk тайминга
загрузки (§1.2), не расхождение 262/271.

### (г) Embedded-scope механика vue (кратко)

Языковой слой один (`textmate`), embedded-окраска (`<script>` → `source.ts` и т.п.)
держится на двух инвариантах платформенного textmate-движка: контейнерные
scope-имена обязаны содержать `.embedded.` (штатный `trimEmbeddedScope` режет
их и text-предков — иначе верхний слой `text source` перекрашивает script
монохромом), а re-lex в редакторе — только whole-document (бинарный state
лексера не несёт стека контекстов). Полностью —
.opencode/skills/wfm-plugin-dev/references/TEXTMATE-QUIRKS.md §3-4 (не дублируется).

### (д) Вендор-политика форка (бонус, виден в байт-коде)

У `PluginManagerCore` в 262 есть приватное поле `VENDORS_OPENIDE` =
`["OpenIDE", "Haulmont", "GitFlic", "Axiom"]` и методы `isVendorOpenIde(String)`,
`isDevelopedByOpenIde(...)` (javap `-p` и `static {}` с четырьмя `ldc`, [B262]) —
доверенные вендоры форка; в ванильной IDEA соответствующая логика — только
JetBrains (`VENDOR_JETBRAINS = "JetBrains"`, `VENDOR_JETBRAINS_SRO`, там же).
На функциональность нашего плагина не влияет, но это конкретное видимое
расхождение форка с ванилью. Прочие наблюденные форк-quirks окружения:
ненадёжный резолв cross-plugin parent-schemes
(TEXTMATE-QUIRKS.md §7, GRAMMARS-SCHEMES.md), занятость `css`/`js`
bundled-AbstractFileType'ами (PLUGIN-XML.md §1).

---

## 5. Кейс: «EP зарегистрирован, но Reformat не работал»

Вывод (5 строк):

1. EP-резолв работал всегда: `findService` находил CoreFormattingService,
   `LanguageFormatting.forContext` → `forLanguage("textmate")` → наш
   `WfmFormattingModelBuilder` (§3.1 шаг 3) — в логах были «model built».
2. Платформенный форматтер для textmate выключен upstream (§4.а) — слот наш,
   перехватчиков (§2.4 п.2-4) в этой установке нет.
3. Поломка была НЕ в резолве и не в модели: правки применял
   `PsiBasedFormattingModel`, которому нужны WHITE_SPACE-узлы в AST.
4. TextMate PSI их не имеет в принципе: `createLexer() == EmptyLexer` (§4.б) —
   модель строилась, а документ не менялся («строки не были изменены»).
5. Фикс — вернуть из `createModel` `DocumentBasedFormattingModel`, пишущий
   прямо в Document (WfmFormattingModelBuilder.java:163-170,
   `InstrumentedDocumentModel extends DocumentBasedFormattingModel`).

Полный разбор с файл:строками движка — FORMAT-ENGINE.md §1, §4, §5 (не дублируется).

---

## Приложение: команды воспроизведения ключевых фактов

```bash
# LanguageFormatting: EP-имя и цепочка forContext (262)
javap -c -p -classpath /usr/share/openide/lib/intellij.platform.codeStyle.jar \
  com.intellij.lang.LanguageFormatting

# LanguageExtension: findForLanguage / allForLanguageOrAny (262)
javap -c -p -classpath /usr/share/openide/lib/intellij.platform.core.jar \
  com.intellij.lang.LanguageExtension

# EmptyLexer в createLexer (262)
javap -c -classpath /usr/share/openide/plugins/textmate-plugin/lib/modules/intellij.textmate.jar \
  org.jetbrains.plugins.textmate.psi.TextMateParserDefinition

# Закомментированный lang.formatter в установленном textmate-плагине (262)
unzip -p /usr/share/openide/plugins/textmate-plugin/lib/modules/intellij.textmate.jar \
  intellij.textmate.xml | grep -n lang.formatter

# Константы plugin.xml-путей и вендоры форка (262)
javap -constants -p -classpath /usr/share/openide/lib/intellij.platform.core.impl.jar \
  com.intellij.ide.plugins.PluginManagerCore | grep -E "PLUGIN_XML|META_INF|VENDOR"

# Порядок formattingService-EP: External first, Core last (271)
grep -n "formattingService implementation" \
  /tmp/opencode/intellij-community/platform/code-style-impl/resources/intellij.platform.codeStyle.impl.xml
```
