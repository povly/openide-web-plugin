# A1: Полная карта правил краски TextMate-конвейера (сессия 4)

Дата: 18.10.2026 (вечер). Метод: javap -p -c по jar'ам
`/usr/share/openide/plugins/textmate-plugin/lib/modules/intellij.textmate{,.core}.jar`.
Джавап-дампы: `research/session4/{theme,theme1,theme2,dcp,settings,settings-state,adapter,comparator-core,comparator,highlighter,weigh,prefs,reader,vsc,vsc-lc,brk}.txt`.

## TL;DR — ответ загадки S4

**Generic keyword/string/comment-правила СУЩЕСТВУЮТ** — но живут не в `TextMateTheme$1/$2`,
а в **`TextMateDefaultColorsProvider.DEFAULT_HIGHLIGHTING_RULES`** (отдельный класс, 36 иммутабельных
правил `Map.ofEntries`, недоступных для записи). В сессии 3 джавапили только `$1/$2` — отсюда
«загадка, чем красится .ts»: .ts красится generic-правилами провайдера
(`keyword`→KEYWORD, `string`→STRING, `comment`→LINE_COMMENT …), а все DEFAULT_\*-ключи
уже есть в нашей схеме «Catppuccin Macchiato + Web» (v1.8.1) — поэтому .ts/.css/.js окрашены.

Второй вывод: **`getRules()` и резолв ключей НЕ зависят от цветовой схемы** — вся карта
захардкожена. Схема участвует только на последней ступени (ключ → TextAttributes).

## 1. Источники правил (полная таблица «источник → селекторы → ключи»)

Пул селекторов = union четырёх источников (первые три — статика, ~164 селектора; четвёртый — из бандлов).

### 1.1 `TextMateTheme$1` → `EXTENSIONS_KEY_MAPPING` (16 записей, javap `theme1.txt`)

Map<селектор, TextAttributesKey-константа>. Только java-точечные правила:

| Селектор | Ключ |
|---|---|
| comment.block.javadoc.java | DOC_COMMENT |
| comment.block.java punctuation.definition.comment.java | BLOCK_COMMENT |
| comment.block.javadoc.java punctuation.definition.comment.java | DOC_COMMENT |
| keyword.other.documentation.javadoc.java | DOC_COMMENT_TAG |
| meta.declaration.annotation.java punctuation.definition.annotation.java | METADATA |
| storage.type.annotation.java | METADATA |
| storage.type.java / storage.type.object.array.java | CLASS_NAME |
| meta.method-call.java entity.name.function.java | FUNCTION_CALL |
| meta.function-call.java entity.name.function.java | FUNCTION_CALL |
| meta.class.body…variable.other.definition.java | INSTANCE_FIELD |
| meta.method.body…variable.other.definition.java | LOCAL_VARIABLE |
| meta.package.java storage.modifier.package.java | IDENTIFIER |
| meta.import.java storage.modifier.import.java | IDENTIFIER |
| punctuation.definition.string.begin/end.java | STRING |

(ключи — `DefaultLanguageHighlighterColors.*`)

### 1.2 `TextMateTheme$2` → `EXTENSIONS_MAPPING` (112 записей, javap `theme2.txt`)

Map<селектор, ИМЯ_ключа-строкой> — резолв через `TextAttributesKey.find(name)`.
Точечные правила legacy-языков: XML_ATTRIBUTE_NAME/XML_TAG_NAME, HTML_COMMENT/HTML_TAG_NAME/
HTML_ATTRIBUTE_NAME, CSS.* (IDENT/COMMENT/PROPERTY_NAME/PROPERTY_VALUE/TAG_NAME/NUMBER/FUNCTION/URL),
LESS_*, SASS_*, YAML_*, PUPPET_*, RUBY_*, RHTML_*, HAML_*, SLIM_*, GHERKIN_*, COFFEESCRIPT.*,
OC.*/IVAR/PROTOCOL_REFERENCE, PY.DECORATOR, JS.REGEXP, `string.regexp`→JS.REGEXP.
Ничего generic (keyword/string/comment без суффикса языка) здесь НЕТ.

### 1.3 `TextMateDefaultColorsProvider.DEFAULT_HIGHLIGHTING_RULES` (36 записей, javap `dcp.txt`) — GENERIC-СЛОЙ

Map.ofEntries (иммутабельно). `getTextAttributesKey(scope)` = `getOrDefault(scope, HighlighterColors.TEXT)`:

