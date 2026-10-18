# A2: Харнесс полного конвейера — решающий эксперимент (сессия 4)

Дата: 18.10.2026 (вечер). Харнесс: `research/session4/PipelineRepro.java`
(запуск — ниже). Вход: бандл v1.8.3 (`resources/bundle`), реальные файлы jpbest:
`resources/js/pages/Echarts.vue` (601 токен) и `resources/js/lib/phoneMask.ts` (933 токена).
Лог: `pipeline-run1.txt`.

## TL;DR — РУТ-КОЗ найден и доказан по стадиям

Конвейер TextMate-краски для vue-script **полностью здоров до ключей включительно**.
«Монохром» создаёт **лишний верхний слой**: generic-селектор `text source`
(из `TextMateDefaultColorsProvider`, A1 §1.3) матчит ЛЮБУЮ цепочку вида
`…source.* ← … ← text.*` → добавляет ключ **DEFAULT_TEMPLATE_LANGUAGE_COLOR**,
который в темах Catppuccin (и наших Wfm*) имеет **собственный бледный FOREGROUND**
(macchiato `f0c6c6`) и при слиянии слоёв **перекрывает семантический цвет**
(keyword/string/comment…). Вся секция script красится одним цветом → «монохром».

RC9 переоткрыт и переобъяснён: `trimEmbeddedScope` ни при чём — в нашей грамматике
нет имён с ".embedded." (v1.8.3 переименование не могло помочь и не могло навредить).
Обрезка — штатной механизм платформы против ИМЕННО этого затемнения, но срабатывает
только когда вложенный контекст назван `…embedded…` (как в стоковом HTML:
`source.js.embedded.html`).

## 1. Методика (стадии воспроизведены на настоящих компонентах)

Харнесс повторяет `TextMateHighlighter.getTokenHighlights` построчно (байткод A1),
используя ЖИВЫЕ компоненты: реальный `TextMateHighlightingLexer`, реальный
`trimEmbeddedScope` (reflection на private static), реальный
`TextMateScopeComparatorCore.sortAndFilter` + реальный weigher, реальный
`TextMateTheme` (164 селектора). customHighlightingColors = ∅ (наш бандл не поставляет
preference-файлов с customHighlightingAttributes — проверено по layout бандла).

```bash
# сборка/запуск (как в session2/README):
CP="/usr/share/openide/plugins/textmate-plugin/lib/modules/*:/usr/share/openide/lib/*"
javac --release 21 -cp "$CP" -d /tmp/hr4 PipelineRepro.java
java -cp "/tmp/hr4:$CP" PipelineRepro ~/Projects/openide-web-plugin/resources/bundle \
     /var/www/jpbest/resources/js/lib/phoneMask.ts /var/www/jpbest/resources/js/pages/Echarts.vue
```

## 2. Diff .ts vs .vue по стадиям (Echarts.vue / phoneMask.ts)

| Стадия | .ts | .vue script | Вердикт |
|---|---|---|---|
| 0. Токены/скоупы | 933 токена, цепочки вида `keyword.control.export.ts ← meta.var.expr.ts ← source.ts` | 601 токен, `keyword.control.import.ts ← … ← source.ts ← … ← text.html.vue` | ✅ одинаково здоровы |
| 1. trimEmbeddedScope | не меняет цепочку | **не меняет цепочку** (нет ".embedded." имён) | ✅ обрезка не фактор |
| 2. sortAndFilter (164 селектора, реальный weigher) | матчится: `keyword`, `string`, `comment`,`comment.block`, `storage`,`storage.type`, … | матчится ТЕ ЖЕ + **`text source`** (из-за `source.ts ← text.html.vue` в цепочке) | ✅ матчинг здоров |
| 3. Ключи (reversed = worst→best) | `[DEFAULT_KEYWORD]`, `[DEFAULT_STRING]`, `[DEFAULT_LINE_COMMENT, DEFAULT_BLOCK_COMMENT]`… | те же + **`DEFAULT_TEMPLATE_LANGUAGE_COLOR` ВСЕГДА ПОСЛЕДНИЙ** (высший weigh): `[DEFAULT_KEYWORD, DEFAULT_TEMPLATE_LANGUAGE_COLOR]`, `[DEFAULT_STRING, DEFAULT_TEMPLATE_LANGUAGE_COLOR]`… | ⚠️ лишний слой |
| 4. Ключ→схема | все ключи определены в WfmMacchiato | все ключи определены, НО `DEFAULT_TEMPLATE_LANGUAGE_COLOR` в WfmMacchiato = **FOREGROUND f0c6c6** (унаследован из catppuccin-xml; Darcula же — `baseAttributes="TEXT"`, без fg) | ❌ бомба |
| 5. Слияние слоёв | — | `LexerEditorHighlighter.convertAttributes` → `LayeredTextAttributes.create(scheme, keys)` = цикл `TextAttributes.merge(acc, scheme.getAttributes(key))` по массиву — **поздние слои переопределяют foreground** (байткод leh.txt/lta.txt) | ❌ fg `f0c6c6` кладётся поверх семантического цвета |

