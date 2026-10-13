# eBrake — NOTES для будущих агентов

## Что за проект
Мобильный мини-HandBrake: перекодирование видео на устройстве.
3 экрана (свайп + док-бар): Ввод → Правка → Вывод. Стек: Kotlin + Jetpack Compose (M3).

## Решения (зафиксированы с пользователем)
- **Тема — вариант А (статичная)**: M3 используется только как toolkit, Material You выключен.
  `ui/theme/Theme.kt` — `dynamicColor` игнорируется, тёмная палитра eBrake Pro (`Color.kt`).
- **Сравнение сжатия** — превью-отрезок 10 сек × 3 уровня (HIGH/BALANCED/ECO), не весь файл.
- **HW-кодеки** — через `bitrate_mode cq/vbr + qp/qmin/qmax` (аналог CRF, фиксированного CBR нет).
  SW-запасной — `libx264 -crf 20/26/32` (нужен `--enable-gpl` при сборке движка).
- **Только arm64-v8a** (`app/build.gradle.kts: ndk.abiFilters`) — x86/armv7 искать уже сложно.
- **В галерее нет подписей** (28.09.2026): только превью + дата-разделы. Имена съедали высоту —
  на телефоне сетка даёт 6 рядов вместо 4. Имя файла осталось там, где оно нужно:
  в очереди/истории и в шапке «Сравнение: <файл>».
- **Мультивыбор на «Вводе»** (28.09.2026): тап — отметка с порядковым номером, 1-й отмеченный
  идёт на сравнение трёх уровней, кнопка «Импорт: N» кладёт остальных в очередь сразу с текущим
  уровнем. Сравнение ради каждого ролика — это минуты впустую, пользователь просил 1 раз.
- **Папка ввода** (28.09.2026): `OpenDocumentTree()` → галерея листает выбранное дерево SAF
  (обычно «смотреть только Downloads»). Разрешение на MediaStore при этом не нужно.
  «Все видео» возвращает к MediaStore.
- **Выполненная очередь** (28.09.2026): список закодированных роликов + «сэкономлено X из Y (Z%)».
  Считается по stat'у исходника (`contentResolver.openFileDescriptor`), т.к. `File.length()`
  для `content://` бесполезен. История в памяти процесса и не переживает перезапуск приложения —
  как и сама очередь.
- **Лог свёрнут** (28.09.2026): кнопка «Лог (N)» раскрывает все строки + «Копировать».
  Сырые ffmpeg-команды (строки, начинающиеся с `>`) в лог **больше не пишутся** вообще —
  они перебивали ход событий; по ошибке ffmpeg и так отдаёт свой хвост вывода.

## Движок ffmpeg-kit-next
- НЕ публикуется в Maven! AAR собран 27.09.2026 через `./nix-android.sh -p android-r27d`
  из `\\wsl.localhost\Ubuntu\home\imd4n\ebrake\ffmpeg-kit-next\prebuilt`.
- **ПЕРЕСБОРКА 28.09.2026**: `--enable-lib-android-media-codec --enable-lib-x264 --enable-gpl`
  + `--disable-arch-arm-v7a(-neon) --disable-arch-x86(-64)` → AAR 8.7 МБ (только arm64).
  Проверено строками в `libavcodec.so`: h264_mediacodec/hevc_mediacodec/libx264/bitrate_mode ✅.
- Лежит в проекте: `prebuilt/bundle-android-aar-24-maven/.../ffmpeg-kit-next-9.0.0.aar` (8.7 МБ,
  только arm64-v8a → APK ~38 МБ).
- Подключение: репозиторий уже прописан в `settings.gradle.kts`,
  зависимость в `app/build.gradle.kts`: `implementation("com.arthenica:ffmpeg-kit-next:9.0.0")`.
  Транзитивная зависимость `smart-exception-java:0.2.1` тянется с mavenCentral сама.
- Дока по сборке: `docs/FFMPEG_KIT_NEXT.md`.

## ⚠️ API-ловушка ffmpeg-kit-next (Kotlin-вариант, проверено через javap по classes.jar)
Библиотека переписана на Kotlin, старая Java-дока из README врёт в деталях:
- **НЕТ property-доступа**: `session.output` / `session.returnCode` НЕ компилируются
  (`returnCode` — protected field, геттер не синтезируется в property).
