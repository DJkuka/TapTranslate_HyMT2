# Rebase Hy-MT2 на проверенную FontFit/AutoFill основу

- Исходная база заменена на пользовательский `TapTranslate_1.125_FontFit_AutoFill_unsigned.apk`.
- SHA-256 фиксирован в `scripts/verify_base_apk.py`; workflow прекращает сборку при подмене исходного APK.
- Подтверждено наличие в оригинале `PKG_LOW` и `PKG_HIGH` для Google Offline Translate и `lib/arm64-v8a/libtesseract.so`.
- При сравнении с предыдущим `Tesseract551_EnglishOCR` выявлены лишние OCR/translation patches: `x9/e`, `qh/p`, `vh/j`, `si/a`, `yh/f->e` и удаление `PKG_LOW`. Они не переносятся.
- Merge-скрипт фиксирует SHA-256 исходных OCR-файлов и тела метода Google Offline Translate до интеграции; сборка завершится ошибкой, если они изменятся.
- После пересборки сравниваются оригинальные `assets/`, native `lib/`, Tesseract library и metadata Google Offline Translate. Оба `PKG_LOW` и `PKG_HIGH` должны быть на месте.
- Hy-MT2 runtime, direct-SAF, CAS backport shims, настройки производительности, контекста, терминологии, стиля, статистика скорости и отдельный заблокированный Vulkan toggle перенесены без изменения.

## Проверки текущей итерации

- SHA-256 базы: `3cc9ac5a10add494b2053b947a8c748c7f0c2f5c69e936216a5ef1df4c833910`.
- `verify_base_apk.py` прошёл.
- Все патч-anchors найдены на базе FontFit/AutoFill.
- Dry-run всех интеграционных патчей на отдельной декодированной копии прошёл.
- Проверка подтвердила неизменность OCR-файлов и метода `yh/f->e` Google Offline Translate.
- `merge_apk.py` и `verify_base_apk.py` проходят `python -m py_compile`.
- Полная Gradle сборка helper APK, merge, итоговая проверка zipalign и runtime-тест на планшете ещё должны быть выполнены GitHub Actions и устройством.

## Содержимое настроек Hy-MT2

- Профили производительности; CPU threads 2/3/4/6/8; context 2048–8192; max output tokens 256–4096; Temperature, Top-P и Top-K.
- Фоновый контекст, словарь `Original = Translation`, 0–4 предыдущих пар перевода, профили стиля и пользовательские инструкции.
- Длительность и `tokensPerSecond`, среднее последних 10 успешных генераций.
- Отдельный Vulkan toggle остаётся выключенным/заблокированным, пока native AAR не будет собран с Vulkan backend.
- GGUF читается через сохранённый SAF URI и `/proc/self/fd/N`; дополнительная копия не создаётся.
