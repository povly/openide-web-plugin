---
name: wfm-plugin-dev
description: >-
  Разработка плагина web-frontend-mini (пакет povly.wfm) для OpenIDE — форка IntelliJ IDEA
  Community (since-build 262): extension points в plugin.xml, TextMate-бандл и грамматики,
  цветовые схемы «Catppuccin + Web», спелчек, Ctrl+B-навигация в .vue, сборка build.sh,
  релизы и отладка подсветки. Использовать при правке plugin.xml, грамматик в
  resources/bundle/Syntaxes/, цветовых схем resources/colorScheme/, Java-исходников
  src/povly/wfm/, при сборке/релизе (version bump, README EN+RU, conventional commits)
  и при отладке подсветки веб-файлов в OpenIDE.
metadata:
  version: "1.0"
  category: plugin-development
---

# Разработка плагина web-frontend-mini (wfm)

Плагин добавляет в OpenIDE TextMate-подсветку веб-файлов
(`.css .scss .less .js .mjs .cjs .ts .tsx .jsx .vue`), 4 схемы
«Catppuccin \<Flavor\> + Web», spellchecking и `Ctrl+B`-навигацию по
`<style|script src="...">` в `.vue`. Это clean-room замена плагина web-tools.
Стек: Java 21 (`javac --release 21`), сборка только `./build.sh` — Maven/Gradle нет,
тестов нет: проверка = сборка + ручная проверка в IDE.

8 классов в плоском пакете `povly.wfm`:

| Класс | Роль |
|---|---|
| `WebFrontendBundleProvider` | Извлечение TextMate-бандла в cache-каталог, список `BUNDLE_FILES` |
| `WfmCssFileType` / `WfmJsTsFileType` / `WfmVueFileType` | fileType-бины, отбирающие расширения у platform AbstractFileTypes |
| `WfmEditorHighlighterProvider` | Editor highlighter для WFM-типов + whole-document re-lex fix |
| `WfmHighlighterStack` | Regex/matcher-стек, зеркалирующий in-product textmate-стек |
| `WfmSpellcheckingStrategy` | Спелчек TextMate-файлов без тел тегов |
| `WfmVueResourceGoToHandler` | `Ctrl+B` по `src="..."` в `<style>`/`<script>` строки `.vue` |

## Когда использовать этот скилл

- правка `resources/META-INF/plugin.xml` (extension points, версия, changelog);
- правка/добавление грамматик в `resources/bundle/Syntaxes/`;
- правка цветовых схем `resources/colorScheme/Wfm*.xml`;
- правка Java-исходников `src/povly/wfm/`;
- сборка и релиз (`./build.sh`, version bump, README, коммиты);
- отладка подсветки/спелчека/навигации веб-файлов в OpenIDE.

## Золотые правила

1. **Перед правкой Java/плагина** читать этот скилл и Javadoc классов `src/povly/wfm/` —
   там root-cause знания о quirks платформы.
2. **Любое изменение → `./build.sh`** — это единственная проверка компиляции
   (валидация plugin.xml + `javac --release 21` + jar + zip в `dist/`).
3. **Новая грамматика = два места**: файл в `resources/bundle/Syntaxes/` И запись в
   `WebFrontendBundleProvider.BUNDLE_FILES`. Пропуск любого — грамматика не доедет до IDE.
4. **Layout бандла неизменен**: `info.plist` + `Syntaxes/*.tmLanguage[.json]` в корне —
   иначе ошибка «Пакет web-frontend-mini имеет неизвестный формат» (детали:
   `references/TEXTMATE-QUIRKS.md`).
5. **Язык в fileType-бинах** разрешается только в коде:
   `Language.findLanguageByID("textmate")` с fallback `PlainTextLanguage.INSTANCE`,
   никогда через XML-атрибут (v1.0 ловила «Incorrect language … should be textmate,
   actual null»).
6. **Embedded-контексты vue** (`<script>` → `source.ts`): контейнерные scope-имена обязаны
   содержать `.embedded.` (механика `trimEmbeddedScope`), а re-lex в редакторе — только
   whole-document (state лексера бинарный). Нарушение любого из двух возвращает
   монохром/потерю окраски script.
7. **`research/` — только чтение**, в сборку не включать. web-tools изучался
   декомпиляцией — код и ресурсы из него не копировать (clean-room, см.
   `references/CLEAN-ROOM.md`).
8. **Релиз**: версия — только в `<version>` plugin.xml, changelog дописывается в
   `<description>`; `README.md` и `README.ru.md` обновляются вместе; коммиты
   conventional (`feat:` / `fix:` / `docs:` / `chore:` / `research:`); релизная сборка —
   без диагностических startup-активностей (урок v1.5→v1.6).
9. **Подсветка ≠ LSP**: окраска/схемы/спелчек/фолдинг даёт плагин; подсказки и переходы —
   LSP-серверы через LSP4IJ (настройка только через GUI). При дебаге «ничего не работает»
   сначала отделить слой (таблица: `references/TROUBLESHOOTING.md`).

## Карта типичных задач

| Задача | Что править | Справка |
|---|---|---|
| Добавить язык/грамматику | `resources/bundle/Syntaxes/` + `BUNDLE_FILES` | `references/GRAMMARS-SCHEMES.md` |
| Изменить подсветку/цвета | грамматика (scope-имена) или `Wfm*.xml` (ключи) | `references/GRAMMARS-SCHEMES.md`, `references/TEXTMATE-QUIRKS.md` |
| Новый extension point / версия | `resources/META-INF/plugin.xml` | `references/PLUGIN-XML.md` |
| Собрать/выпустить | `./build.sh`, `<version>`, README ×2 | `references/BUILD-RELEASE.md` |
| «Подсветка сломалась» | сначала таблица симптомов | `references/TROUBLESHOOTING.md` |
| Изучить web-tools / платформу | `research/` (read-only), харнессы | `references/CLEAN-ROOM.md` |

## Быстрая отладка в IDE

```bash
# лог плагина (OpenIDE 2026.2):
grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log | tail -1
```

После установки: открыть любой `.vue` — template и `<script>` окрашены; схема
«Catppuccin \<Flavor\> + Web» выбрана в Settings → Editor → Color Scheme.

## Справочники

- `references/PLUGIN-XML.md` — карта extension points и зачем каждый нужен
- `references/TEXTMATE-QUIRKS.md` — quirks платформы: bundle layout, fileType-бины,
  re-lex, scope-имена `.embedded.`, matcher-стек
- `references/GRAMMARS-SCHEMES.md` — грамматики (добавление, источники, лицензии)
  и правила схем «Catppuccin + Web»
- `references/BUILD-RELEASE.md` — конвейер build.sh, требования, release checklist
- `references/TROUBLESHOOTING.md` — полная таблица симптом → причина → решение
- `references/CLEAN-ROOM.md` — clean-room политика, каталог research/, атрибуция
