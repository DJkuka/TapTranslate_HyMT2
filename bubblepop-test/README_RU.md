# BubblePop Test (отдельное диагностическое приложение)

Независимый Android-проект для экспериментов с сегментацией речевых пузырей. Не зависит от основного приложения TapTranslate и не содержит Hy-MT2, Vulkan, Paddle OCR или интеграционных APK.

## Что входит

- BubblePop ONNX-модель сегментации речевых баблов;
- PP-OCRv6 tiny ONNX text detector для поиска текстовых строк (без распознавания символов);
- ONNX Runtime Android;
- OpenCV;
- экран выбора изображения и сравнения диагностических методов;
- просмотр маски, карты boundary, concavity-разделения и distance-transform watershed;
- OCR-guided режим с полигонами текста, группами строк и защитой этих групп от разреза;
- копирование текстового отчёта диагностики.

## Сборка

Из корня репозитория:

```bash
gradle -p bubblepop-test :app:assembleRelease
```

На выходе получается unsigned APK:
`bubblepop-test/app/build/outputs/apk/release/app-release-unsigned.apk`.

Модель загружается в CI из закреплённого коммита upstream-проекта BubblePop и помещается в assets перед сборкой.

## Изоляция

Изменения внутри `bubblepop-test/` запускают отдельную GitHub Actions workflow. Они не запускают тяжёлую сборку TapTranslate.


## PP-OCRv6

Используется официальный PP-OCRv6 tiny text-detection ONNX из репозитория
[PaddlePaddle/PP-OCRv6_tiny_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx),
зафиксированный на ревизии `2ba1506c0380b8f0b03dd142459aac66d4421f6c`.
В APK включён только детектор текста: он возвращает четырёхугольники строк.
Android preprocessing и DB postprocessing адаптированы из официального
[PaddleOCR Android SDK](https://github.com/PaddlePaddle/PaddleOCR/tree/main/deploy/ppocr-android).
Модель и исходный код распространяются по Apache-2.0.

Кнопка «PP-OCRv6 + защищённое разделение» группирует близкие строки по вертикальной
близости и горизонтальному выравниванию. Bounding-зоны групп передаются в splitter:
кандидат отклоняется, если одна группа текста попадает в разные результирующие части.
Это экспериментальный OCR-guided этап; он не предполагает автоматического исправления
редкой геометрии вроде случая №13.
