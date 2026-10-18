# C2: lsp4ij semantic-интеграция по байткоду (сессия 4) + C3-вердикт

Дата: 18.10.2026 (вечер). Метод: javap по `~/.local/share/OpenIDE/OpenIDE2026.2/lsp4ij/lib/lsp4ij-0.19.4.6.jar`.
Дампы: `research/session4/lsp4ij-{support,feature,capreg,tdscr,colors,default-provider,visitor,data,simplelang,lsi,utils}.txt`.

## TL;DR

**Semantic-путь ВКЛЮЧАЕМ БЕЗ КОДА ПЛАГИНА.** Вся цепочка (visitor → гейты → запрос → покраска)
работает для user-defined серверов и textmate-файлов: гейт по capability проверяет только
`semanticTokensProvider != null` (НЕ `full`!), lsp4ij всегда шлёт `semanticTokens/full`
(который Volar реально обслуживает — доказано C1), а textmate-языки получают ПРЯМОЙ путь
покраски, минуя PSI-обход. Ключи LSP_\* имеют фолбэки на DEFAULT_\* — наша схема их уже красит.
Semantic = ДОПОЛНЕНИЕ (идентификаторы), не замена textmate-синтаксису.

## 1. Архитектура конвейера (по байткоду)

```
LSPSemanticTokensHighlightVisitor (daemon HighlightVisitor)
  suitableForFile(file) = LanguageServersRegistry.isFileSupported(file)     ← только маппинг серверов, БЕЗ языкового ценза
  analyze(file…):
    ProjectIndexingManager.canExecuteLSPFeature(file) == NOW
    → LSPFileSupport.getSemanticTokensSupport().getSemanticTokens(params)
      → getLanguageServers(file, isEnabled, isSupported):                   ← первый сматчившийся сервер
          isEnabled    = LSPSemanticTokensFeature.isEnabled(file)
          isSupported  = SemanticTokensCapabilityRegistry.isSemanticTokensSupported(file)
      → TextDocumentService.semanticTokensFull(params)                      ← ВСЕГДА full, ни delta, ни range
    → SemanticTokensData(tokens, legend, provider=clientFeature, feature)
    → если data.shouldVisitPsiElement(file) == FALSE → highlightSemanticTokens(СРАЗУ, весь файл)   ← наш случай (textmate)
      если TRUE  → лениво через visit(element) по LeafElement              ← случай «настоящих» PSI-языков
```

## 2. Ответы на вопросы задачи

### 2.1 Условия запроса: capability `full` НЕ обязательна

`SemanticTokensCapabilityRegistry.lambda$static$0`: предикат = **`serverCapabilities.getSemanticTokensProvider() != null`**.
Плюс documentSelector-матчинг файла (`ExtendedDocumentFilter.language/scheme/pattern`, TextDocumentServerCapabilityRegistry).
Флаги `full`/`range`/`delta` из регистрации НЕ проверяются. Volar регистрирует провайдера
(full=false, range=true — C1) → гейт ПРОЙДЕН. Запрос всегда `semanticTokens/full` — Volar
его обслуживает (C1: 44/79 токенов, 0.01 c, resultId есть; delta виснет — но её и не шлют).

### 2.2 textmate-файлы: ПРЯМОЙ путь покраски (не блокировка!)

- `SimpleLanguageUtils.isSupported(lang)` = `set.contains(lang)`, где set = {`PlainTextLanguage.INSTANCE`,
  `Language.findLanguageByID("textmate")`}.
- Наш `WfmVueFileType.baseLanguage()` = тот же `Language.findLanguageByID("textmate")` →
  `LSPSemanticTokensFeature.shouldVisitPsiElement(file) = !isSupported = FALSE`.
- В `analyze` (visitor, см. схему §1): `shouldVisitPsiElement == FALSE` → **немедленный**
  `highlightSemanticTokens(file, result, holder)` — покраска всего файла без PSI-обхода.
  Инверсия не баг: textmate-PSI бессмысленно обходить, поэтому красят напрямую;
  «настоящие» языки идут лениво по LeafElement (`isEligibleForSemanticHighlighting`).
- Реализация покраски: `SemanticTokensData.highlight(file, document, consumer)` —
  использует `LSPSemanticTokensFileViewProvider.getInstance` + inspector.

### 2.3 Мапинг tokenType → ключи

