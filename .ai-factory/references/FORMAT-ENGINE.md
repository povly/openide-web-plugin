# FORMAT-ENGINE — контракты движка форматирования IntelliJ/OpenIDE

> Справочник задачи «починить форматтер». Все факты ниже проверены по исходникам и
> байт-коду **точной** версии пользователя. Обозначения источников:
>
> - `[S271]` — исходники intellij-community, частичный чекаут `/tmp/opencode/intellij-community`
>   (модули `platform/code-style-impl`, `platform/lang-impl`, `plugins/textmate`; build.txt `271.SNAPSHOT`).
>   Используются как читаемое зеркало логики; критичные места продублированы байт-кодом 262.
> - `[B262]` — байт-код (`javap -c`) из jar'ов установленной OpenIDE **262.10968.63.1**
>   (`/usr/share/openide/lib/*.jar`, `/usr/share/openide/plugins/textmate-plugin/lib/modules/intellij.textmate.jar`).
>
> Локальные jar-карты (262): `LanguageFormatting`, `CodeStyleManagerImpl` → `intellij.platform.codeStyle.jar`;
> `CodeFormatterFacade`, `FormatterImpl`, `FormatProcessor`, модели → `intellij.platform.ide.impl.jar`;
> TextMate-классы → `intellij.textmate.jar` (plugin).

## 1. Полный путь Reformat (Ctrl+Alt+L) — от Action до правок документа

```
ReformatCodeAction / Actions on Save (Reformat code)
  └─ ReformatCodeProcessor.run()                        [lang-impl, в чекауте отсутствует — 262 байт-код не снимался]
      └─ CodeStyleManager.reformatText(file, ranges)
          └─ CodeStyleManagerImpl.reformatText(file, formatRanges, predicate)      [S271 code-style-impl:162-188]
              └─ FormattingServiceUtil.formatRanges(file, ranges, isExplicit=false, isCompleteFile)
                  [S271 formatting/service/FormattingServiceUtil.java]
                  ├─ findService(file, ...): первый EP com.intellij.formattingService,
                  │  чьи фичи покрывают запрос. CoreFormattingService.canFormat() == true всегда →
                  │  дефолтный сервис найден всегда (fail-safe: LOG.assertTrue, если нет).
                  └─ CoreFormattingService.formatRanges(...)
                      └─ CodeFormatterFacade(settings, language, canChangeWSonly).processRange(tree, start, end)
                          [S271 CodeFormatterFacade.java:74-120; цепочка сверена с B262]
                          ├─ LanguageFormatting.INSTANCE.forContext(fileToFormat)
                          │   [B262 intellij.platform.codeStyle.jar; логика:]
                          │   1. LanguageFormattingRestriction EP → isFormatterAllowed(element); false → null
                          │   2. findCustomFormattingModelBuilder: только CustomFormattingModelBuilder из EP
                          │   3. иначе forLanguage(file language) — EP `com.intellij.lang.formatter`,
                          │      т.е. наш <lang.formatter language="textmate">   ← резолв работает
                          │      (косвенно подтверждено: completion.contributor language="textmate"
                          │       резолвится на тех же файлах — тот же LanguageExtension-механизм)
                          │   4. ExternalFormatProcessor.useExternalFormatter(file) → обёртка (не наш случай)
                          │   5. Formatter.isEligibleForVirtualFormatting → wrapForVirtualFormatting (не наш случай)
                          ├─ if (builder == null) → ТИХО возвращаемся: ни лога, ни правок (!)
                          ├─ CoreFormatterUtil.buildModel(builder, element, range, settings, REFORMAT)
                          │   [S271 CoreFormatterUtil.java:26-32]
                          │   └─ builder.createModel(FormattingContext.create(element, range, settings, mode))
                          │       ← ЕДИНСТВЕННАЯ точка входа билдера; вызывается и indent-детектором
                          ├─ FormatterEx.getInstanceEx().format(model, settings, indentOptions,
                          │       new FormatTextRanges(range, true))                [S271 FormatterImpl.java:218-240]
                          │   ├─ validateModel: проверяет ТОЛЬКО ASTBlock-корни (наш корень — не ASTBlock → проходит)
                          │   └─ FormatProcessor.format(model, sequentially=true)  [S271 FormatProcessor.java:85-95]
                          │       ├─ (построение) InitialInfoBuilder — WrapBlocksState   [InitialInfoBuilder.java]
                          │       ├─ AdjustWhiteSpacesState — желаемый whitespace      [engine/AdjustWhiteSpacesState.java]
                          │       ├─ ExpandChildrenIndentState
                          │       └─ ApplyChangesState — генерация и применение правок [engine/ApplyChangesState.java]
                          └─ … PostprocessReformattingAspect, command "Reformat"
```

## 2. Как движок строит wrapper-дерево (InitialInfoBuilder) — реальные контракты

