# D1: Матрица решения — окраска vue-script (сессия 4)

Дата: 18.10.2026 (вечер). Входы: findings-A1 (карта правил), findings-A2 (рут-коз +
харнесс), findings-C1/C2 (semantic-путь). Эмпирика F2: `pipeline-f2.txt` (бандл-копия
с `.embedded.`-контейнерами, тот же харнесс).

## 1. Архитектурные варианты плана — вердикты

| Вариант плана | Вердикт | Основание |
|---|---|---|
| (а) textmate-путь через штатный `customHighlightingColors` | ❌ ОТКЛОНЁН | A1 §1.4: это механизм бандлов с ЛИТЕРАЛЬНЫМИ цветами вне цветовой схемы (ломает тему юзера); Settings UI отсутствует |
| (а') свой scope→key мост в плагине | ❌ ОТКЛОНЁН | Не нужен: карта правил уже матчит script-токены (A2 стадия 2–3 здорова) — проблема была в лишнем слое, не в нехватке правил |
| (б) semantic-путь как основной | ❌ ОТКЛОНЁН | C1/C3: легенда LSP — только идентификаторы (7–13% токенов); keywords/строки/комментарии semantic не даёт принципиально |
| **(в) комбинация: textmate основной + semantic добавка** | ✅ ПРИНЯТО | Как у web-tools (S9). Semantic-слой уже включён и работает без кода (C2); чинить нужно textmate-слой |

## 2. Фикс-варианты рут-коза (слой DEFAULT_TEMPLATE_LANGUAGE_COLOR поверх семантики)

Рут-коз (A2): селектор `text source` (TextMateDefaultColorsProvider) матчит цепочку
`…source.* ← … ← text.html.vue` → ключ DEFAULT_TEMPLATE_LANGUAGE_COLOR идёт последним
(высший weigh) → `LayeredTextAttributes.merge` кладёт его FOREGROUND поверх
семантического цвета. Темы Catppuccin/Wfm задают ему бледный fg → вся секция script
одна краска.

### F1 — схемы: убрать FOREGROUND у DEFAULT_TEMPLATE_LANGUAGE_COLOR

- Правка: в 4× `resources/colorScheme/Wfm{Macchiato,Frappe,Latte,Mocha}.xml` заменить блок
  `<option name="DEFAULT_TEMPLATE_LANGUAGE_COLOR"><value><option name="FOREGROUND" …/></value></option>`
  на `<option name="DEFAULT_TEMPLATE_LANGUAGE_COLOR" baseAttributes="TEXT" />` (как Darcula).
- Эффект: слой остаётся в keys, но без собственного fg → merge ничего не перекрывает.
- Плюсы: одна правка на файл; выравнивание с платформенным Darcula.
- Минусы: лечит только НАШИ схемы; на оригинальных темах Catppuccin (юзер может их
  вернуть) монохром вернётся.

### F2 — грамматика: `.embedded.`-имена контейнеров script/style

- Правка: в `resources/bundle/Syntaxes/vue.tmLanguage.json` контекстные имена-контейнеры
  `source.ts` / `source.tsx` / `source.css` (те, что обёртывают `include` грамматики)
  переименовать в `source.ts.embedded.vue` / `source.tsx.embedded.vue` /
  `source.css.embedded.vue` (10 замен; `include`-строки НЕ трогать).
- Механизм: штатный `trimEmbeddedScope` платформы (A1 §3.2) отрезает `.embedded.`-имя
  и всех text-предков → селектор `text source` больше не матчится → слой не возникает.
  Это тот же механизм, которым стоковый HTML защищает inline-JS.
- **Доказано харнессом** (`pipeline-f2.txt`, бандл-копия /tmp/bundle-f2):
  script-токены → `TRIMMED`, `sel: [keyword]`, `keys: [DEFAULT_KEYWORD]` (было
  `[DEFAULT_KEYWORD, DEFAULT_TEMPLATE_LANGUAGE_COLOR]`); matched 518→408 — минус 110
  токенов, матчившихся ТОЛЬКО через `text source` (сам монохром-слой).
- Плюсы: работает с ЛЮБОЙ темой юзера; платформенно-нативно.
- Минусы/риски: меняет scope-цепочку (проверить: спелчек-стратегия, highlighter-стек,
  brace-matching — не зависят от имени контейнера; DIAG-счётчики косметичны).

### Рекомендация: **F1 + F2** (v1.9.0)

F2 — структурный фикс (любы темы), F1 — гигиена наших схем (не задавать жёсткий fg
template-цвета). Взаимно независимы, дешёвы, вместе дают защиту на обоих уровнях.

## 3. План D2 (после согласования)

1. `vue.tmLanguage.json`: 10 замен имён контейнеров (sed по `"name": "source.(ts|tsx|css)"`).
2. 4× `Wfm*.xml`: `DEFAULT_TEMPLATE_LANGUAGE_COLOR` → `baseAttributes="TEXT"`.
3. version → `1.9.0`; release-сборка — **БЕЗ WFM-DIAG активностей** (убрать
   `WfmLiveDiagStartup` + `<postStartupActivity>`; урок v1.5→v1.6, план D2).
4. `build.sh` (валидация XML) → установка юзером → визуальная проверка (D3).
5. Коммит в репо плагина (Commit Plan: осмысленные conventional-коммиты после D).

## 4. Открытые пункты для юзера

1. **Согласовать F1+F2** (или один вариант) → запускаю D2.
2. **A3 (DIAG3)**: v1.8.5-diag3 собран, но не установлен. Варианты: (i) поставить сейчас,
   забрать лог-факты (merged fg=f0c6c6 по предсказанию) → формально закрыть A3, затем v1.9.0;
   (ii) пропустить A3-раунд — рут-коз доказан харнессом+байткодом+F2-тестом, A3 закрыть
   по совокупности с пометкой; (iii) v1.9.0-diag (DIAG3 остаётся в диаг-сборке) — юзер
   одним взглядом видит и фикс, и лог.
3. **B1/B2 (web-tools baseline)**: юзер решает — делать живой замер или скипнуть
   (решение уже не влияет на D1: механика web-tools ясна из S9+C2).
