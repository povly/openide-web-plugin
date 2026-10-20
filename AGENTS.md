# AGENTS.md

> Карта проекта для AI-агентов. Обновлять при существенных изменениях структуры (создаётся `/aif`).

## Обзор проекта

Плагин **web-frontend-mini** для OpenIDE (форк IntelliJ IDEA Community): syntax highlighting
веб-файлов (`.css .scss .less .js .ts .jsx .vue`) через TextMate-грамматики, цветовые схемы
«Catppuccin + Web», spellchecking и `Ctrl+B`-навигация по внешним ресурсам `.vue`. Clean-room
замена плагина web-tools. Подробная спецификация — `.ai-factory/DESCRIPTION.md`.

## Технологический стек

- **Язык программирования:** Java 21 (`javac --release 21`)
- **Платформа:** IntelliJ Platform SDK / OpenIDE fork, `since-build="262"`
- **Система сборки:** `build.sh` (bash) — Maven/Gradle отсутствуют
- **БД / ORM:** нет
- **Тесты:** нет (проверка = сборка + ручная проверка в IDE)

## Структура проекта

```
openide-web-plugin/
├── src/povly/wfm/            # 8 Java-классов плагина: bundle provider, fileType-бины,
│                             #   editor highlighter, spellchecking, goto-handler
├── resources/
│   ├── META-INF/plugin.xml   # дескриптор плагина: extension points, <version>, since-build 262
│   ├── bundle/               # TextMate-бандл: info.plist + Syntaxes/ (9 грамматик, MIT)
│   └── colorScheme/          # 4 XML-схемы «Catppuccin <Flavor> + Web»
├── research/                 # материалы исследования (session2: web-tools; session4: vue) — ТОЛЬКО ЧТЕНИЕ
├── build.sh                  # сборка: validate → javac → jar → dist/web-frontend-mini.zip
├── manifest.mf               # манифест jar (генерируется build.sh)
├── out/                      # временный вывод javac (создаётся build.sh)
├── dist/                     # результат сборки (создаётся build.sh)
├── opencode.json             # MCP-конфиг OpenCode (github, filesystem)
├── .opencode/skills/         # скиллы агента (aif*, wfm-plugin-dev)
└── .ai-factory/              # контекст AI Factory (config.yaml, DESCRIPTION.md, ARCHITECTURE.md, rules/)
```

## Ключевые точки входа

| Файл | Назначение |
|------|-----------|
| `build.sh` | Единственная команда сборки/проверки компиляции (требует JDK 21+, `OPENIDE_HOME`) |
| `resources/META-INF/plugin.xml` | Реестр всех extension points + версия плагина (single source of truth) |
| `src/povly/wfm/WebFrontendBundleProvider.java` | Регистрация TextMate-бандла, список файлов `BUNDLE_FILES` |
| `src/povly/wfm/WfmEditorHighlighterProvider.java` | Путь подсветки редактора + whole-document re-lex fix |
| `.ai-factory/DESCRIPTION.md` | Спецификация проекта (стек, возможности, требования) |

## Документация

| Документ | Путь | Описание |
|----------|------|----------|
| README (EN, основной) | README.md | Возможности, установка, LSP-настройка, troubleshooting |
| README (RU) | README.ru.md | Русская версия, обновляется вместе с EN |
| Архитектура | .ai-factory/ARCHITECTURE.md | Архитектурный документ (паттерн, слои, правила зависимостей) |
| Правила проекта | .ai-factory/rules/base.md | Конвенции кода, обнаруженные из кодовой базы |

## AI-контекст файлы

| Файл | Назначение |
|------|-----------|
| AGENTS.md | Эта карта проекта |
| .ai-factory/DESCRIPTION.md | Спецификация: стек, возможности, нефункциональные требования |
| .ai-factory/ARCHITECTURE.md | Архитектурные правила и структура |
| .ai-factory/rules/base.md | Базовые конвенции кода |
| .ai-factory/config.yaml | Конфигурация AI Factory (языки: ru/ru; git: main, без веток) |
| .opencode/skills/wfm-plugin-dev/ | Скилл разработки плагина (extension points, quirks платформы, сборка) |

## Правила для агентов

- Разбивать составные shell-команды git на отдельные шаги:
  - Неправильно: `git checkout main && git pull`
  - Правильно: сначала `git checkout main`, затем `git pull origin main`
- Перед правкой Java/плагина читать скилл `wfm-plugin-dev` и Javadoc классов — там root-cause
  знания о quirks платформы
- Новая грамматика = файл в `resources/bundle/Syntaxes/` + запись в
  `WebFrontendBundleProvider.BUNDLE_FILES` (оба места)
- `research/` не изменять и не включать в сборку
- После изменений запускать `./build.sh` — это единственная проверка
