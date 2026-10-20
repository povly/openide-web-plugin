# Quirks платформы (root-cause знания из Javadoc и research/)

Все факты ниже прослеживаются до Javadoc `src/povly/wfm/*.java`, README и
`research/session*/`. Это причины, по которым код устроен именно так — не менять,
не поняв соответствующий пункт.

## 1. Bundle layout: почему «unknown format»

Ошибка «Пакет web-frontend-mini имеет неизвестный формат» = `BundleType.Companion.detectBundleType()`
вернул UNDEFINED. Алгоритм (по TOP-LEVEL листингу каталога бандла, `Files.list`,
не-рекурсивно, только regular files):

| Проверка верхнего уровня | Тип бандла |
|---|---|
| имя каталога заканчивается `.tmBundle` | TEXTMATE |
| есть `package.json` | VSCODE |
| любой файл `*.tmLanguage` / `*.tmPreferences` | SUBLIME |
| есть `info.plist` | TEXTMATE |
| иначе | UNDEFINED → error notification |

Провал v1.1: каталог `wfm-bundle` содержал только директорию `syntaxes` — директории
фильтруются, тип UNDEFINED. С v1.2 layout правильный:

```
<bundleRoot>/info.plist
<bundleRoot>/Syntaxes/*.tmLanguage | *.plist | *.tmLanguage.json   (case-sensitive)
```

TEXTMATE-reader читает грамматики из поддиректории `Syntaxes`. Провайдер извлекает
файлы из jar (`/bundle/...`) в `~/.cache/OpenIDE/OpenIDE2026.2/wfm-bundle-v2/`
— путь захардкожен в `WebFrontendBundleProvider` под OpenIDE 2026.2. Извлечение
идемпотентное (`REPLACE_EXISTING` на каждый boot) — обновления грамматик применяются
без ручной чистки кэша.

Новая грамматика = файл в `resources/bundle/Syntaxes/` + строка в
`WebFrontendBundleProvider.BUNDLE_FILES` (обязательны ОБА места; имя должно
удовлетворять фильтру ридера).

## 2. fileType-бины: разрешение языка

Паттерн всех трёх бинов (`WfmCssFileType` / `WfmJsTsFileType` / `WfmVueFileType`):

```java
public final class WfmXxxFileType extends LanguageFileType implements TextMateBackedFileType {
    private static Language baseLanguage() {
        Language textMate = Language.findLanguageByID("textmate");
        return textMate != null ? textMate : PlainTextLanguage.INSTANCE;
    }
    @Override public boolean isSecondary() { return true; }
}
```

Почему так:

- `LanguageFileType` + `TextMateBackedFileType` — маркер textmate-интеграции + иконки.
- **Язык в конструкторе, не в XML**: fileType-бины могут инстанцироваться раньше, чем
  textmate-плагин зарегистрирует свой язык. В v1.0 язык задавался XML-атрибутом и плагин
  ловил «Incorrect language … should be textmate, actual null». Программный
  `findLanguageByID("textmate")` с fallback на `PlainTextLanguage` решает тайминг.
- `isSecondary() = true` — если платформа когда-нибудь заведёт полноценный язык
  (CSS/JS), он останется primary.

## 3. Whole-document re-lex (сердце подсветки редактора)

`WfmEditorHighlighterProvider.WfmLexerEditorHighlighter`:

```java
@Override
public synchronized void documentChanged(DocumentEvent event) {
    setText(event.getDocument().getText());
}
```

Root cause: state TextMate-лексера — бинарный (0 = чистый конец строки, 1 =
внутри контекста) и НЕ несёт стека контекстов. Любой инкрементальный рестарт внутри
embedded-области (vue `<script>` → `source.ts`) пере-лексирует её корневой грамматикой —
script теряет всю окраску. Доказано харнессом: непрерывный проход давал 259
`source.ts`-токенов, после рестарта строки — 24. Отсюда правило: на каждое изменение
документа re-lex всего документа с начального state — непрерывный проход всегда корректно
re-входит в embedded-контекст.

Тот же класс: `LexerEditorHighlighter` с `TextMateLexerDataStorage` (упаковка множества
`TextMateElementType` в shorts), внутри — `TextMateHighlighter` из дескриптора грамматики
по имени файла (`getLanguageDescriptorByFileName`); нет грамматики → plain-text
highlighter (как upstream textmate).

