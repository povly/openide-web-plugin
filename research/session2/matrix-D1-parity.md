# D1: Матрица паритета web-tools ↔ web-frontend-mini (18.10.2026)

Вход: findings C1–C4, baseline-ours (A2). Приоритет — согласуется с юзером.

| # | Фича | web-tools 1.0.1 | наш v1.7.0 | Гэп | Фикс | Приоритет |
|---|---|---|---|---|---|---|
| 1 | Схемы в списке Color Scheme | Frontend/Web Dark/Light; валидный XML; parent Darcula | 4× «Catppuccin + Web»: **XML битый** (двойной `<value>`), parent — схема др. плагина | схемы не появляются (S8) | перегенерация Wfm*.xml: одинарный `<value>`, значения из catppuccin-xml (MIT), `id` в EP | **P1** |
| 2 | parent_scheme | Darcula (self-contained) | «Catppuccin Frappé» (load-order хрупко) | ненадёжный резолв | развилка: Darcula+полные цвета vs Catppuccin-родитель | **P1, спросить** |
| 3 | ЦВЕТА textmate-fallback | полный DEFAULT_* | 25 ключей (сейчас битые из-за №1) | — | покрывается №1 | P1 (включено) |
| 4 | Цвета LSP semantic tokens | LSP_*-ключи в схеме + своя Settings-страница | нет LSP_* | Volar semantic красится неполно | добавить LSP_*-палитру в наши 4 схемы (копия формата web-tools, значения catppuccin) | **P1** (дёшево, большой эффект) |
| 5 | languageId для LSP (.ts/.vue/.css) | 32 fileNamePatternMapping + 9 серверов | юзер-конфиг lsp4ij кривой (S9) | деградация подсказок | развилка: (а) правка UserDefinedLanguageServerSettings.xml / (б) EP-mappings+server в плагине | **P1/P2, спросить** |
| 6 | Подсветка css/js/ts/vue (textmate) | свои fileTypes+грамматики (старые) | наши грамматики новее (vscode+Volar, патчи S4/S5) | **мы впереди** (S12, C3) | — | — |
| 7 | Prettier on-save | actionOnSave + documentMatcher | нет | закрыто юзером (Ctrl+Alt+F, сессия 1) | — | нет |
| 8 | spellchecker.support (textmate) | 1 EP | нет | мелкий UX | 1 строка в plugin.xml | P3 опция |
| 9 | emmet/zenCoding (xml) | 2 EP | нет | юзер не просил | — | нет |
| 10 | ESLint/StyleLint серверы | есть | нет (юзер-стек решает) | вне текущей боли | — | нет |
| 11 | Run/Debug (DAP Node), тесты, генераторы, Angular/Nest/Playwright/TestCafe | ~40 EP | нет | вне scope | — | нет |
| 12 | Лицензирование | has.license, Pro-баннеры, updater-dep | принципиально нет | clean-room требование | — | никогда |

## Развилки для юзера (D1-согласование)

1. **№2 parent_scheme**: (recommended) `Darcula` + полные цвета из catppuccin-xml —
   self-contained, как web-tools; или оставить Catppuccin-родителя (наследование
   обновляемой базы темы, но зависимость от порядка загрузки плагинов).
2. **№5 languageId**: (recommended-а) правка юзер-конфига lsp4ij при закрытой IDE —
   3 маппинга, мгновенно, ничего не дублируется; (б) mappings в плагине —
   самодостаточно для других юзеров, но риск двух инстансов серверов (нужен
   переходный план и проверка резолва serverId против user-defined).
3. **№8 spellcheck**: брать ли мелочь.
