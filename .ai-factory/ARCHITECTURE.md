# Архитектура: Layered (модель extension-point реестра IntelliJ-платформы)

## Обзор

web-frontend-mini дополняет OpenIDE (форк IntelliJ IDEA Community, since-build 262) веб-стеком:
подсветка синтаксиса через TextMate-грамматики, цветовые схемы Catppuccin «+ Web»,
spellchecking и навигация `Ctrl+B` по внешним ресурсам `.vue`. Архитектура проекта повторяет
модель, которую навязывает сама платформа: наверху декларативный реестр расширений
(`plugin.xml`), под ним тонкие Java-адаптеры в едином плоском пакете `povly.wfm`, ниже
самодостаточные ресурсы (TextMate-бандл и XML-схемы) и сборочный скрипт `build.sh`,
который упаковывает всё в один zip.

Это слоистая (Layered) организация в терминах платформы, а не бизнес-логики: «слои» здесь
это этапы интеграции (декларация → классы → ресурсы → упаковка), а не
Controller/Service/Repository. Доменной логики в проекте нет: плагин только связывает
расширения файлов, грамматики и механики редактора. Решение сознательно минималистично:
8 классов, отсутствие системы сборки и тестов; проверка выполняется сборкой и ручными
проверками в IDE (`grep -a 'Web Frontend Mini' idea.log`).

## Обоснование выбора

- **Тип проекта:** плагин IntelliJ-платформы (OpenIDE fork); всё поведение подключается
  через extension points, поэтому композиционный корень задан платформой (`plugin.xml`),
  а не выбирается командой.
- **Стек:** Java 21 (`javac --release 21`), `build.sh` на bash, без Maven/Gradle,
  без БД/ORM, без тестового каркаса.
- **Ключевой фактор:** по матрице выбора (команда из 1 человека, низкая сложность домена,
  низкие требования к масштабу) Layered подходит напрямую. Дополнительно модель расширений
  платформы сама формирует слои: `plugin.xml` это реестр и точка сборки, классы это
  адаптеры к extension points, ресурсы это данные, `build.sh` это упаковка.

## Структура каталогов

Фактическая структура репозитория (без выдуманных слоёв):

```text
openide-web-plugin/
├── src/povly/wfm/                        # Java-интеграции: один плоский пакет, 8 классов
│   ├── WebFrontendBundleProvider.java    # textmate.bundleProvider: распаковка бандла при старте
│   ├── WfmCssFileType.java               # fileType: перехват расширения .css у платформы
│   ├── WfmJsTsFileType.java              # fileType: js/mjs/cjs/ts/tsx/jsx
│   ├── WfmVueFileType.java               # fileType: vue
│   ├── WfmEditorHighlighterProvider.java # editorHighlighterProvider: LexerEditorHighlighter + re-lex
│   ├── WfmHighlighterStack.java          # общий regex/matcher-стек textmate
│   ├── WfmSpellcheckingStrategy.java     # spellchecker.support для language "textmate"
│   └── WfmVueResourceGoToHandler.java    # gotoDeclarationHandler: Ctrl+B по src="..." в .vue
├── resources/
│   ├── META-INF/plugin.xml               # дескриптор: единственный реестр расширений и версий
│   ├── bundle/                           # TextMate-бандл: info.plist + Syntaxes/ (9 грамматик)
│   └── colorScheme/                      # 4 XML-схемы «Catppuccin <Flavor> + Web»
├── research/                             # read-only материалы расследований; в поставку не входит
├── build.sh                              # валидация plugin.xml → javac → jar → zip в dist/
├── out/, manifest.mf, dist/              # транзитные артефакты сборки, создаются build.sh
├── README.md / README.ru.md              # пользовательская документация и MIT-атрибуция
└── .ai-factory/                          # AI-контекст проекта (DESCRIPTION, ARCHITECTURE, rules)
```

## Правила зависимостей

```text
IntelliJ Platform / OpenIDE (lib/*, plugins/textmate-plugin/lib/modules/*)
        ↑ classpath собирает build.sh из OPENIDE_HOME
        │
plugin.xml (декларации; implementationClass ссылается на povly.wfm по имени)
        │ платформа загружает классы при старте
        ↓
povly.wfm → (classloader) → resources/bundle/, resources/colorScheme/
```

- ✅ `povly.wfm` зависит только от API IntelliJ-платформы и API плагина
  `org.jetbrains.plugins.textmate`. Других зависимостей нет и быть не должно: единственные
  `<depends>` в `plugin.xml` это `com.intellij.modules.platform` и
  `org.jetbrains.plugins.textmate`.
- ✅ `plugin.xml` ссылается на `povly.wfm` строковыми именами классов
  (`implementationClass`); резолвинг выполняет платформа в runtime.