- Провайдер по умолчанию: `LSPSemanticTokensFeature implements SemanticTokensColorsProvider`
  (резолв через `LanguageServerItem.getSemanticTokensColorsProvider()` = clientFeatures) →
  логика в `DefaultSemanticTokensColorsProvider.getTextAttributesKey(tokenType, modifiers, file)`.
- Switch покрывает **все 12 типов легенды Volar** (namespace/class/enum/interface/struct/
  typeParameter/type/parameter/variable/property/enumMember/decorator/event/function/method/
  macro/comment/string/keyword/number/regexp/modifier/operator — полный набор LSP-спек).
- Модификаторы: `declaration|definition` → `*_DECLARATION`; `static`/`readonly` (комбинации) →
  `STATIC_*`/`READONLY_*`/`STATIC_READONLY_*`; `defaultLibrary` → `DEFAULT_LIBRARY_FUNCTION`.
- Ключи (36 констант `SemanticTokensHighlightingColors`): `createTextAttributesKey("LSP_XXX",
  DefaultLanguageHighlighterColors.YYY)` — фолбэк на DEFAULT_\*: LSP_CLASS→CLASS_REFERENCE,
  LSP_INTERFACE→INTERFACE_NAME, LSP_TYPE_PARAMETER→PARAMETER, LSP_VARIABLE→REASSIGNED_LOCAL_VARIABLE,
  LSP_PROPERTY→INSTANCE_FIELD, LSP_FUNCTION/LSP_METHOD→FUNCTION_CALL,
  LSP_FUNCTION_DECLARATION→FUNCTION_DECLARATION, LSP_ENUM_MEMBER→STATIC_FIELD, …

### 2.4 Схемы: цвета уже есть

`colorSchemes/SemanticTokens{Dark,Light,HighContrast,IntelliJLight}.xml` — аддон-фрагменты
(`<list>`), реальный оверрайд один: `LSP_TYPE_PARAMETER` (16BAAC в Dark). Остальные LSP_\*-ключи
берут цвета из DEFAULT_\*-фолбэков → **наши Wfm-схемы (25+ DEFAULT_\*) уже красят semantic-токены**.
Кастомные LSP_\*-оверрайды в схеме — опциональная косметика (так делают web-tools: их 21 LSP_\*-ключ, S9).

### 2.5 User-defined серверы

`isEnabled/isSupported/getLegend` живут в clientFeatures дефолтной реализации → работают для
ВСЕХ серверов, включая user-defined (наш vue-language-server, маппинг vue — сессия 3).
Кастомизация мапинга per-server-definition — через EP `SemanticTokensColorsProviderExtensionPointBean`
(плагинный; нам не нужно).

## 3. C3: Вердикт покрытия semantic-слоя

| Критерий | Оценка |
|---|---|
| Идентификаторы script (переменные/пропсы/функции/классы) | ✅ да (property/variable/class/function/method — C1: 44–79 токенов на файл) |
| Идентификаторы template (компоненты, пропсы) | ✅ да (C1: template-регион покрыт — property 42 в Login.vue) |
| keywords / строки / комментарии / числа | ❌ принципиально (в легенде LSP их нет — модель semanticTokens) |
| Доля покрытия | ~7–13% токенов файла (C1 vs A2: 44/601, 79/~600) |
| Включение без кода | ✅ доказано (§2.1–2.2: гейты проходимы, путь прямой) |
| Зависимость от textmate-фикса | независима, но БЕЗ textmate-фикса файл всё равно выглядит битым: 87–93% токенов (keyword/string/comment) остаются под слоем DEFAULT_TEMPLATE_LANGUAGE_COLOR (рут-коз A2) |

**Архитектурный вывод для D1**: правильная комбинация — та же, что у web-tools (S9):
**textmate как основной слой синтаксиса (починить F1/F2 из findings-A2) + semantic как
дополнение по идентификаторам (уже включён, работает сам)**. Semantic-путь НЕ требует
реализации и НЕ закрывает проблему монохрома сам по себе.

## 4. Чек-лист задачи C2 (+C3)

- [x] условия запроса semanticTokens — capability `full` НЕ обязательна, только provider≠null + documentSelector
- [x] мапинг tokenType→ключи — DefaultSemanticTokensColorsProvider + 36 LSP_\* с DEFAULT_\*-фолбэками
- [x] работает ли для user-defined серверов — да (дефолтные clientFeatures)
- [x] что нужно добавить — НИЧЕГО; вывод «включаемо без кода плагина» = ДА
- [x] C3-вердикт покрытия — §3 (semantic = добавка, не замена)
