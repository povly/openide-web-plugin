# Implementation Plan: Prettier/blade-formatter как внешний форматтер (v1.15.0)

Branch: none (git.create_branches=false — работа на main)
Created: 2026-10-20
Mode: full
Исследование: `.ai-factory/references/FORMATTERS-RESEARCH.md` (пользователь выбрал опцию A)

## Settings

- Testing: yes — движок-лаборатория остаётся для старого кода; новый сервис = процесс-интеграция,
  проверяется вручную в IDE (конвенция проекта); чистая логика сборки CLI — plain-тестом
- Logging: verbose — `wfm:` префикс, INFO на каждый формат (файл, инструмент, мс), WARN при ошибках
- Docs: yes — README EN/RU + версия 1.15.0 + change-notes

## Tasks

- [x] **Task 1: WfmPrettierFormattingService** (AsyncDocumentFormattingService)
  Файлы: `src/povly/wfm/WfmPrettierFormattingService.java` (новый), `resources/META-INF/plugin.xml`.
  - `canFormat`: `.blade.php` → резолв blade-formatter; наши веб-типы (css/scss/less/js/mjs/cjs/ts/tsx/jsx/vue
    по fileType-бину) → резолв prettier; иначе false (платформа уходит в core — фолбэка больше нет, см. Task 2).
  - `createFormattingTask`: процесс `node <prettier.cjs> --stdin-filepath <имя>` (cwd = каталог проекта →
    .prettierrc подхватывается сам) либо `node <blade-formatter.cjs> --stdin`; текст в stdin UTF-8,
    stdout → `request.onTextReady`; не-нулевой exit/пустой вывод → `onError` + stderr.
  - Резолв (кэш 60с): node — registry `wfm.node.path` → `~/.nvm/versions/node/*/bin/node` (новейший) → `node`
    из PATH; prettier — `node_modules` проекта → global (`<nodeDir>/../lib/node_modules`); blade — global.
  - `getFeatures` = FORMAT_FRAGMENTS; `getName` "Prettier"; `getNotificationGroupId` "Web Frontend Mini";
    `getTimeout` 20s.
- [x] **Task 2: Уход встроенного форматтера (источник дрейфа)**
  Файл: `plugin.xml` — снять обе регистрации `<lang.formatter>` (textmate + Blade).
  Root-cause: стартовый `DetectAndAdjustIndentOptionsTask` строит модели по нашему lang.formatter и
  переписывает файлы при каждом открытии (доказано логами 05:28–05:29); провайдер опций на него не влияет.
  Классы остаются в исходниках (лаборатория тестирует WfmBlock; Javadoc фиксирует причину retire).
  `<fileIndentOptionsProvider>` остаётся — стабилизирует Enter-отступы (tmPreferences).
- [x] **Task 3: plugin.xml-обвязка**
  `<formattingService implementationClass="…" order="first"/>` (перед CoreFormattingService — order="last"),
  `<notificationGroup id="Web Frontend Mini" displayType="BALLOON"/>`.
- [x] **Task 4: Документация и версия**
  README EN/RU: раздел Formatting переписать (prettier primary; требования Node + prettier в проекте или
  глобально; blade-formatter для .blade.php; встроенный форматтер удалён — история). plugin.xml 1.15.0 + change-notes.

## Commit Plan

- Commit 1: `feat: v1.15.0 - external formatting via Prettier and blade-formatter (built-in formatter retired)`

## Верификация вручную (пользователь)

1. products.bronber-store (.vue/.module.css, локальный prettier + .prettierrc): Ctrl+Alt+L —
   форматирование prettier-правилами (useTabs, tabWidth 2 из .prettierrc), повторное нажатие — 0 изменений.
2. bronber-store (.blade.php): Ctrl+Alt+L — blade-formatter.
3. `grep -a 'wfm: prettier' idea.log`.
