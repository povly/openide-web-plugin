# Сборка и релиз

## Конвейер ./build.sh (единственная команда сборки)

```
[0/4] plugin.xml sanity     python3 xml.etree — валидация resources/META-INF/plugin.xml
[1/4] javac --release 21    classpath: $OPENIDE_HOME/plugins/textmate-plugin/lib/modules/*:$OPENIDE_HOME/lib/*
[2/4] jar                   jar cfm dist/web-frontend-mini/lib/web-frontend-mini.jar manifest.mf
                             (-C out . -C resources .)  ← всё содержимое resources/ едет в jar
[3/4] zip                   dist/web-frontend-mini.zip  (jar -cMf, из dist/)
[4/4] done                  вывод версии из <version> + unzip -l
```

Требования: JDK 21+; OpenIDE в `/usr/share/openide` (переопределяется env
`OPENIDE_HOME`; `RELEASE` по умолчанию 21 — class-file target, JBR 25 исполняет 21).
Maven/Gradle отсутствуют намеренно. Версия читается grep'ом из
`resources/META-INF/plugin.xml` `<version>` — plugin.xml единственный источник версии.

Тестов в проекте НЕТ. Верификация любого изменения = `./build.sh` (компиляция +
валидация XML) + ручная проверка в IDE: установка zip через
Settings → Plugins → ⚙ → Install Plugin from Disk…, перезапуск, открытие `.vue` /
`.ts` / `.css`.

Проверка живости плагина в IDE:

```bash
grep -a 'Web Frontend Mini' ~/.cache/OpenIDE/OpenIDE2026.2/log/idea.log | tail -1
```

(лог: `wfm: getBundles() entered`, `wfm: bundle ready at …`).

## Release checklist

1. **Version bump** — только `<version>` в `resources/META-INF/plugin.xml`.
2. **Changelog** — дописать строку `vX.Y.Z: <что изменилось>` в `<description>`
   (история v1.2…v1.9.1 ведётся там же, по одной версии на релиз).
3. **README ×2 вместе**: `README.md` (EN, основной) и `README.ru.md` (RU) обновляются
   в одном коммите — фичи/требования/troubleshooting синхронны в обоих языках.
4. **Никаких диагностических активностей в релизе**: WFM-DIAG startup-активности
   убирались из релизной сборки (урок v1.5→v1.6: диагностика жила в отдельных
   diag-сборках, не в релизных).
5. `./build.sh` → `dist/web-frontend-mini.zip` → установка → визуальная проверка →
   GitHub Releases (релизы раздаются zip'ом из Releases; тег вида `vX.Y.Z`).
6. **Коммиты — conventional**: `feat:` / `fix:` / `docs:` / `chore:` / `research:`
   (наблюдаемый стиль репо: `feat: v1.9.1 - Ctrl+B navigation …`,
   `docs: vue src-navigation feature in README (EN/RU)`).
7. Git: работа в `main`, без веток (config AI Factory); составные shell-команды git
   разбивать на отдельные шаги (не `git checkout main && git pull`).

## Что нельзя

- Не включать `research/` в сборку (jar пакует `resources/`, но держать исследования
  вне поставки — политика проекта).
- Не bump'ать версию где-либо, кроме `<version>` plugin.xml (нет gradle.properties
  и прочих дублей).
- Не править `manifest.mf` руками — генерируется build.sh на шаге [2/4].