- ✅ `WebFrontendBundleProvider` читает ресурсы `/bundle/*` через classloader
  (`getResourceAsStream`). Ресурсы самодостаточны и ничего не знают о Java-коде.
- ✅ `build.sh` зависит от `plugin.xml` (извлечение `<version>`, XML-валидация) и от
  `OPENIDE_HOME` (classpath для javac).
- ❌ Грамматики ↔ Java-код: единственная точка связи это список
  `WebFrontendBundleProvider.BUNDLE_FILES`. Добавление грамматики требует правки ровно в
  двух местах: файл в `resources/bundle/` и запись в `BUNDLE_FILES`.
- ❌ `povly.wfm` → `research/` запрещено: это read-only материалы расследований
  (декомпиляция web-tools, история раскраски vue); в jar попадают только `out/` и
  `resources/`, ссылки на research из рантайма не допускаются.
- ❌ Любые сторонние библиотеки и системы сборки: classpath состоит только из
  платформенных jar.
- Циклов нет: направление строгое, platform → plugin.xml → классы → ресурсы.

## Взаимодействие слоёв (runtime data flow)

1. **Старт IDE.** Платформа читает `plugin.xml`, инстанцирует fileType-бины
   (`fieldName="INSTANCE"`), регистрирует три `editorHighlighterProvider` (по имени
   filetype), четыре `bundledColorScheme`, `spellchecker.support`
   (language="textmate"), `gotoDeclarationHandler` (order="last") и
   `textmate.bundleProvider`.
2. **Бандл.** TextMate-плагин вызывает `WebFrontendBundleProvider.getBundles()`. Провайдер
   распаковывает `BUNDLE_FILES` из `/bundle/` в
   `<user.home>/.cache/OpenIDE/OpenIDE2026.2/wfm-bundle-v2/` (идемпотентно,
   `REPLACE_EXISTING`, при каждом старте, поэтому обновления грамматик применяются без
   чистки кэша) и возвращает `PluginBundle`. Регистратор бандла требует layout
   `info.plist` + `Syntaxes/` на верхнем уровне, иначе возвращает «неизвестный формат».
3. **Раскраска.** Открытие файла → fileType-бин перехватывает расширение → платформа
   запрашивает highlighter у `WfmEditorHighlighterProvider` →
   `TextMateService.getLanguageDescriptorByFileName()` подбирает грамматику по имени файла
   → `TextMateHighlightingLexer` с общим `WfmHighlighterStack.MATCHER` →
   `WfmLexerEditorHighlighter` (наследник `LexerEditorHighlighter`) с
   `TextMateLexerDataStorage` (упаковка множества `TextMateElementType` в shorts).
4. **Re-лекс всего документа.** `documentChanged()` вызывает `setText()` на полном тексте
   документа: состояние TextMate-лексера бинарно (0 = чистый конец строки, 1 = внутри
   контекста) и не несёт контекст-стек, поэтому инкрементальный перезапуск внутри
   embedded-области (vue `<script>` → `source.ts`) перекрашивает её корневой грамматикой и
   скрипт теряет цвета. Полный проход от начального состояния всегда заново входит в
   embedded-контекст корректно.
5. **Language.** Во всех fileType-бинах язык резолвится в коде
   (`Language.findLanguageByID("textmate")` с fallback на `PlainTextLanguage`), а не через
   XML-атрибут: бины могут создаться раньше, чем textmate-плагин зарегистрирует свой язык.
6. **Spellchecking.** `WfmSpellcheckingStrategy` зарегистрирован на language "textmate"
   (единый язык всех грамматик бандла); тела тегов `<...>` исключаются регулярным
   выражением, остальное идёт через `PlainTextSplitter`.
7. **Навигация.** `WfmVueResourceGoToHandler` (order="last", срабатывает последним)
   регуляркой ищет `src|source="..."` на строке тега `<style|script>` внутри `.vue` и
   резолвит относительный путь от каталога файла через
   `VirtualFile.findFileByRelativePath()`.

## Ключевые принципы

1. **plugin.xml как центр реестра.** Каждая интеграция с платформой оформлена extension
   point'ом и отдельным классом; новая механика без декларации в `plugin.xml` не
   существует.
2. **Single point of truth для бандла.** `BUNDLE_FILES` ↔ `resources/bundle/`. Правка
   только в одном месте это молчаливая ошибка: распаковка берёт список из Java, jar не
   сканируется.
3. **Тонкие адаптеры.** Классы не содержат бизнес-логики; платформенные quirks
   документируются в Javadoc с root-cause и историей версий (v1.x). Эти Javadoc-заметки
   являются институциональными знаниями проекта и должны сохраняться при правках.
4. **Самодостаточные ресурсы.** Схемы self-contained (`parent_scheme=Darcula` плюс полная
   палитра), кросс-плагиновые родители не резолвятся надёжно в этом форке. Бандл
   распаковывается при каждом старте заново.