- **Надо вызывать геттеры явно**: `session.getOutput()`, `session.getReturnCode()?.value`,
  `ReturnCode.isSuccess(rc)` (static в Companion — из Kotlin вызывается как обычно).
- Сигнатуры: `FFmpegKit.execute(String)`, `executeAsync(String, complete, log, statistics)`.
  Пайпов НЕТ — `execute("-encoders | grep ...")` не сработает, фильтровать вывод в Kotlin.
- Пример рабочего кода: `TranscodeViewModel.runEngineDiagnostics()`.
- **Смешанный стиль API**: `Session`/`MediaInformation`/`StreamInformation` — обычные
  `fun getX()` (вызов `session.getOutput()` ОК), а `Statistics` — Kotlin-класс со
  свойствами: `stats.time`, `stats.size` и т.д. Явный `stats.getTime()` НЕ компилируется
  («Unresolved reference»), хотя javap метод показывает! (28.09.2026, чинилось долго).
- **SAM-ловушка**: пустая лямбда `{ }` НЕ подходит для `LogCallback` (ожидается `(Log) -> Unit`)
  и ломает вывод типов соседних лямбд — писать `{ _ -> }`.

## Факты с устройства
- `ffmpeg version n9.0.1` — движок стартует, rc=0. ✅ (27.09.2026)
- **ВЕРДИКТ 28.09.2026: в сборке НЕТ mediacodec (ни энкодеров, ни декодеров) и нет
  libx264/libx265.** Диагностика: `has[h264_mediacodec]=false`, `has[hevc_mediacodec]=false`,
  `has[libx264]=false`, `has[libx265]=false`, mediacodec-декодеров 0.
  Причина: дефолтный `./nix-android.sh` не включает `android-media-codec` и x264.
  Следствие: `Unrecognized option 'bitrate_mode'` — опций mediacodec-обёртки в этом
  ffmpeg просто нет (ошибка парсинга, не рантайма).
- Пересборка с HW + x264 (только arm64, быстрее):
  `./nix-android.sh -p android-r27d --enable-lib-android-media-codec --enable-lib-x264 --enable-gpl --disable-arch-arm-v7a --disable-arch-arm-v7a-neon --disable-arch-x86 --disable-arch-x86-64`
  Флаги найдены в `scripts/android/ffmpeg.sh:328` (`android-media-codec` → `--enable-mediacodec`)
  и `scripts/help-android.sh:34`. Для x265 добавить `--enable-lib-x265`.
- До пересборки приложение работает на fallback: см. ниже.

## Замеченные UI-дефекты (со скринов, backlog)
1. ~~Кнопка «Диагностика» переносится в 3 строки~~ — ИСПРАВЛЕНО 28.09.2026:
   ряд кнопок переведён на `FlowRow` (`OutputScreen.kt`), позже 28.09.2026 — на сетку
   по 2 кнопки в ряд (`Row` + `weight(1f)`): `FlowRow` давал разную ширину кнопок
   в зависимости от текста.
2. ~~Окно лога показывает только последние 12 строк~~ — ИСПРАВЛЕНО 28.09.2026:
   лог выводит все строки (`state.logs`, до 300). 28.09.2026 второй круг: 300 строк
   шума перекрывали очередь, поэтому лог свёрнут за кнопку «Лог (N)» и раскрывается
   вместе с «Копировать».
3. Карточки сравнения печатают сырую ffmpeg-команду — шумно. 28.09.2026 команды
   убраны из лога совсем, в UI остались плеер + размер + уровень.
4. ~~Док-бар: пункт во всю высоту пилюли выглядит её обрезком~~ — ИСПРАВЛЕНО 28.09.2026:
   `DockMetrics.ChipHeight = Height - 12.dp` + `verticalAlignment = CenterVertically`,
   радиус чипа = радиусу пилюли (`DockMetrics.Radius = Height / 2 = 32.dp`),
   чтобы кривизны совпадали.
