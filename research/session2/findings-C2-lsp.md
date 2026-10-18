# C2: LSP-механика «полных подсказок» web-tools (18.10.2026, фаза C)

Источники: `wtjar/META-INF/plugin.xml` (строки 540–594), байткод lsp4ij 0.19.4.6
(`research/session2/lsp/`), байткод web-tools.

## 1. Как web-tools объявляет LSP-стек — только EP lsp4ij, ноль юзер-конфига

```xml
<extensions defaultExtensionNs="com.redhat.devtools.lsp4ij">
  <server id="…vuels.VueLanguageServer" name="Vue Language Server"
          factoryClass="…vuels.VueLanguageServerFactory" />
  … (9 серверов: vue, ts, tsgo, css, angular, eslint, stylelint, prettier, tailwind)
  <fileNamePatternMapping patterns="*.ts"  serverId="…tls.TSLanguageServer"  languageId="typescript" />
  <fileNamePatternMapping patterns="*.vue" serverId="…vuels.VueLanguageServer" languageId="vue" />
  <fileNamePatternMapping patterns="*.*"   serverId="…prettierls.PrettierLanguageServer"
                          languageId="javascript"
                          documentMatcher="…PrettierDocumentMatcher" />
  … (30+ маппингов)
</extensions>
```

Доступные EP lsp4ij (plugin.xml lsp4ij): `server`, `languageMapping` (Language→server),
`fileTypeMapping`, **`fileNamePatternMapping`** (pattern→server+languageId, optional
documentMatcher), `debugAdapterServer`.

## 2. Как languageId доезжает до didOpen (байткод, проверено)

`LanguageServerDefinition.getLanguageId(Language, FileType, fileName)` — порядок резолва:

```
1. languageIdLanguageMappings.get(Language)   ← languageMapping EP
2. languageIdFileTypeMappings.get(FileType)   ← fileTypeMapping EP
3. languageIdFileNamePatterns(fileName)       ← fileNamePatternMapping EP  ← web-tools тут
4. фолбэк: Language.getID()                   ← у юзера = "textmate" (S9) → деградация
```

`DocumentContentSynchronizer` получает готовый languageId в конструкторе → didOpen.
**Маппинг побеждает язык файла** — потому у web-tools .vue уходит как `vue`, .ts как
`typescript`, независимо от зарегистрированного fileType.

## 3. Тонкости архитектуры web-tools

- **Несколько серверов на файл — норма**: на .ts висят TLS + ESLint + Tailwind (+Prettier
  по матчера); на .vue — Vue LS + TLS + Tailwind.
- **Vue v3 hybrid**: TLS на *.vue — «тихий бэкенд» (комментарий в их plugin.xml:
  `TSLSPClientFeatures.isEnabled → v3 only, user-facing features suppressed for .vue`).
  Фабрики кастомизируются через `createClientFeatures()` (LSPClientFeatures).
- **documentMatcher**: PrettierDocumentMatcher → делегат GlobPatternDocumentMatcher,
  паттерны из WebToolsSettings$State (настраиваемые); гейтит «*.*»-маппинг.
- Фабрика = `createConnectionProvider` (запуск процесса) + `createLanguageClient` +
  `createClientFeatures` (+ LanguageServerEnablementSupport — вкл/выкл per-project).
- initOpts у web-tools живут в фабриках/клиентах (наши текущие initOpts — в per-project
  `.idea/LanguageServersSettings.xml`, S8 сессии 1 — не трогать).

## 4. Минимальный набор паритета подсказок для НАС

Проблема (S9): у юзера 6 user-defined серверов, маппинги в
UserDefinedLanguageServerSettings.xml кривые (TS ← languageId=JavaScript даже для *.ts;
Vue ← languageId нет → фолбэк «textmate»).

Два пути (развилка из плана D2-в):

- **(а) Правка юзер-конфига** (IDE закрыта, ~3 строки в XML): для TS-сервера маппинги
  по fileNamePatterns с languageId=typescript/javascript/jsx/tsx; для Vue-сервера
  languageId=vue. Плюс: не нужен код в плагине. Минус: юзер-конфиг, слетит при ручных
  правках в GUI lsp4ij; не переносится другим пользователям плагина.
- **(б) EP в нашем плагине по образцу web-tools**: свои `<server>`-дескрипторы
  (factoryClass = запуск тех же бинарей) + `<fileNamePatternMapping>` с корректными
  languageId. Плюс: самодостаточно, IDE-wide. Минус: **дублирование серверов** —
  юзер-конфиг уже запускает эти же серверы → два инстанса на файл; нужен переходный
  план (отключить юзер-дефиниции или не shipping'ать серверы, а только mappings —
  проверить, резолвится ли serverId из fileNamePatternMapping против user-defined
  дефиниций в LanguageServersRegistry; если да — минимальный вариант: mappings-only).
- Открытый пункт на проверку в D2: registry матчинг serverId по user-defined
  дефинициям (S10: правильный оверлоад `findLanguageServerDefinitionFor(Language,
  FileType, fileName)`).

## 5. Что взять в D1-матрицу из C2

| Фича web-tools | Наш статус |
|---|---|
| fileNamePatternMapping ×30 (vue/ts/js/tsx/css/scss/less/html) | ❌ нет (язык файла = textmate) |
| 9 бандл-серверов (148M node_modules) | — не цель (юзер уже имеет свои) |
| Prettier on-save + documentMatcher | ⚠️ юзер предпочёл Ctrl+Alt+F (сессия 1, S9) |
| Vue hybrid: TS как тихий бэкенд .vue | ❌ нет (но юзер-стек Volar уже решает S8-сессии-1) |
| LSP semantic tokens + цвета в схеме (LSP_*) | ❌ нет ни токенов, ни ключей (см. C1) |