| Селектор | Ключ (DefaultLanguageHighlighterColors / прочие) |
|---|---|
| **comment** / comment.line | LINE_COMMENT |
| **comment.block** | BLOCK_COMMENT |
| comment.documentation | DOC_COMMENT |
| **constant** | CONSTANT |
| constant.number / constant.numeric | NUMBER |
| constant.character.escape | VALID_STRING_ESCAPE |
| constant.character.entity | MARKUP_ENTITY |
| invalid | BAD_CHARACTER |
| invalid.deprecated | DEPRECATED_ATTRIBUTES |
| **keyword** | **KEYWORD** |
| keyword.operator | OPERATION_SIGN |
| **storage** / storage.type | **KEYWORD** |
| **string** | **STRING** |
| variable | LOCAL_VARIABLE |
| variable.parameter | PARAMETER |
| entity | IDENTIFIER |
| entity.name / entity.name.class | CLASS_NAME |
| entity.name.function | FUNCTION_DECLARATION |
| entity.other.attribute-name | MARKUP_ATTRIBUTE |
| punctuation | DOT |
| punctuation.definition.tag | MARKUP_TAG |
| support.function | FUNCTION_CALL |
| support.type | PREDEFINED_SYMBOL |
| meta.tag | METADATA |
| text source | TEMPLATE_LANGUAGE_COLOR |
| markup.bold/italic/underline/heading | ключи с литеральными bold/italic/underline-атрибутами |
| markup.changed/inserted/deleted | DiffColors.DIFF_MODIFIED/INSERTED/DELETED |

`getAllDefaultKeys()` этих 36 селекторов входит в `TextMateTheme.RULES`
(union: DEFAULT_HIGHLIGHTING_RULES.keySet ∪ EXTENSIONS_MAPPING.keySet ∪ EXTENSIONS_KEY_MAPPING.keySet).

### 1.4 `TextMateServiceImpl.customHighlightingColors` — цвета ИЗ БАНДЛОВ (не настройки!)

- Тип: `HashMap<CharSequence scope, TextMateTextAttributesAdapter>` (tmsi.txt:13).
- Наполнение: ТОЛЬКО при регистрации бандла — `registerPreferences`:
  `TextMatePreferences.getCustomHighlightingAttributes()` != null →
  `customHighlightingColors[scopeName] = TextMateTextAttributesAdapter(scopeName, attrs)` (tmsi.txt:1107–1124).
- Источник данных: preference-файлы бандла (plist: `scope` + `settings` с foreground/background/fontStyle —
  Sublime-style `.tmPreferences`; строки в `BundleReaderCoreKt`: "scope", "settings", "shellVariables").
  **VS Code-бандлы так НЕ красятся**: `VSCodeExtensionLanguageConfiguration` (vsc-lc.txt) поля
  `customHighlightingAttributes` не имеет — только brackets/comments/indentation/onEnterRules.
- Адаптер (`adapter.txt`): строковые цвета → литеральные `TextAttributes` →
  `TextAttributesKey.createTextAttributesKey(scopeName+суффикс, attrs)` — **динамический ключ с
  буквальными цветами, вне цветовой схемы** (alpha смешивается с фоном схемы через
  `TextMateTheme.getDefaultBackground()` = global scheme default background).
- **Хранение: НЕ в options XML.** `TextMateSettings` (settings.txt) — PersistentStateComponent только
  для бандлов (`BundleConfigBean`: name/path/enabled; state = bundles). Никаких цветов там нет.
- **Settings UI: нет.** Страница TextMate управляет только бандлами.
- **Программно:** только «зарегистрировать бандл с таким plist» (либо reflection в HashMap — хак).
  Юзерского механизма scope→key без кода НЕ СУЩЕСТВУЕТ (ответ на open question 3 плана: НЕТ,
  штатного `customHighlightingColors` через Settings UI задать нельзя; и не нужно — см. §4).

## 2. Резолв селектора в ключ (`TextMateTheme.getTextAttributesKey`, theme.txt)

```
1. EXTENSIONS_KEY_MAPPING[scope]           → готовый ключ (java-точечные)
2. EXTENSIONS_MAPPING[scope] → имя → TextAttributesKey.find(имя)   (найден → он)
3. иначе DEFAULT_COLORS_PROVIDER.getTextAttributesKey(scope)        (generic-таблица или TEXT)
```
Для vue-script достаточно ступени 3: `keyword.*`/`string.*`/`comment.*`-токены → KEYWORD/STRING/LINE_COMMENT.

## 3. Потребитель: `TextMateHighlighter.getTokenHighlights` (highlighter.txt, псевдокод)