5. **Мягкие отказы.** Guard clauses, `null`/пустой результат как «не мой случай»;
   фатально только падение распаковки бандла (`LOG.error` + `UncheckedIOException`).
   Логирование через платформенный `Logger` с префиксом `wfm:`.

## Примечание об организации кода

- **Новые возможности:** следуют слоистой модели выше по мере практической применимости.
- **Существующий код:** структура задокументирована как есть; при правках приоритет
  конвенциям этого документа, но принудительного переписывания несовпадающего кода нет.
- **Совместимость:** когда новый код должен вызывать существующий, предпочтительны ясные
  точки входа (например, общий `WfmHighlighterStack`), но структурная перестройка ради
  «чистоты» не проводится.

## Примеры кода

### Добавление новой грамматики (два обязательных места)

```java
// src/povly/wfm/WebFrontendBundleProvider.java
private static final String[] BUNDLE_FILES = {
        "info.plist",
        "Syntaxes/css.tmLanguage",
        // ... существующие записи ...
        "Syntaxes/pug.tmLanguage.json",   // 1. добавить запись сюда
};
```

```text
# 2. сам файл кладётся рядом с остальными
resources/bundle/Syntaxes/pug.tmLanguage.json
```

Если расширение должно сразу открываться с подсветкой, дополнительно нужен fileType-бин в
`plugin.xml` (или расширение в `extensions="..."` существующего `WFM_JSTS`) и запись
`fileTypes` в саму грамматику. Правка только `resources/bundle/` без `BUNDLE_FILES` файл
теряет: распаковка идёт по явному списку.

### fileType-бин (шаблон для новых расширений)

```java
public final class WfmXxxFileType extends LanguageFileType implements TextMateBackedFileType {
    public static final WfmXxxFileType INSTANCE = new WfmXxxFileType();

    private WfmXxxFileType() {
        super(baseLanguage());
    }

    private static Language baseLanguage() {
        // timing-quirk: бин может создаться раньше регистрации textmate-языка,
        // поэтому резолвим в коде с fallback (подробности в Javadoc WfmCssFileType)
        Language textMate = Language.findLanguageByID("textmate");
        return textMate != null ? textMate : PlainTextLanguage.INSTANCE;
    }

    @Override
    public boolean isSecondary() { return true; } // язык платформы остаётся primary
}
```

```xml
<fileType id="WFM_XXX" name="WFM XXX" implementationClass="povly.wfm.WfmXxxFileType"
          fieldName="INSTANCE" extensions="xxx" />
<editorHighlighterProvider filetype="WFM XXX"
                           implementationClass="povly.wfm.WfmEditorHighlighterProvider" />
```

### Правило зависимостей в build.sh

```bash
# classpath только из установленной OpenIDE: platform lib + textmate модули
CP="$OPENIDE_HOME/plugins/textmate-plugin/lib/modules/*:$OPENIDE_HOME/lib/*"
javac --release "$RELEASE" -cp "$CP" -d "$OUT" src/povly/wfm/*.java
# в jar попадают только скомпилированные классы и resources/; src/ и research/ не поставляются
jar cfm "$DIST/lib/web-frontend-mini.jar" manifest.mf -C "$OUT" . -C resources .
```

## Анти-паттерны

- ❌ Добавить грамматику только в `resources/bundle/` и забыть `BUNDLE_FILES` (или
  наоборот): рассинхрон единственной точки связи Java ↔ ресурсы, файл молча не попадает в
  кэш-бандл.
- ❌ Менять layout бандла: `info.plist` и `Syntaxes/` обязаны лежать в корне
  распакованного каталога, иначе регистратор вернёт «неизвестный формат» и подсветка
  исчезнет целиком.
- ❌ Резолвить language через XML-атрибут fileType-бина: известный отказ v1.0
  («Incorrect language ... should be textmate, actual null»), резолвинг только в коде.
- ❌ «Оптимизировать» `documentChanged()` до инкрементального re-лекса: бинарное состояние
  TextMate-лексера ломает embedded-контексты (история v1.8.2), цвета в vue `<script>`
  пропадут.
- ❌ Ставить `parent_scheme` схемы на схему другого плагина: кросс-плагиновые родители не
  резолвятся надёжно (история v1.8.1), только `Darcula` плюс полная палитра.
- ❌ Вводить Maven/Gradle или разносить классы по под-пакетам: `build.sh` компилирует
  плоский `src/povly/wfm/*.java`, структура сознательно минимальна.
- ❌ Ссылаться на `research/` из рантайма или упаковывать её: это read-only материалы
  расследований, в поставку идут только `out/` и `resources/`.
- ❌ Добавлять заимствованные грамматики/схемы без MIT-атрибуции в README: источники
  (atom, shiki, vuejs/language-tools, catppuccin/jetbrains) обязаны сохранять упоминание.
