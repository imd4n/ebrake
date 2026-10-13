# ffmpeg-kit-next — подключение движка

Библиотека **не публикуется в Maven**. Единственный путь — собрать `.aar` локально
([android/README.md](https://github.com/arthenica/ffmpeg-kit-next/tree/main/android)).

## 1. Сборка (один раз, на Linux/macOS с Nix)

```bash
git clone https://github.com/arthenica/ffmpeg-kit-next
cd ffmpeg-kit-next
./nix-android.sh --list-profiles
./nix-android.sh -p android-r27d
# при желании: --enable-gpl (для libx264/x265) --disable-arch-arm-v7a-neon ...
```

Результат: `prebuilt/bundle-android-aar-24-maven/com/arthenica/ffmpeg-kit-next/9.0.0/`.

## 2. Подключение к eBrake

Уже сделано: папка `prebuilt/bundle-android-aar-24-maven` лежит в репозитории,
репозиторий прописан в `settings.gradle.kts`, зависимость
`implementation("com.arthenica:ffmpeg-kit-next:9.0.0")` — в `app/build.gradle.kts`.

Если AAR нужно заменить (другая версия движка или другие флаги):

1. Собрать AAR по шагу 1 и положить в `prebuilt/bundle-android-aar-24-maven/`
   с тем же путём `com/arthenica/ffmpeg-kit-next/<версия>/`.
2. Обновить версию в `app/build.gradle.kts`.
3. Проверить на устройстве:
   `FFmpegKit.execute("-version")`, `FFmpegKit.execute("-encoders")`,
   `FFmpegKit.execute("-h encoder=h264_mediacodec")` — смотрим, есть ли
   `bitrate_mode`. Помощник есть в приложении: кнопка «Диагностика» на «Выводе».

## 3. Как будем вызывать (заготовка уже в коде)

* Команда строится в `transcode/TranscodeModels.kt` (`FfmpegCommands.previewArgs/fullArgs`).
* HW: `-c:v h264_mediacodec -bitrate_mode cq/vbr -qp_i_max Q -qp_p_max Q -qmin 18 -qmax Q`
  (настоящего CRF в железе нет — это его аналог; точные Q калибруем на твоём чипе).
* SW: `-c:v libx264 -crf 20/26/32` (только если сборка была с `--enable-gpl`).
* SAF: вход через `FFmpegKitConfig.getSafParameterForRead(context, uri)`,
  выход через `getSafParameterForWrite`, префикс `ffkitsaf://`.
* Прогресс: `executeAsync(cmd, sessionCallback, logCallback, statistics -> time)`.
* Метаданные: `FFprobeKit.getMediaInformation(safUrl)`.

## 4. Состояние на 28.09.2026

AAR собран и лежит в репозитории проекта:
`prebuilt/bundle-android-aar-24-maven/com/arthenica/ffmpeg-kit-next/9.0.0/`.

Команда, которой он получен:

```bash
./nix-android.sh -p android-r27d \
  --enable-lib-android-media-codec --enable-lib-x264 --enable-gpl \
  --disable-arch-arm-v7a --disable-arch-arm-v7a-neon \
  --disable-arch-x86 --disable-arch-x86-64
```

Проверено строками в `libavcodec.so`: `h264_mediacodec`, `hevc_mediacodec`,
`libx264`, опция `bitrate_mode` присутствуют. AAR — 8.7 МБ, только arm64-v8a,
APK — ~31 МБ.

`runCompareStub()` давно заменён на реальное сравнение: `TranscodeViewModel.runCompare()`
генерирует три `previewArgs(...)` по 10 сек и показывает настоящие размеры файлов.
Что важно помнить при доработках — в [NOTES.md](../NOTES.md) (SAF-ловушка,
особенности Kotlin-API, калибровка уровней на MediaCodec).

## 5. Лицензия

`--enable-gpl` + `libx264` означают, что дистрибутив (AAR и APK) обязан быть
под GPL-3.0. Это же относится к самому приложению — см. LICENSE в корне.
