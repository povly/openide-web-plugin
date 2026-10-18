# C1: semanticTokens vue-language-server (Volar 2.2.12 classic) — сессия 4

Дата: 18.10.2026 (вечер). Зонд: `probes/vue_probe6_semantic.py`
(продолжение vue_probe5; полный вывод — `probes/probe6-out.txt`).

```bash
python3 probes/vue_probe6_semantic.py [file1.vue file2.vue ...]
# сервер: ~/.lsp4ij/lsp/vue-js-language-server-ls/node_modules/.bin/vue-language-server
# инициализация как в IDE: vue.hybridMode=false + tsdk из node_modules/typescript/lib
```

## 1. Зарегистрированная capability (verbatim)

```json
{"range": true, "full": false,
 "legend": {"tokenTypes": ["namespace","class","enum","interface","typeParameter",
                            "type","parameter","variable","property","enumMember",
                            "function","method"],
            "tokenModifiers": ["declaration","readonly","static","async","defaultLibrary","local"]}}
```

- Факт S7 подтверждён: **`full=false`** в регистрации. Но эмпирически сервер ОТВЕЧАЕТ и на
  `textDocument/semanticTokens/full` (44/79 токенов, с `resultId`, latency **0.01s**).
- `semanticTokens/full/delta` — **без ответа** (запрос повисает): дельта не поддерживается,
  потребитель обязан звать full (или range — range=true зарегистрирован).

## 2. Легенда — ТОЛЬКО идентификаторы

12 tokenTypes, все — идентификаторные (namespace/class/enum/interface/typeParameter/type/
parameter/variable/property/enumMember/function/method). **Ни keyword, ни string, ни comment,
ни number в легенде НЕТ** — семантический слой принципиально не красит синтаксис
(это не ограничение Volar, а сама модель LSP semanticTokens).

## 3. Покрытие (2 файла jpbest)

| Файл | semantic-токенов | textmate-токенов (A2) | Типы | Регионы |
|---|---|---|---|---|
| Echarts.vue | 44 | 601 | property 20, variable 12, class 9, function 3 | script И template (template: class 8, property 6, variable 4) |
| Login.vue | 79 | — | property 60, variable 6, method 5, function 4, class 3, type 1 | script И template (template: property 42!) |

- Semantic-слой покрывает ~7–13% токенов файла — только идентификаторы, зато в ОБЕИХ
  частях SFC (в template красит компоненты/пропсы — то, чего textmate не знает).
- Latency full-запроса ~10 мс (после прогрева didOpen) — дёшево.

## 4. Вывод для C3/D1

1. **Semantic-путь НЕ может быть основным** для окраски script: keywords/строки/комментарии
   он не даёт в принципе → textmate-грамматика остаётся обязательной (наш текущий слой).
2. Semantic-путь — ценная ДОБАВКА (идентификаторы в script+template, как web-tools:
   их путь S9 = LSP semantic tokens + LSP_\*-ключи в схеме).
3. Условия включения в lsp4ij (gate по capability? обязательность `full`?) — вопрос C2:
   регистрация `full=false` у Volar может помешать lsp4ij вообще запрашивать токены.