`InitialInfoBuilder.prepareToBuildBlocksSequentially(root, docModel, options, ...)` → `buildFrom(root, 0, null, null, null)` [S271 InitialInfoBuilder.java:71-151]:

- **Композитность определяется `getSubBlocks()`, а НЕ `Block.isLeaf()`**: блок с непустым
  `getSubBlocks()` → `CompositeBlockWrapper`, пустым → `LeafBlockWrapper` [строки 141-150].
  `isLeaf()` в этом пути вообще не вызывается — поэтому isLeaf-фикс v1.13.2 не менял поведения
  Reformat (он важен для других обходчиков, напр. indent-детектора `getBlockAtOffset`).
- `ASSERT.checkChildRange(parentRange, childRange, model)` [строка 134]: диапазон ребёнка обязан
  лежать внутри родителя; нарушения кидают FormattingModelInconsistencyException → LOG.error
  (в idea.log ошибок нет → наши диапазоны валидны).
- Блок вне affected-рангов → `buildLeafBlock(..., readOnly=true)` [строки 148-150] — правки его
  whitespace запрещены. При Reformat всего файла ранг = весь файл → не наш случай.
- **WhiteSpace-объекты** создаются между подряд идущими лексемами:
  `myCurrentWhiteSpace.changeEndOffset(blockStartOffset, ...)` [строка 137]; после лексемы —
  `new WhiteSpace(textRange.getEndOffset(), false)` [строка 288]. Первый whitespace стартует с
  `min(root.start, affected.start)` [строки 66, 83-91] и помечен `isFirstWhiteSpace=true`.
- **getSpacing(prev, next)**: `initCurrentWhiteSpace` [строки 230-233] — вызывается для пары
  соседних детей ОДНОГО родителя; для самого первого ребёнка (prev==null) — только если
  whitespace isFirstWhiteSpace. Значение кастится к `SpacingImpl` → обязана быть платформенная
  реализация `Spacing` (наши пресеты `Spacing.createSpacing(...)` — подходят; возвращать `null`
  для первого ребёнка допустимо — `arrangeLineFeeds` толерантен к null [S271 WhiteSpace.java:387-389,
  ветка `else if (isFirst()) setLineFeeds(0)`]).
- `spaceProperty.isReadOnly()` → whitespace readonly (наш `Spacing.getReadOnlySpacing()` — легальный способ «не трогать»).
- Disabled ranges (`@formatter:off` через FormatterTagHandler) → whitespace readonly [строки 289-290, 333-335].

## 3. Как генерируются правки (AdjustWhiteSpacesState + ApplyChangesState)

`AdjustWhiteSpacesState.processToken()` [S271 engine/AdjustWhiteSpacesState.java:149-205] для каждой лексемы:
- `whiteSpace.arrangeLineFeeds(spaceProperty, blockRangesMap)` — число переводов строк (min/pref line feeds, keep);
- если нет line feeds — `arrangeSpaces(spaceProperty)` (min/max spaces);
- `IndentAdjuster.adjustIndent(block)` [engine/IndentAdjuster.java:44-66]: **отступ применяется только
  когда whitespace содержит line feeds** → `block.calculateOffset(options)` → `whiteSpace.setSpaces(...)`.

Отступ `LeafBlockWrapper.calculateOffset` [LeafBlockWrapper.java:159-186] → `parent.getChildOffset(...)`
[AbstractBlockWrapper.java:172+]: накапливается по цепочке wrapper-родителей; вклад ребёнка считается
по его `Indent` через `CoreFormatterUtil.getIndent` [CoreFormatterUtil.java:216-237]:
`NONE → 0`, `NORMAL → INDENT_SIZE`, `CONTINUATION → CONTINUATION_INDENT_SIZE` и т.д. Вклад родителя
учитывается, когда родительский whitespace содержит line feeds.

`ApplyChangesState` [engine/ApplyChangesState.java]:
- `collectBlocksToModify()` [строки 118-131]: лексема к правке ⟺ `!whiteSpace.isReadOnly()` и
  `whiteSpace.generateWhiteSpace(indentOptions)` != текущего текста.
- **Пустой список → `setDone(true)` без единой правки** [строки 142-145] — наблюдаемый симптом.
- Иначе на каждую лексему: `FormatProcessorUtils.replaceWhiteSpace(model, block, shift, newWs, options)`
  → `model.replaceWhiteSpace(...)`; завершение — `myModel.commitChanges()` [строка 198].

**Ключевой вывод: куда попадают правки, решает ТИП FormattingModel, возвращённый нашим createModel.**

## 4. ROOT CAUSE: PsiBasedFormattingModel умирает на TextMate PSI

`Formatter.getInstance().createFormattingModelForPsiFile(file, root, settings)` возвращает
`PsiBasedFormattingModel` [S271 FormatterImpl.java:564-569] — применитель правок через **AST**:

