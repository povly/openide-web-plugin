# plugin.xml: карта extension points

Источник: `resources/META-INF/plugin.xml` (v1.9.1). build.sh на шаге [0/4] валидирует
этот файл как XML (python3 `xml.etree`) — невалидный XML валит сборку до javac.

## Шапка дескриптора

| Элемент | Значение | Почему |
|---|---|---|
| `<id>` | `povly.webfrontendmini` | Идентификатор плагина в IDE/marketplace |
| `<version>` | (текущая, напр. `1.9.1`) | **Single source of truth** для версии; build.sh грепает её для echo, changelog ведётся в `<description>` |
| `<idea-version since-build="262"/>` | платформа 2026.2+ | Форк OpenIDE; ниже не тестировалось |
| `<depends>` | `com.intellij.modules.platform`, `org.jetbrains.plugins.textmate` | TextMate — **единственная** зависимость; предустановлен в IDEA/OpenIDE, доустановится сам |

`<description>` содержит построчный changelog (v1.2 → v1.9.1) — каждая новая версия
дописывает туда своё краткое описание изменений (см. `references/BUILD-RELEASE.md`).
Внимание при правках sed'ом: закрывающий `</description>` уже однажды терялся
(коммит «restore closing </description>…») — всегда прогоняй `./build.sh`.

## Extension points (defaultExtensionNs="com.intellij")

### 1. fileType-бины — отбор расширений у платформы

```xml
<fileType id="WFM_CSS"  name="WFM CSS"       implementationClass="povly.wfm.WfmCssFileType"  fieldName="INSTANCE" extensions="css" />
<fileType id="WFM_JSTS" name="WFM JavaScript" implementationClass="povly.wfm.WfmJsTsFileType" fieldName="INSTANCE" extensions="js;mjs;cjs;ts;tsx;jsx" />
<fileType id="WFM_VUE"  name="WFM Vue"       implementationClass="povly.wfm.WfmVueFileType"  fieldName="INSTANCE" extensions="vue" />
```

Зачем каждый:

- **WFM_CSS** (`css`): платформа шлёт bundled «CSS (syntax highlighting only)»
  AbstractFileType (defaultFileTypes.xml), который иначе выигрывает расширение и не
  пускает TextMate к файлу. Наш бин перебивает claim.
- **WFM_JSTS** (`js;mjs;cjs;ts;tsx;jsx`): две причины — (1) `js` занят платформенным
  «JavaScript (syntax highlighting only)» AbstractFileType; (2) `ts/tsx/jsx/mjs/cjs` не
  заняты никем, а грамматики shiki js/ts не объявляют своих `fileTypes`, поэтому без
  явного типа extension-mapping вообще не находит грамматику.
- **WFM_VUE** (`vue`): без явного claim `.vue` полагается на textmate-плагиновский
  `TextMateFileDetector` (order="last") — в этом билде detector-типированные файлы дали
  деградированную подсветку многострочных контекстов (script/style интерьеры); явный
  LanguageFileType-бин ставит `.vue` на тот же код-путь, что css/js. Плюс имя/иконка в
  UI и стабильный тип для LSP-маппингов.

Язык внутри бинов НЕ задается XML-атрибутом — см. `references/TEXTMATE-QUIRKS.md` §2.

### 2. editorHighlighterProvider — свой путь подсветки редактора

```xml
<editorHighlighterProvider filetype="WFM Vue" implementationClass="povly.wfm.WfmEditorHighlighterProvider" />
<editorHighlighterProvider filetype="WFM JavaScript" implementationClass="povly.wfm.WfmEditorHighlighterProvider" />
<editorHighlighterProvider filetype="WFM CSS" implementationClass="povly.wfm.WfmEditorHighlighterProvider" />
```

Регистрируется **по имени fileType** (по одному на каждый из трёх типов). Даёт
LexerEditorHighlighter на TextMate-грамматике + whole-document re-lex fix (иначе
embedded-контексты vue умирают на инкрементальных переразборах — детали в
`references/TEXTMATE-QUIRKS.md` §3). Без него — стандартный путь подсветки textmate-плагина.

### 3. bundledColorScheme — схемы «Catppuccin + Web» с явными id

```xml
<bundledColorScheme id="povly.wfm.mocha.web"     path="/colorScheme/WfmMocha" />
<bundledColorScheme id="povly.wfm.macchiato.web" path="/colorScheme/WfmMacchiato" />
<bundledColorScheme id="povly.wfm.frappe.web"    path="/colorScheme/WfmFrappe" />
<bundledColorScheme id="povly.wfm.latte.web"     path="/colorScheme/WfmLatte" />
```

- Явные `id` держат ключи схем стабильными; без них используется path-derived ключ
  + startup WARN.
- Схемы пере-добавляют DEFAULT_\*-ключи, которых нет в `.theme.json`-варианте Catppuccin
  (без них TextMate-подсветка монохромна), и содержат LSP_\*-ключи для lsp4ij
  semantic tokens.
- Правила содержимого схем — `references/GRAMMARS-SCHEMES.md`.

### 4. spellchecker.support — спелчек

```xml
<spellchecker.support language="textmate" implementationClass="povly.wfm.WfmSpellcheckingStrategy" />
```

`language="textmate"` — единый язык, на который резолвятся все грамматики бандла.
Стратегия исключает тела тегов `<...>` из проверки (не флудит spellchecker именами
тегов/атрибутов), остальное идёт через платформенный `PlainTextSplitter`.

### 5. gotoDeclarationHandler — Ctrl+B по src в .vue

```xml
<gotoDeclarationHandler id="WfmVueResourceGoToHandler" implementationClass="povly.wfm.WfmVueResourceGoToHandler" order="last" />
```

`order="last"` — обработчик смотрит только строки `<style|script ... src="...">` в `.vue`
и не должен перехватывать чужие навигации. Ни Volar (нет documentLink/definition для этих
атрибутов — проверено пробой, research/session4), ни TextMate (нет PSI-ссылок) такие пути
не резолвят.

### 6. textmate.bundleProvider — регистрация бандла

```xml
<textmate.bundleProvider implementation="povly.wfm.WebFrontendBundleProvider" />
```

Единственная точка входа TextMate-интеграции: провайдер извлекает бандл из jar в
cache-каталог и отдаёт `PluginBundle("web-frontend-mini", bundleRoot)`. Требования к
layout — `references/TEXTMATE-QUIRKS.md` §1.

## Чек-лист правки plugin.xml

- [ ] XML валиден (прогнать `./build.sh` — шаг [0/4]).
- [ ] Новое расширение файла → соответствующий fileType-бин (или расширение в существующем).
- [ ] Новый класс → `implementationClass` с полным именем `povly.wfm.*`.
- [ ] Изменения поведения → строка в changelog внутри `<description>`.
- [ ] Версия меняется только в `<version>`.
