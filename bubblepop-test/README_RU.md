# BubblePop Test (отдельное диагностическое приложение)

Независимый Android-проект для экспериментов с сегментацией речевых пузырей. Не зависит от основного приложения TapTranslate и не содержит Hy-MT2, Vulkan, Paddle OCR или интеграционных APK.

## Что входит

- BubblePop ONNX-модель сегментации речевых баблов;
- ONNX Runtime Android;
- OpenCV;
- экран выбора изображения и сравнения диагностических методов;
- просмотр маски, карты boundary, concavity-разделения и distance-transform watershed;
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