`PsiBasedFormattingModel.replaceWithPSI` [S271 psi/formatter/PsiBasedFormattingModel.java:78-112]:
```java
ASTNode leafElement = findElementAt(offset);           // элемент AST сразу после whitespace
if (leafElement != null) {
  if (leafElement.getPsi() instanceof PsiFile) return null;           // тихий no-op
  return replaceWithPsiInLeaf(effectiveRange, whiteSpace, leafElement); // → FormatterUtil.replaceWhiteSpace(…, TokenType.WHITE_SPACE, …)
} else if (textRange.getEndOffset() == myASTNode.getTextLength()) { …replaceLastWhiteSpace… }
else return null;                                                       // тихий no-op
```
`replaceWithPsiInLeaf` [строки 127-136] требует у AST-листа соседний узел `TokenType.WHITE_SPACE`.

**TextMate PSI не имеет таких узлов никогда**: `TextMateParserDefinition.createLexer()` возвращает
`EmptyLexer` — подтверждено байт-кодом 262 (`javap` intellij.textmate.jar: `new EmptyLexer` в
`createLexer`) и исходником [S271 plugins/textmate/.../TextMateParserDefinition.java:21-23].
Плоский TextMateFile не содержит WHITE_SPACE AST-узлов → `FormatterUtil.replaceWhiteSpace` не находит,
что заменять → документ не меняется → **«Строки не были изменены», без ошибок и исключений**.

Полностью объясняет факты сессии: модели строятся (логи), дерево идемпотентно, isLeaf-фикс корректен,
но ничего не применяется. Также объясняет, почему правки v1.10–v1.13 по модельным гипотезам не помогали:
поломка не в модели, а в выбранном применителе правок.

## 5. Решение (Task 4)

Вернуть из `createModel` **`DocumentBasedFormattingModel`** [S271 psi/formatter/DocumentBasedFormattingModel.java:55-57,
public ctor `(Block rootBlock, CodeStyleSettings settings, PsiFile file)`]: его
`replaceWhiteSpace` делает `myDocument.replaceString(start, end, whiteSpace)` напрямую [строки 56-92+] —
без PSI, ровно то, что нужно для лексемной модели поверх EmptyLexer-грамматики.

Dummy-модель без грамматики остаётся `Formatter.getInstance().createDummyFormattingModel(file)`.

## 6. Чек-лист инвариантов модели (сверка с WfmBlock)

| # | Инвариант (источник) | WfmBlock | Статус |
|---|---|---|---|
| 1 | Композит ⟺ `getSubBlocks()` непуст; `isLeaf()` движком Reformat не читается (InitialInfoBuilder:141-150) | `to-from>1` имеет детей; isLeaf=`to-from<=1` (для других обходчиков) | ✅ |
| 2 | Диапазоны детей ⊂ диапазон родителя, отсортированы, без пересечений (ASSERT.checkChildRange:134) | дети — подряд идущие окна [i, close+1) | ✅ (проверяется фаззером) |
| 3 | `getSpacing(prev, next)` — платформенный `Spacing` (каст к SpacingImpl:232); null допустим только первому ребёнку | пресеты `Spacing.createSpacing` / `getReadOnlySpacing`; для prev==null → null | ✅ |
| 4 | Отступ применяется только при line feeds в whitespace (IndentAdjuster:52-56) | LINE_BREAK/SPACE_OR_LINE с minLineFeeds=1 у `{`/`}`/`;` | ✅ |
| 5 | Правки идут через `FormattingModel.replaceWhiteSpace` → тип модели решает всё (ApplyChangesState:164-183) | **было** PsiBased (умирало), **стало** DocumentBased | 🔧 фикс |
| 6 | read-only whitespace защищают текст (Spacing.getReadOnlySpacing / disabled ranges) | js/ts и прочее не-CSS — getReadOnlySpacing | ✅ |
| 7 | Идемпотентность: format(format(x)) == format(x) — второй прогон не порождает диффа | симулятор ядра ×3 прогона | ✅ (тест задачи 6) |

## 7. Открытые вопросы / наблюдения

- Логи `wfm: formatter model built` в idea.log не различают вызовы Reformat-action и
  indent-автодетектора (детектор тоже зовёт createModel, обходя isLeaf напрямую). Различимость
  даёт инструментирование в `commitChanges` (Task 3): счётчик реальных правок документа.
- Кэш LAST_MODEL однослотовый: детектор, чередуя файлы, вытесняет записи — «model built»
  повторяется для одного файла. Не ошибка, но шумно в логах; кадидат на уточнение.
- `getIndentOptions(...)` в CodeFormatterFacade для 262 не декомпилировался детально;
  auto-detect отступов для TextMate отключён нами в v1.13.0 — не влияет на применение правок.