5. ~~Плеер в карточке «Сравнение качества» — с острыми углами~~ — ИСПРАВЛЕНО 28.09.2026:
   `Box` кадра получил `.clip(RoundedCornerShape(12.dp))` до `.background(Color.Black)`.
6. ~~Движки кодека в один столбец занимают пол-экрана~~ — ИСПРАВЛЕНО 28.09.2026:
   `EngineGrid` — сетка 2×N, в ячейке короткое имя + характер («HW H.264 / быстро»).
   Поэтому у `TranscodeEngine` появились `shortName`/`hint` рядом с длинным `title`.

## Сборка: грабли окружения (уже пофикшены, не трогать)
- Каталог шаблона был битый: `core-ktx 1.19.0` + `lifecycle 2.11.0` требуют compileSdk 37,
  а установлен только android-36.1. Откачено на `coreKtx 1.17.0` / `lifecycle 2.9.3`
  в `gradle/libs.versions.toml`. Поднимать только вместе с compileSdk.
- Иконки Tune/VideoLibrary/Terminal — в `material-icons-extended` (не core).
- **У Media3 НЕТ BOM-артефакта** (`androidx.media3:media3-bom` даёт 404) — версии
  указывать напрямую на модулях (`media3 = "1.7.1"` в `[versions]`, проверено 28.09.2026,
  latest на тот момент 1.11.1, но берём 1.7.1 как заведомо живую).
- Foojay-тулчейн Gradle не отвечал → сборка привязана к JDK 21 из Android Studio:
  `org.gradle.java.home` + `org.gradle.java.installations.paths` в `gradle.properties`
  (путь машинно-специфичный!).

## Архитектура кода (кратко)
- `MainActivity` → `ui/EbrakeApp.kt` (Scaffold + NavigationBar + HorizontalPager, таб↔свайп
  синхронизированы) → `ui/screens/{Input,Edit,Output}Screen.kt`.
- Состояние: `transcode/TranscodeViewModel.kt` (один на все экраны) + `TranscodeModels.kt`
  (`TranscodeEngine`, `CompareLevel`, `TranscodePreset`, `TranscodeJob`, `SelectedInput`,
  `DoneRecord`, `FfmpegCommands`).
- `FfmpegCommands.previewArgs/fullArgs` — единственное место построения команд, SAF-URL вида
  `ffkitsaf://` подставит вызывающий код через `FFmpegKitConfig.getSafParameterForRead/Write`.
- **SAF-ловушка (28.09.2026, очередь падала с `Failed to open SAF id N: callback returned 0`)**:
  одноразовые SAF-URL (дефолт библиотеки) не открываются в `executeAsync`.
  Лечение: `FFmpegKitConfig.setSafUrlsReusable(true)` один раз (в `init` ViewModel),
  все URL создавать с `reusable=true` и освобождать вручную `unregisterSafProtocolUrl()`
  после каждого задания. Плюс `takePersistableUriPermission()` в пикере, т.к. очередь
  открывает файл позже возврата пикера.
- `runCompare()` — РЕАЛЬНОЕ сравнение с 28.09.2026: 3 синхронных `FFmpegKit.execute()`
  в IO-потоке, отрезок 10 сек из середины (seek из FFprobe-длительности), выход в
  `cacheDir/preview_<level>.mp4`, вход через многоразовый `getSafParameterForRead(..., true)`.
  Прогресс грубый (по уровням), отмена — `FFmpegKit.cancel()` + кнопка «Стоп».
  ViewModel — `AndroidViewModel` (нужен context для SAF и cacheDir).
- **Fallback-движки (28.09.2026)**: добавлен `TranscodeEngine.SW_MPEG4` (`mpeg4 -q:v 2/4/8`,
  constant quality, есть в любой сборке). Перед сравнением `ensureEncodersDetected()`
  парсит `-encoders`; `effectiveEngine()` молча заменяет отсутствующий движок по цепочке
  HW_H264 → HW_HEVC → SW_X264 → SW_MPEG4 с записью в лог. Недоступные чипы в EditScreen
  показываются в сетке погашенными («нет в сборке»), но не блокируются.
