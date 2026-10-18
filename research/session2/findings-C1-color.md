# C1: Цветовой секрет web-tools (18.10.2026, фаза C)

Вопросы из плана → ответы. Все выводы по wtjar/ (web-tools 1.0.1), ideimpl/
(байткод платформы), catppuccin/ (тема-источник), наш репо v1.7.0.

## 1. Содержат ли WebToolsDefault*.xml DEFAULT_*? — ДА, полностью

- `WebToolsDefaultDark.xml` (1081 строка): `<scheme name="Frontend/Web Dark" version="142" parent_scheme="Darcula">`
  — полный набор DEFAULT_* (KEYWORD/STRING/NUMBER/COMMENT/…), все editor-цвета (colors-секция),
  плюс **CSS.\***, **SASS/LESS**, **HTML/XML**, **JS.\*** и — важно — **LSP_\* semantic-token ключи**
  (LSP_CLASS, LSP_FUNCTION, LSP_METHOD, LSP_VARIABLE, LSP_PROPERTY… → lsp4ij semantic highlighting
  получает цвета прямо в схеме).
- `WebToolsDefaultLight.xml` — симметрично (32 DEFAULT_* упоминания + то же).
- Вывод: их textmate-fallback никогда не монохром — все ключи на месте; их LSP-подсветка
  семантики имеет цвета в той же схеме.

## 2. Как bundledColorScheme попадает в список (механизм платформы)

Цепочка (байткод EditorColorsManagerImpl / EditorColorsManagerImplKt):

```
EditorColorsManagerImpl.<init>
  → createLoadBundledSchemeRequests(providersMap, checkId, nameResolver)   // корутина по EP
  → SchemeManager.loadBundledSchemes(requests)                             // per-scheme load
  → loadSchemes() → список схем
```

- EP `bundledColorScheme` (bean `com.intellij.configurationStore.BundledSchemeEP`), dynamic=true.
- На каждый EP строится `LoadBundleSchemeRequest`: `getSchemeKey()` = `LazyExtension.getId()`,
  при null — WARN + фолбэк-ключ из path (strip `/` и `.xml`; DISASSAMBLY: ldc `/` removePrefix,
  `.xml` endsWith, makeConcatWithConstants).
- Загрузка одного запроса (`createBundledEditorColorScheme`):
  `JDOMUtil.load(bytes) → BundledEditorColorScheme.readExternal(element) → resolveParent(nameResolver)`
  → parent-имя ищется среди УЖЕ загруженных схем.
- **id НЕ обязателен**: web-tools регистрирует `path`-only — и работает. Catppuccin-плагин — с `id`.
  Неявный фильтр: `/themes/islands/highContrastScheme.xml` скипается в new UI.

## 3. Почему наши «+ Web» не появились (S8) — ДВА ДЕФЕКТА у нас

### Дефект 1 (главный): XML наших схем ПОВРЕЖДЁН

Все 4 `resources/colorScheme/Wfm*.xml`: каждый атрибут обёрнут `<value>` ДВАЖДЫ:

```xml
<!-- наши (битое) -->
<option name="DEFAULT_ATTRIBUTE">
    <value>
<value>                                    <!-- ← лишняя обёртка -->
        <option name="FOREGROUND" value="e5c890"/>
      </value>
    </value>
</option>

<!-- web-tools (эталон) -->
<option name="CSS.IMPORTANT">
    <value>
        <option name="FOREGROUND" value="cf8e6d"/>
        <option name="FONT_TYPE" value="1"/>
    </value>
</option>
```

Факт: 50 `<value>` на 25 атрибутов в каждом файле — двойная обёртка ВСЕЮДУ (генератор сессии 2
вкладывал готовый блок ещё раз). Нестандартный элемент внутри `<value>` → readExternal не
десериализует атрибуты (или валит схему целиком) — в любом случае схема не может загрузиться
корректно.

### Дефект 2: хрупкий parent_scheme

- У нас: `parent_scheme="Catppuccin Frappé"` (и др. flavor'ы) — схема ДРУГОГО плагина;
  resolveParent ищет среди уже загруженных → зависит от порядка загрузки плагинов
  (Catppuccin Theme должен загрузиться раньше нас). «é» в имени само по себе не при чём
  (XML unicode-безопасен) — при чём порядок.
- У web-tools: `parent_scheme="Darcula"` — платформенная схема, загружена всегда и первой.
  Полностью self-contained.

## 4. Нужен ли colorSettingsPage для появления? — НЕТ

`<colorSettingsPage>` — вкладка/страница в Settings → Editor → Color Scheme (дерево страниц
настроек), НЕ список схем. web-tools регистрирует `WebToolsLspColorSettingsPage` — страница
настроек ЦВЕТОВ LSP-токенов (semantic). Схемы попадают в список только через
`bundledColorScheme`.

## 5. Что делают «Promo»/NotificationProvider (вопрос из плана)

Класса «EditorHighlightingPromoService» в web-tools НЕТ. Реальные:
`licensing/BaseProNotificationProvider` (+ VueProNotificationProvider, AngularProNotificationProvider,
editorNotificationProvider в plugin.xml): `isApplicableFile(project, vf)` →
`hasActiveLicense() ? … : EditorNotificationPanel` c `isDismissed`-механикой (PropertiesComponent).
Это лицензионные баннеры над файлом, к подсветке/схемам отношения НЕ имеют.

## Вывод для D2 (фиксы)

1. Перегенерировать Wfm*.xml с правильной ОДИНАРНОЙ обёрткой `<value>` (источник значений —
   `research/session2/catppuccin/themes/<flavor>.xml`, MIT; там же взять colors-секцию).
2. Добавить в наши схемы LSP_\*-ключи (паритет semantic-подсветки lsp4ij) — по образцу
   WebToolsDefaultDark (строки 968–1079).
3. parent_scheme: надёжный вариант — `Darcula` + полные цвета из catppuccin-xml (self-contained,
   как web-tools); быстрый вариант — оставить Catppuccin-родителя и починить только XML.
   Решение — в D1/D2 с юзером.
4. Зарегистрировать (опционально) `id` в bundledColorScheme — убирает WARN в логе.