Примеры из лога (vue script): токен `import` → sel `[keyword, text source]` → keys
`[DEFAULT_KEYWORD, DEFAULT_TEMPLATE_LANGUAGE_COLOR]`; токен `'@inertiajs/vue3'` →
`[DEFAULT_STRING, DEFAULT_TEMPLATE_LANGUAGE_COLOR]`. У .ts таких пар нет никогда —
нет `text.*`-предка → селектор `text source` не матчится → .ts цветной. ✓ совпадает с S1.

## 3. Почему это объясняет ВСЮ историю симптомов

| Факт истории | Объяснение |
|---|---|
| Монохром vue-script «с самого начала» (ещё до наших схем, на теме Catppuccin Macchiato) | Оригинальные catppuccin-темы тоже задают fg у DEFAULT_TEMPLATE_LANGUAGE_COLOR (frappe `eebebe`, latte `dd7878`… — проверено grep'ом catppuccin/themes/*.xml) |
| .ts/.css/.js файлы цветные | корневой скоуп `source.*` без `text.*`-предка → `text source` не матчится → слоя нет |
| template-часть .vue цветная | у токенов template нет `source.*`-потомка → `text source` не матчится |
| v1.8.3 (убрать «embedded» из имён) не помог | обрезка никогда не срабатывала: дело не в trimEmbeddedScope, а в живом предке `text.html.vue` |
| web-tools красит script semantic-токенами (S9) | их путь не textmate для .vue — слой не возникает; и их схемы Frontend/Web — без «вредного» fg? (проверяется в B1) |
| DIAG2: токены в живом редакторе ЕСТЬ (1499/1510 source-scoped) | стадия 0 подтверждена независимо — совпадает с харнессом |

## 4. Предсказания для A3 (DIAG3, фальсифицируемые)

1. Резолвнутый foreground ВСЕХ замеряемых script-токенов .vue под темой
   `_@user_Catppuccin Macchiato + Web` = **f0c6c6** (или близкий — если юзер переопределял).
2. Токены template-части .vue имеют «нормальные» разные foreground'ы.
3. Секция `<style>` в .vue **тоже монохромна** (`source.css ← text.html.vue` → тот же
   `text source` слой; харнесс: source.css-агрегат 26/26 keyed). Проверить взглядом/логом.

## 5. Кандидаты фикса для D1 (обоим — одна строчка, без кода плагина)

| Вариант | Суть | Плюсы/минусы |
|---|---|---|
| **F1 (схема)** | В Wfm*.xml убрать FOREGROUND у DEFAULT_TEMPLATE_LANGUAGE_COLOR (→ `baseAttributes="TEXT"` или пустой value) | + тривиально, чинит ВСЕ textmate-вложения сразу; + семантический слой остаётся; − правится только наша схема (юзер на `_@user_`-копии — надо обновить копию/переустановить схему); − не спасёт, если юзер вернётся на catppuccin-оригинал |
| **F2 (грамматика)** | В vue.tmLanguage.json обозвать контейнер script-контекста с ".embedded." (напр. `source.ts.embedded.vue` между `text.html.vue` и `source.ts`) | + ШТАТНЫЙ механизм платформы (trimEmbeddedScope отрежет text-предков → `text source` не сматчится); + работает с ЛЮБОЙ темой юзера; − меняет скоуп-цепочку (наши scope-ключи/спелчек — проверить); − тоньше в отладке |
| F1+F2 | обе меры | максимум надёжности |

Механика F2 проверена харнессом: обрезка срабатывает на первом имени, содержащем
".embedded.", сохраняя всё нижележащее (A1 §3.2) — семантические селекторы (keyword/string)
остаются, `text source` исчезает.

## 6. Чек-лист задачи A2

- [x] Java-харнесс на настоящих компонентах: лексинг → getTokenHighlights-эквивалент → ключи
- [x] оба файла из jpbest (.ts и .vue), сравнение по стадиям (§2)
- [x] ТОЧНАЯ ступень потери цвета: стадия 4–5 (ключ→attrs merge), не 0–3
- [x] зонды vue_probe*.py сохранены: `research/session4/probes/` (6 файлов)
- [x] findings-A2-pipeline.md