## 4. Scope-имена и `.embedded.` (фикс v1.9.0, RC10)

История (не повторять):

- **RC10, root cause монохрома vue-script**: generic-селектор `text source` (из
  `TextMateDefaultColorsProvider`) матчит любую scope-цепочку вида
  `…source.* ← … ← text.*` → к ключам токена добавляется
  `DEFAULT_TEMPLATE_LANGUAGE_COLOR` **последним** (высший weigh) →
  `LayeredTextAttributes.merge` кладёт его FOREGROUND поверх семантического цвета
  (keyword/string/comment). В Catppuccin/Wfm-темах у этого ключа бледный fg → вся
  секция script одним цветом.
- **v1.8.3 не работала**: переименование «убрать .embedded. из имён» — обрезка ни при
  чём, пока в цепочке живой предок `text.html.vue`.
- **Фикс F2 (грамматика)**: контейнерные scope-имена в `vue.tmLanguage.json`,
  обёртывающие include грамматик, называть с `.embedded.`:
  `source.ts.embedded.vue`, `source.tsx.embedded.vue`, `source.css.embedded.vue`
  (10 замен; **include-строки НЕ трогать** — только `"name"` контейнеров). Штатный
  платформенный `trimEmbeddedScope` отрезает `.embedded.`-имя и всех text-предков →
  селектор `text source` больше не матчится. Тот же механизм, которым стоковый HTML
  защищает inline-JS.
- **Фикс F1 (схемы)**: в `Wfm*.xml` у `DEFAULT_TEMPLATE_LANGUAGE_COLOR` нет собственного
  FOREGROUND (`baseAttributes="TEXT"`, как Darcula) — слой остаётся, но merge ничего
  не перекрывает.

Правила на будущее:

- scope-имена embedded-контейнеров обязаны содержать `.embedded.`, иначе монохром
  вернётся (высший слой `text source`);
- `trimEmbeddedScope` срабатывает на первом имени со `.embedded.`, сохраняя всё
  нижележащее — семантические селекторы (keyword/string) остаются;
- jsx/tsx-вставки внутри `.js`/`.ts` не окрашены — upstream-ограничение textmate-движка
  (embedded-скопы); если понадобится — тот же приём, что в vue v1.9.0.

## 5. WfmHighlighterStack: стек matcher'ов

`WfmHighlighterStack` — точная копия стека, который in-product собирает
`TextMateSyntaxHighlighterFactory$Inner`:

```
CaffeineCachingRegexProvider( RememberingLastMatchRegexFactory( JoniRegexFactory() ) )
+ TextMateCachingSelectorWeigher( TextMateSelectorWeigherImpl() )
→ TextMateCachingSyntaxMatcher( TextMateSyntaxMatcherImpl(regexProvider, weigher) )
```

Держится в одном месте, чтобы editor highlighter и pass-based highlighter вели себя
идентично. `LINE_LIMIT = 20000` — default реестра `textmate.line.highlighting.limit`.

## 6. Joni-ограничение (факт S4, research/session2)

Variable-length look-behind в regex'ах грамматик **не компилируется** joni-движком
(аудит `RegexAudit.java` по всем regex'ам). При правке/добавлении грамматики с
look-behind — проверять, что он fixed-length. Харнесс аудита:
`research/session2/RegexAudit.java` (вход — `regexes.b64`).

## 7. Прочие факты платформы

- Catppuccin `.theme.json`-вариант тем не содержит НИ ОДНОГО из 27 DEFAULT_\*-ключей
  (xml-вариант содержит все 27) — отсюда требование выбирать схему
  «Catppuccin \<Flavor\> + Web», а не theme.json-вариант.
- Cross-plugin parent schemes в этом форке резолвятся ненадёжно — поэтому Wfm-схемы
  self-contained (`parent_scheme` = Darcula + полная палитра), см.
  `references/GRAMMARS-SCHEMES.md`.
- Окружение эталона: OpenIDE 2026.2.3 (build 262.10968.63.1, JBR 25) в
  `/usr/share/openide`; textmate-модули в `/usr/share/openide/plugins/textmate-plugin/lib/modules/`.
