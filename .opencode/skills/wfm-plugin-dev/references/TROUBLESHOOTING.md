# Troubleshooting: симптом → причина → решение

Полная таблица из README.md / README.ru.md (v1.9.1). Сначала определить СЛОЙ:
подсветка/схемы/спелчек/фолдинг — плагин; подсказки/hover/переходы по коду —
LSP4IJ + серверы.

## Слой плагина (подсветка)

| Симптом | Причина | Решение |
|---|---|---|
| Весь веб-стек монохромный | Выбран не тот вариант темы (Catppuccin шлёт `.theme.json`-вариант без базовых цветов — 0 из 27 DEFAULT_\* ключей) | Выбрать схему «Catppuccin \<Flavor\> **+ Web**» (или Darcula) |
| В `.vue` красится template, а `<script>` одним цветом | Версия плагина < 1.9.0 (нет embedded-scope фикса F1+F2) | Обновиться до 1.9.0+ |
| «Пакет web-frontend-mini имеет неизвестный формат» | Нарушен layout бандла (актуально при правке `resources/bundle/`) | Формат строго `info.plist` + `Syntaxes/*.tmLanguage[.json]` в корне бандла |
| Схемы не грузятся / не появляются в списке | Невалидная структура атрибутов XML схемы (урок v1.7: duplicated value-wrapper) | Проверить `<option>/<value>` структуру, `./build.sh`, переустановка |
| jsx/tsx-вставки внутри `.js`/`.ts` обесцвечены | Upstream-ограничение textmate-движка (embedded-скопы) | Настройкой не лечится; при необходимости — тот же приём, что в vue 1.9.0 (`.embedded.`-контейнеры) |
| После правки грамматики в IDE ничего не изменилось | Старый бандл в кэше / zip не переустановлен | Пересобрать + переустановить zip; извлечение в кэш идемпотентно на каждый boot, но running-сессия держит старые грамматики до рестарта |

## Слой LSP4IJ (подсказки и переходы; настраивает юзер, не плагин)

| Симптом | Причина | Решение |
|---|---|---|
| Vue-сервер «запускается, но тишина»: подсказок нет, LSP-консоль пустая, процесс жив | Initialization Options не дошли до сервера (правились файлом / поле в GUI пустое). Volar без `options.typescript` отвергает initialize и остаётся «зомби» | Вставить Initialization Options **заново через GUI** → перезапустить IDE |
| В `.vue` пустые hover и переходы при живых подсказках в template | Volar по умолчанию в hybrid-режиме: TS-фичи отданы внешнему tsserver, которого вне VS Code нет | `"vue":{"hybridMode":false}` в Initialization Options |
| Красная ошибка `Cannot read properties of null (reading 'validProperties')` в css | CSS-сервер не получил Configuration (секции css/scss/less) | Вставить Configuration JSON в GUI-вкладку CSS-сервера → перезапуск |
| Подсказки в `.ts`/`.js` неполные или сервер не цепляется к файлам | В маппинге нет `languageId` — сервер получает язык файла (`textmate`) вместо `typescript` | В Mapping каждого сервера указать паттерны **и** languageId |

Золотое правило LSP4IJ: **конфиги заполнять только через GUI и перезапускать IDE после
правок** — правка XML настроек «на живую» не подхватывается, сервер молча не получает
опции. Обязательные JSON-опции серверов — в README (раздел «Настройка подсказок и
переходов (LSP)»): Vue → Initialization Options (`typescript.tsdk`,
`vue.hybridMode:false`); CSS → Configuration (`css/scss/less.lint.unknownProperties:"ignore"`,
`validProperties:[]`).

## Дебаг-инструменты проекта

- Лог плагина: `grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log`.
- Харнессы лексинга/краски вне IDE (CP как в build.sh):
  `research/session2/Repro.java` (непрерывный лексинг),
  `LinesRepro.java` (построчный, режим редактора),
  `RegexAudit.java` (joni-аудит regex'ов);
  `research/session4/PipelineRepro.java` (полный конвейер краски с реальными
  компонентами — им доказан RC10 и проверен фикс F2). Запуск — в README session2/session4.
- Диагностические startup-активности (WFM-DIAG) существовали только в diag-сборках;
  в релизные не включать (урок v1.5→v1.6).
