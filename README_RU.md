# Tap Translate 1.125 + Hy-MT2 Offline (SAF direct-file build)

## Что исправлено в этой редакции

1. Исправлена причина падения на первом переводе: merge-скрипт добавляет два отсутствовавших D8 synthetic CAS-помощника, на которые ссылаются корутины (`AtomicReferenceFieldUpdater.compareAndSet` и `AtomicReference.compareAndSet`). После переноса coroutine DEX скрипт проверяет все ссылки на D8 synthetic-классы и добавляет два отсутствовавших CAS-моста с сигнатурами, реально используемыми в байткоде.
2. GGUF больше не копируется в `Android/data/.../files/models` или `filesDir`. Приложение сохраняет SAF URI и persistable read permission, затем открывает `ParcelFileDescriptor`.
3. Для llama.cpp используется путь `/proc/self/fd/N`, полученный из открытого SAF-дескриптора. Дескриптор остаётся открытым, пока модель загружена. Поэтому модель читается с исходного SD/внешнего носителя; второй гигабайт на внутреннем накопителе не создаётся.
4. Перед загрузкой проверяются тип обычного файла, размер и сигнатура `GGUF`. Если выбранный DocumentsProvider предоставляет поток/pipe вместо seekable-файла, приложение сообщит об этом явно и не станет незаметно копировать модель.
5. Старые копии из предыдущих сборок больше не используются автоматически. Новая версия требует сохранённый SAF URI, чтобы не продолжать незаметно загружать 1,13-ГБ дубль из `Android/data`. Старая копия не удаляется автоматически: после успешного теста прямой загрузки её можно удалить вручную из `Android/data/com.tap_to_translate.snap_translate/files/models/` (если именно там она была создана).

## Модель

Используй `Hy-MT2-1.8B-GGUF` в формате **Q4_K_M** (около 1,13 ГБ):

https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF

Модель не включается в APK. Выбор выполняется через системный SAF-проводник. Для прямой работы выбранный провайдер должен предоставлять обычный seekable file descriptor (как правило, это системный раздел SD-карты в DocumentsUI).

## Сборка через GitHub Actions

1. Распакуй архив.
2. Помести всё содержимое `TapTranslate_HyMT2_Integration` в корень репозитория, сохранив `.github/workflows`, `app`, `input`, `scripts`, `tools`.
3. Commit в ветку `main`.
4. Actions → `Build Tap Translate 1.125 with Hy-MT2 (direct SAF)` → `Run workflow`.
5. Скачай Artifact `TapTranslate-1.125-HyMT2-direct-SAF-unsigned`.

Workflow использует JDK 17, Android SDK setup v4, compileSdk 35 и Gradle 8.9. Он компилирует helper APK, декодирует оба APK apktool, объединяет smali и ARM64-библиотеки llama.cpp, пересобирает host APK и выполняет `unzip -t` и `zipalign -c`.

## Что останется прежним

Tesseract 5.5.1, OCR, авто-заполнение, шрифты, регулировка размера текста и исходный Google Offline Translate остаются в основном APK. При ошибке Hy-MT2 текущий фрагмент переводится резервным движком ML Kit.

## Исправление вылета на первом запуске перевода

В v4 падение происходило при создании coroutine: DEX `kotlinx.coroutines` ссылался на D8-generated класс `androidx.concurrent.futures.AbstractResolvableFuture$SafeAtomicHelper$$ExternalSyntheticBackportWithForwarding0`, которого не было в объединённом APK. Merge-скрипт добавляет два минимальных smali-моста с CAS-семантикой и проверяет, что все D8 synthetic-классы, на которые ссылается coroutine DEX, присутствуют в итоговом наборе smali. Дополнительно запуск coroutine обёрнут в защиту с переходом на Google Offline, чтобы ошибка планировщика не завершала основной процесс.

## Проверка на устройстве

1. Подпиши unsigned APK тем же тестовым ключом, которым подписываешь предыдущие сборки.
2. Выбери `Hy-MT2 Offline` и укажи исходный `Hy-MT2-1.8B-Q4_K_M.gguf` на SD-карте.
3. При выборе файла Logcat должен показать `Direct SAF path probe passed`. Перед загрузкой появятся `Opening selected model through SAF without copying` и `SAF descriptor ready and reopenable`.
4. При успешной загрузке появится `Hy-MT2 loaded from content://...`.
5. Проверь короткий перевод. Если файл-провайдер не поддерживает произвольный доступ, пришли `TapTranslateHyMT2` и `AndroidRuntime`.

Важно: `/proc/self/fd/N` — способ передать уже открытый SAF-файл в API llama.cpp, который принимает путь, не создавая копию. Проект сначала проверяет, что этот путь можно открыть повторно и прочитать GGUF-сигнатуру. Это необходимая предварительная проверка, но не доказывает, что дальнейшее `mmap` внутри llama.cpp будет успешно работать на любом DocumentsProvider. Окончательно no-copy режим подтверждается только после успешной загрузки и перевода на SD-карте планшета. Если провайдер не поддерживает такой доступ, приложение сообщит об ошибке и не создаст копию автоматически.


## Прямой запуск с SD-карты без копирования

Модель больше не копируется: сохраняется SAF URI, а runtime держит открытым `ParcelFileDescriptor` и передаёт llama.cpp путь `/proc/self/fd/N`. Перед сохранением URI проверяются тип обычного файла, размер, сигнатура GGUF и повторное открытие proc-fd. Это исключает дополнительную копию примерно на 1,13 ГБ. Возможность работы зависит от того, выдаёт ли конкретный DocumentsProvider настоящий seekable file descriptor; если нет, приложение покажет причину отказа вместо скрытого копирования. Старые копии из прежних сборок можно удалить вручную из `Android/data/com.tap_to_translate.snap_translate/files/models/` после подтверждения прямой загрузки.