```
если tokenType не TextMateElementType → PlainSyntaxHighlighter (⇒ монохром)
customColors = TextMateService.getCustomHighlightingColors()          // §1.4
rules = union(customColors.keySet, TextMateTheme.INSTANCE.getRules()) // ~164+ селекторов
scope  = trimEmbeddedScope(tokenType)
matched = TextMateScopeComparatorCore(weigher, scope, identity)
            .sortAndFilter(rules)       // оставить weigh > 0; сортировка лучший-первый
result  = ContainerUtil.reverse(matched) // ← массив ХУДШИЙ-ПЕРВЫЙ (лучший — последним)
return result.map { sel ->
    customColors[sel]?.getTextAttributesKey(TextMateTheme.INSTANCE)  // литеральные бандл-цвета
    ?: TextMateTheme.INSTANCE.getTextAttributesKey(sel)              // статичная карта §1–2
}
```

### 3.1 `TextMateScopeComparatorCore` (comparator-core.txt)

- `compare(a,b)` = `weigher.weigh(selector(a), scope).compareTo(weigher.weigh(selector(b), scope))`.
- `TextMateWeigh = (weigh: Int, priority: Priority)`; `weigh > 0` ⇔ селектор сматчил scope-chain.
- `sortAndFilter`: filter `weigh > 0` → sort best-first. `max`: максимальный weigh, null если нет матчей.
- IDE-обёртка `TextMateScopeComparator` (comparator.txt) — то же + статический
  `TextMateCachingSelectorWeigher(TextMateSelectorWeigherImpl)`.
- Матчинг селектора к цепочке — стандартная TextMate-семантика в `TextMateSelectorWeigherImpl`
  (префикс по границе точки, composite-селекторы с пробелами, `L:`-descendants; детали — core jar,
  для краски достаточно `weigh > 0` + порядок).

### 3.2 `trimEmbeddedScope` — ТОЧНАЯ семантика (уточнение RC9/S2!)

```
scope = token.getScope(); collected = []
пока scope != null:
    name = scope.getScopeName()
    если name содержит ".embedded.":
        return НОВАЯ цепочка только из collected   // rebuilt: EMPTY.add(collected[last..0])
    collected.add(name); scope = scope.getParent()
return token.getScope()   // ".embedded." нигде не встретился — цепочка НЕ тронута
```

- Обход **снизу вверх** (от токена к корню). При первом имени, **содержащем подстроку `.embedded.`**,
  это имя И ВСЕ ЕГО РОДИТЕЛИ выбрасываются; остаются только уже собранные нижние (токен-близкие) имена.
- v1.8.3 (наши контейнеры без «embedded») ⇒ ветка обрезки НЕ срабатывает, цепочка приходит целиком —
  значит после v1.8.3 generic-правила из §1.3 формально ДОЛЖНЫ матчить `keyword.*`-токены script'а
  (как в .ts). Раз визуально монохром (S3) — **барьер НЕ в карте правил**: остаются (а) массив ключей
  не доходит до рендера (потребитель .vue — `TextMateEditorHighlighterProvider$TextMateLexerEditorHighlighter`,
  S5), (б) ключ→attrs резолв, (в) другой проход краски. Это и есть программа A2/A3.

## 4. Следствия для решения (вход в D1)

1. **Кандидат №1 открытого вопроса 3 (юзерский scope→key без кода) — НЕ существует.** Но он и не нужен:
   generic-правила §1.3 уже ведут на стандартные ключи (KEYWORD/STRING/…), которые любая схема
   (включая нашу «Catppuccin Macchiato + Web») красит через DEFAULT_\*. Проблема vue-script — не в правилах.
2. `customHighlightingColors` (бандл-цвета) — анти-паттерн для нас: литеральные цвета вне схемы,
   сломают тему юзера. Не используем.
3. Единственная статичная точка расширения карты правил — НЕТ (все три источника захардкожены;
   $1/$2 — static final HashMap-подклассы, DEFAULT_HIGHLIGHTING_RULES — Map.ofEntries).
   Свой scope→key мост означал быreflection/свой highlighter — оценивать в D1 только если A2 покажет,
   что дефолтного матчинга не хватает.
4. Массив ключей из getTokenHighlights идёт «worst-first» (лучший последним) — при анализе DIAG3/A2
   учитывать, что порядок в массиве — слои подсветки, не приоритет первого элемента.

## 5. Чек-лист ответов на задачи A1

- [x] полный список `TextMateTheme.getRules()` — 164 селектора, три источника (§1.1–1.3), generic найдены
- [x] `TextMateService.getCustomHighlightingColors()` — тип/наполнение/хранение/программность/UI (§1.4)
- [x] `TextMateScopeComparatorCore.sortAndFilter` — семантика выбора (§3.1)
- [x] таблица «источник → селекторы → ключи» (§1)
- [x] ответ S4-загадки (TL;DR + §1.3)
