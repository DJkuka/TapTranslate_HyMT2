# Изменения direct-SAF fix v2

- Устранён `NoClassDefFoundError` из-за двух D8 synthetic CAS-функций. Добавлены минимальные smali-мосты к `AtomicReferenceFieldUpdater.compareAndSet` и `AtomicReference.compareAndSet`; merge-скрипт проверяет все synthetic-ссылки в собранном наборе DEX.
- Удалена лишняя зависимость `androidx.concurrent:concurrent-futures`, поскольку отсутствовавший класс был D8 backport helper, а не требовавшимся runtime API AndroidX Futures.
- Убран импорт GGUF в `getExternalFilesDir`. Сохраняется SAF URI, файл открывается через `ParcelFileDescriptor`, а llama.cpp получает `/proc/self/fd/N`; исходный дескриптор удерживается до выгрузки модели.
- Выбор файла проверяет regular/seekable fd, размер, GGUF magic и повторное открытие `/proc/self/fd/N`. Если provider выдаёт pipe/proxy, копирование не выполняется и показывается ошибка.
- Исправлены реальные callback anchors для четырёх селекторов движка в исходном APK. Ранее скрипт содержал буквальные `\n` в anchor и не мог выполнить эту инъекцию.
- Сохранены прежние Tesseract/OCR, шрифты, автозаполнение, dispatcher Google Offline fallback и UI менеджера модели.

## Проверки

- `merge_apk.py` проходит `python3 -m py_compile`.
- На отдельной декодированной копии исходного APK patcher успешно добавил callback во все четыре селектора и оба CAS shim; проверка разрешения 99 D8 synthetic-ссылок прошла.
- Полная финальная сборка helper APK + host APK выполняется GitHub Actions workflow из `.github/workflows/build-taptranslate-hymt2.yml`; её выполнение на устройстве и прямой `mmap` с SD-карты ещё требуют runtime-проверки.