- **Ввод/вывод папок (28.09.2026)**: два зеркальных объекта в `transcode/` —
  `InputTarget` (дерево SAF для галереи, чтение) и `OutputTarget` (дерево SAF для ffmpeg,
  запись). Оба в SharedPreferences, оба с `takePersistableUriPermission`. Разрешение
  `READ_MEDIA_VIDEO` нужно ТОЛЬКО когда вход не выбран (MediaStore-ветка галереи).
- **Источник списка роликов**: `VideoLibrary.videos(context, tree)` — `tree != null` →
  обход `buildChildDocumentsUriUsingTree` на один уровень (как показывает системный пикер),
  фильтр `mime.startsWith("video/")` + запасной по расширению (часть провайдеров SAF не
  отдаёт mime). Длительности у SAF-файлов нет → бейдж не рисуется.
- **Выполненная очередь**: отдельный `StateFlow<List<DoneRecord>>` в `TranscodeQueue`
  (макс. 100 записей), пишет сервис в момент успеха, зеркалится в `TranscodeUiState.done`.
  `DoneRecord` сам считает `savedBytes`/`savedPercent`. `clearFinished()` историю не трогает —
  чистится отдельно кнопкой «Сбросить» в шапке.
- **Мультивыбор**: `TranscodeUiState.selection: List<SelectedInput>` хранит порядок нажатий;
  `inputUri`/`inputName` всегда = `selection.first()`. `toggleSelection()` снимает отметку →
  входом становится следующий. `addJob()` не добавляет ролик, который уже ждёт в очереди
  (`QUEUED`/`RUNNING`) — защита от дублей при повторном «Импорт».

## Калибровка HW на устройстве (28.09.2026, лог диагностики)
- `bitrate_mode` — int-опция; `cq` конкретный чип отвергает
  (`Could not open encoder before EOF`, -22). HIGH переведён на `vbr`.
- qp-границы без `-b:v` чип игнорирует: Баланс(q26)=1.3МБ vs Эко(q32)=1.4МБ (h264),
  480КБ vs 480КБ (hevc) — разницы нет. Лестница теперь через VBR-таргет:
  HIGH 8M / BALANCED 3M / ECO 1.2M (+maxrate, qp как страховка). См. `HwQuality`.
- Слайдер в Правке показывает только уровни с готовым файлом — упавший HIGH
  исчезал из слайдера (by design из другого чата, так и задумано).
- ✅ ПРОГОН VBR-лестницы 28.09.2026 (23с, отрезок 10с): H.264 = 6.9/4.3/2.0 МБ,
  HEVC = 4.8/3.9/2.0 МБ. Лестница монотонная везде, HEVC компактнее H.264
  на каждом уровне. Фактический битрейт ниже таргета (VBR не добирает на
  простом контенте) — нормально. Калибровка принята, не трогать.

## Публикация на GitHub (28.09.2026)
- Лицензия **GPL-3.0** — вынужденно: AAR собран с `--enable-gpl` + `libx264`,
  значит производная (APK) тоже под GPL. `LICENSE` — канонический текст с gnu.org.
- **Релиз = тег `v*`**: `.github/workflows/release.yml` собирает
  `:app:assembleRelease`, кладёт `eBrake-<версия>.apk` + `.sha256` в GitHub
  Releases (`generate_release_notes` сам собирает заметки из коммитов).
  `build.yml` на каждый пуш/PR собирает debug и отдаёт его артефактом.
- ⚠️ **`gradle.properties` больше не содержит `org.gradle.java.home`** — путь к
  JDK из Android Studio убьёт CI на Linux. Настройка переехала в
  `%USERPROFILE%\.gradle\gradle.properties` (вне репозитория). В `gradle.properties`
  осталась только подсказка, куда писать. Тулчейн 21 описан в
  `gradle/gradle-daemon-jvm.properties`.
- `org.gradle.jvmargs` поднят с `-Xmx2048m` до `-Xmx4096m` — release-сборка
  с 8.5 МБ AAR на 2 ГБ упирается в GC.
- **Тесты**: `app/src/test/.../EbrakeMathTest.kt` — 6 тестов на арифметику
  `Savings` (экономия/проценты/формат размеров). Логику экономии специально
  вынесли из `DoneRecord` в объект `Savings`: у `DoneRecord` есть `android.net.Uri`,
  а Uri в юнит-тестах без Robolectric кидает `ExceptionInInitializerError`
  на статическом инициализаторе. Тесты гоняются в обоих workflow перед сборкой.
- `.github/`: `ISSUE_TEMPLATE/{bug_report,feature_request}.yml`,
  `PULL_REQUEST_TEMPLATE.md`, `CONTRIBUTING.md` — базовый набор для публичного
  репозитория. В шаблоне бага отдельное поле под лог ffmpeg из «Вывода».
- **Подпись**: `app/build.gradle.kts` читает ключ из `keystore.properties` (локально)
  или из env (CI: `ANDROID_KEYSTORE_PATH` / `_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`).
  Секрет в репозитории называется `ANDROID_KEYSTORE_BASE64` — это сам файл ключа
  в base64; workflow распаковывает его в `$RUNNER_TEMP/release.jks` и кладёт путь
  в `ANDROID_KEYSTORE_PATH`.
  Без ключа release собирается под debug-подписью и не падает. Своего keystore
  пока нет — решение за владельцем, см. README («Релизы и подпись»): для F-Droid
  ключ не нужен вовсе (F-Droid подписывает сам), для GitHub Releases нужен один
  и тот же навсегда.
- **Скриншотов в репозитории нет** — `docs/screenshots/README.md` описывает,
  какие положить (F-Droid без скриншотов сборку не примет). В README стоят ссылки
  на несуществующие файлы: до заполнения папки картинки не отрисуются.
- `lint { checkReleaseBuilds = false }`: в AGP 9.3.1 задача `lintVitalAnalyzeRelease`
  падает сама (не резолвит свой `lintTool.versionKey`) и ломает `assembleRelease`.
  Статический анализ доступен вручную: `./gradlew :app:lint`.

## Следующие шаги (по приоритету)
1. ✅ Прогон 28.09.2026: сравнение на MPEG-4 fallback — все 3 уровня готовы
   (Высокое 14,5 МБ / Баланс 8,4 МБ / Эко 5,4 МБ за 10 сек), 172 энкодера, очередь ×3.
2. ✅ Очередь закодирована 28.09.2026 (`startQueue/cancelQueue`): последовательно через
   `executeAsync`, выход в `getExternalFilesDir/ebrake/ebrake_<id>_<codec>_<level>.mp4`
   (SAF-write не нужен), прогресс из `statistics.time` / FFprobe-длительность.
   ✅ ПРОГОН 28.09.2026: 3/3 DONE (Высокое 318 МБ / Баланс 188 МБ / Эко 126 МБ, исходник 2:47).
   SAF-фикс (`setSafUrlsReusable` + persistable permission) сработал.
3. ✅ Плееры превью (Media3 `QualityPreview.kt`, ExoPlayer+PlayerView, автоплей выкл).
4. ✅ Кнопка «Копировать» лог в буфер обмена (теперь внутри свёрнутого блока «Лог»).
5. ✅ ForegroundService + уведомление (`TranscodeService`, mediaProcessing, кнопка «Стоп»).
6. ⚠️ Нужен прогон на устройстве (28.09.2026, собрано, не проверено руками):
   - галерея без подписей + мультивыбор с номерами отметок;
   - выбор папки ввода (обход дерева SAF) и возврат «Все видео»;
   - «Импорт: N» — остальные ролики сразу в очередь с текущим уровнем;
   - выполненная очередь с экономией (нужно, чтобы stat исходника отдавал размер:
     если нет — в строке будет «исходник: размер неизвестен»);
   - сетка движков на «Правке», скруглённый кадр превью;
   - новый чип-док (короче пилюли, радиус 32dp) и сетка кнопок + свёрнутый лог на «Выводе».
7. Пересборка AAR с mediacodec + x264 (команда выше) → замена `prebuilt/` → новый APK →
   проверка, что чипы HW разблокировались.
8. Думается: сохранять выполненную очередь в SharedPreferences/JSON, чтобы экономия
   не обнулялась при перезапуске процесса (сейчас — только в памяти, как сама очередь).

