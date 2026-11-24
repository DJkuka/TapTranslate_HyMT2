package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/** Comic OCR display and detector preprocessing settings. */
class OcrSettingsActivity : Activity() {
    private lateinit var alphaSwitch: Switch
    private lateinit var pageMaskSwitch: Switch
    private lateinit var bubbleWrapSwitch: Switch
    private lateinit var alphaSeek: SeekBar
    private lateinit var alphaValue: TextView
    private lateinit var maxSideSpinner: Spinner
    private lateinit var detImgModeSpinner: Spinner
    private lateinit var detLimitSideLenSpinner: Spinner
    private lateinit var detLimitTypeSpinner: Spinner
    private lateinit var detMaxCandidatesSpinner: Spinner
    private lateinit var detScoreModeSpinner: Spinner
    private lateinit var recBatchSizeSpinner: Spinner
    private lateinit var detUseDilationSwitch: Switch
    private lateinit var diagnosticsButton: Button
    private lateinit var diagnosticsOutput: TextView
    private lateinit var status: TextView
    private lateinit var screenshotFormatSpinner: Spinner
    private lateinit var screenshotFolderLabel: TextView
    private val floatControls = LinkedHashMap<String, FloatControlSpec>()

    private data class FloatControlSpec(
        val title: String,
        val min: Float,
        val step: Float,
        val maxProgress: Int,
        val decimals: Int,
        val seek: SeekBar,
        val valueLabel: TextView
    ) {
        fun value(): Float = min + seek.progress * step
    }

    private val maxSideValues = intArrayOf(4000, 2400, 2000, 1800, 1600, 1280)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OCR · настройки распознавания"
        buildScreen()
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })

        addText(root, "РАСПОЗНАВАНИЕ КОМИКСОВ", 20f, true, dp(10))
        addText(root,
            "PP-OCRv6 используется для распознавания комиксов. ML Kit и Cloud выбираются отдельно в штатном меню OCR.",
            14f, false, dp(18))

        addText(root, "ПРОЗРАЧНОСТЬ СВОБОДНОГО ТЕКСТА", 16f, true, dp(8))
        addText(root,
            "Отдельная непрозрачность фона прямоугольников для надписей вне баблов: вывесок, подписей и SFX. При выключенной настройке сохраняется прежняя общая прозрачность.",
            13f, false, dp(10))

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        alphaSwitch = Switch(this).apply {
            text = "Использовать отдельную прозрачность"
            isChecked = prefs.getBoolean(KEY_ALPHA_ENABLED, false)
        }
        root.addView(alphaSwitch, matchWrap(dp(8)))

        val inheritedAlpha = readCurrentBubbleAlpha()
        val inheritedPercent = ((inheritedAlpha * 100 + 127) / 255).coerceIn(0, 100)
        val savedPercent = prefs.getInt(KEY_ALPHA_PERCENT, inheritedPercent).coerceIn(0, 100)

        alphaValue = addText(root, "", 13f, false, dp(2))
        alphaSeek = SeekBar(this).apply {
            max = 100
            progress = savedPercent
            isEnabled = alphaSwitch.isChecked
        }
        root.addView(alphaSeek, matchWrap(dp(16)))
        updateAlphaLabel()

        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlphaLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        alphaSwitch.setOnCheckedChangeListener { _, checked ->
            alphaSeek.isEnabled = checked
        }

        addText(root,
            "В режиме ML Kit RT-DETR включается только для определения границ баблов и свободного текста, если эта опция включена. Сам текст продолжает распознавать ML Kit.",
            12f, false, dp(20))

        addText(root, "ТОНКАЯ НАСТРОЙКА PP-OCRv6", 16f, true, dp(8))
        addText(root,
            "Параметры ниже влияют только на PP-OCRv6. Для комиксов полезно сохранять чувствительность к мелким и стилизованным надписям. Для книжной печати можно осторожно повышать пороги, чтобы отсечь слабые шумовые области. Более строгие значения повышают риск пропуска бледного или мелкого текста.",
            13f, false, dp(10))

        addFloatControl(root, prefs, KEY_DET_THRESH, "detThresh",
            "Порог карты вероятностей. Ниже — больше кандидатов, включая мелкий текст и шум; выше — меньше слабых областей.",
            0.05f, 0.60f, 0.01f, 0.20f, 2)
        addFloatControl(root, prefs, KEY_DET_BOX_THRESH, "detBoxThresh",
            "Минимальная оценка найденной области. Повышение отсекает слабые рамки, но может терять бледные буквы и тонкие надписи.",
            0.10f, 0.90f, 0.01f, 0.45f, 2)
        addFloatControl(root, prefs, KEY_DET_UNCLIP_RATIO, "detUnclipRatio",
            "Насколько расширять рамку вокруг текста. Слишком малое значение может обрезать края, слишком большое — объединять соседние строки.",
            1.00f, 2.00f, 0.05f, 1.40f, 2)
        addFloatControl(root, prefs, KEY_REC_SCORE_THRESH, "recScoreThresh",
            "Минимальная уверенность распознавания. 0.00 сохраняет все непустые результаты; повышение отбрасывает сомнительные строки, но может убрать необычный шрифт.",
            0.00f, 1.00f, 0.05f, 0.00f, 2)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val comicsPreset = Button(this).apply {
            text = "Набор: комиксы"
            setOnClickListener {
                setFloatControl(KEY_DET_THRESH, 0.20f)
                setFloatControl(KEY_DET_BOX_THRESH, 0.45f)
                setFloatControl(KEY_DET_UNCLIP_RATIO, 1.40f)
                setFloatControl(KEY_REC_SCORE_THRESH, 0.00f)
                status.text = "Выбран экспериментальный набор для комиксов. Нажмите «Сохранить», чтобы записать его."
            }
        }
        val booksPreset = Button(this).apply {
            text = "Набор: книги"
            setOnClickListener {
                setFloatControl(KEY_DET_THRESH, 0.30f)
                setFloatControl(KEY_DET_BOX_THRESH, 0.60f)
                setFloatControl(KEY_DET_UNCLIP_RATIO, 1.50f)
                setFloatControl(KEY_REC_SCORE_THRESH, 0.00f)
                status.text = "Выбран экспериментальный набор для книг. Нажмите «Сохранить», чтобы записать его."
            }
        }
        presets.addView(comicsPreset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        presets.addView(booksPreset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(presets, matchWrap(dp(12)))
        addText(root,
            "Это стартовые экспериментальные наборы, а не измеренные оптимальные значения. Сравнивай их на одной и той же странице. Для очень мелкого или бледного шрифта сначала оставляй низкие пороги.",
            12f, false, dp(16))

        addText(root, "МАКСИМАЛЬНЫЙ РАЗМЕР ИЗОБРАЖЕНИЯ", 16f, true, dp(8))
        addText(root,
            "Максимальная сторона изображения для детектора PP-OCRv6 (detMaxSideLimit). 4000 px — исходное значение. Меньшие значения ускоряют обработку, но могут ухудшить распознавание мелких букв.",
            13f, false, dp(8))

        val sideLabels = maxSideValues.map { value ->
            when (value) {
                4000 -> "4000 px · как сейчас"
                else -> value.toString() + " px"
            }
        }
        maxSideSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@OcrSettingsActivity,
                android.R.layout.simple_spinner_item,
                sideLabels
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val savedLimit = prefs.getInt(KEY_MAX_SIDE, 4000)
            setSelection(maxSideValues.indexOf(savedLimit).let { if (it >= 0) it else 0 })
        }
        root.addView(maxSideSpinner, matchWrap(dp(18)))

        addText(root,
            "Для эксперимента можно начать с 2000 px, затем сравнить с 1600 px на той же странице. Если пропадают мелкие или стилизованные надписи, верни 4000 px. Новое значение применяется после полного перезапуска приложения.",
            12f, false, dp(20))

        addText(root, "ДОПОЛНИТЕЛЬНЫЕ ПАРАМЕТРЫ PP-OCRv6", 16f, true, dp(8))
        addText(root,
            "Эти параметры соответствуют полям PaddleOCRConfig. Меняй по одному: несколько агрессивных настроек одновременно затрудняют поиск причины пропусков.",
            13f, false, dp(8))

        detImgModeSpinner = addChoiceSpinner(
            root, "Цветовой порядок входа детектора (detImgMode)",
            listOf("BGR", "RGB"), prefs.getString(KEY_DET_IMG_MODE, "BGR") ?: "BGR"
        )
        detLimitTypeSpinner = addChoiceSpinner(
            root, "Правило изменения размера (detLimitType)",
            listOf("min", "max", "resize_long"), prefs.getString(KEY_DET_LIMIT_TYPE, "min") ?: "min"
        )
        detLimitSideLenSpinner = addChoiceSpinner(
            root, "Опорная сторона детектора, px (detLimitSideLen)",
            listOf("64", "320", "480", "640", "736", "960", "1280", "1536"),
            prefs.getInt(KEY_DET_LIMIT_SIDE_LEN, 64).toString()
        )
        detMaxCandidatesSpinner = addChoiceSpinner(
            root, "Максимум контуров-кандидатов (detMaxCandidates)",
            listOf("500", "1000", "2000", "3000", "5000", "8000"),
            prefs.getInt(KEY_DET_MAX_CANDIDATES, 3000).toString()
        )
        detScoreModeSpinner = addChoiceSpinner(
            root, "Оценка рамки (detScoreMode)",
            listOf("fast", "slow"), prefs.getString(KEY_DET_SCORE_MODE, "fast") ?: "fast"
        )
        detUseDilationSwitch = Switch(this).apply {
            text = "Морфологическое расширение карты (detUseDilation)"
            isChecked = prefs.getBoolean(KEY_DET_USE_DILATION, false)
        }
        root.addView(detUseDilationSwitch, matchWrap(dp(10)))
        recBatchSizeSpinner = addChoiceSpinner(
            root, "Размер пакета распознавания (recBatchSize)",
            listOf("1", "2", "4", "8"), prefs.getInt(KEY_REC_BATCH_SIZE, 1).toString()
        )
        addText(root,
            "Больший recBatchSize может ускорить обработку множества строк, но повышает пиковое потребление памяти. Для планшета безопасное исходное значение — 1. detBoxType зафиксирован как quad: другие типы рамок текущая реализация постобработки не поддерживает.",
            12f, false, dp(16))

        addText(root, "ПРОВЕРКА ПОДКЛЮЧЕНИЯ ДВИЖКОВ И МОДЕЛЕЙ", 16f, true, dp(8))
        addText(root,
            "Проверка запускает реальный тестовый проход PP-OCRv6, RT-DETR и BubblePop на синтетической странице. Для ML Kit проверяется наличие host-пути и SDK; для Cloud — регистрация провайдера. Сетевой запрос к Cloud не выполняется.",
            12f, false, dp(8))
        diagnosticsOutput = addText(root, "Проверка ещё не запускалась.", 13f, false, dp(4))
        diagnosticsButton = Button(this).apply {
            text = "Проверить движки и модели"
            setOnClickListener { runDiagnostics() }
        }
        root.addView(diagnosticsButton, matchWrap(dp(18)))

        addText(root, "МАСКА ОБЛАСТЕЙ ДЛЯ ПЕРЕВОДА", 16f, true, dp(8))
        addText(root,
            "BubblePop строит общую маску внутренних областей баблов на всей странице. Она не пытается разделять соединённые баблы на отдельные объекты. OCR-группы PP-OCRv6 остаются самостоятельными; маска будет использоваться как ограничение допустимой зоны компоновки.",
            13f, false, dp(8))
        pageMaskSwitch = Switch(this).apply {
            text = "Использовать карту BubblePop"
            isChecked = prefs.getBoolean(KEY_BUBBLEPOP_PAGE_MASK, false)
        }
        root.addView(pageMaskSwitch, matchWrap(dp(14)))

        addText(root,
            "Перенос строк в переводе внутри баблов. Если отключить, приложение сначала уменьшит шрифт, чтобы разместить фразу в одну строку. Если она не помещается даже при минимальном размере, останется безопасный многострочный вариант.",
            13f, false, dp(6))
        bubbleWrapSwitch = Switch(this).apply {
            text = "Автоматический перенос строк"
            isChecked = prefs.getBoolean(KEY_BUBBLE_AUTO_WRAP, true)
        }
        root.addView(bubbleWrapSwitch, matchWrap(dp(14)))

        addText(root, "СКРИНШОТЫ ПЕРЕВОДА", 16f, true, dp(8))
        addText(root,
            "Снимок сохраняет исходную страницу вместе с наложенным переводом, без верхней панели управления. Нумерация продолжается по порядку добавления: 001, 002, 003 и далее.",
            13f, false, dp(8))

        val screenshotPrefs = getSharedPreferences(SCREENSHOT_PREFS, MODE_PRIVATE)
        val screenshotFormats = listOf("PNG · без потерь", "JPEG · компактнее")
        screenshotFormatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@OcrSettingsActivity,
                android.R.layout.simple_spinner_item,
                screenshotFormats
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(
                if (screenshotPrefs.getString(KEY_SCREENSHOT_FORMAT, "png") == "jpeg") 1 else 0
            )
        }
        root.addView(screenshotFormatSpinner, matchWrap(dp(10)))

        screenshotFolderLabel = addText(root, "", 13f, false, dp(6))
        updateScreenshotFolderLabel()

        val chooseFolder = Button(this).apply {
            text = "Выбрать папку для скриншотов"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        screenshotPrefs.getString(KEY_SCREENSHOT_TREE_URI, null)?.let {
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                        }
                    }
                }
                try {
                    startActivityForResult(intent, REQUEST_SCREENSHOT_FOLDER)
                } catch (t: Throwable) {
                    status.text = "Не удалось открыть выбор папки: " + (t.message ?: "ошибка")
                }
            }
        }
        root.addView(chooseFolder, matchWrap(dp(18)))

        val save = Button(this).apply {
            text = "Сохранить настройки OCR и скриншотов"
            setOnClickListener {
                val selectedLimit = maxSideValues.getOrElse(maxSideSpinner.selectedItemPosition) { 4000 }
                prefs.edit()
                    .putBoolean(KEY_ALPHA_ENABLED, alphaSwitch.isChecked)
                    .putBoolean(KEY_BUBBLEPOP_PAGE_MASK, pageMaskSwitch.isChecked)
                    .putBoolean(KEY_BUBBLE_AUTO_WRAP, bubbleWrapSwitch.isChecked)
                    .putInt(KEY_ALPHA_PERCENT, alphaSeek.progress.coerceIn(0, 100))
                    .putInt(KEY_MAX_SIDE, selectedLimit)
                    .putString(KEY_DET_IMG_MODE, detImgModeSpinner.selectedItem.toString())
                    .putString(KEY_DET_LIMIT_TYPE, detLimitTypeSpinner.selectedItem.toString())
                    .putInt(KEY_DET_LIMIT_SIDE_LEN, detLimitSideLenSpinner.selectedItem.toString().toInt())
                    .putInt(KEY_DET_MAX_CANDIDATES, detMaxCandidatesSpinner.selectedItem.toString().toInt())
                    .putString(KEY_DET_SCORE_MODE, detScoreModeSpinner.selectedItem.toString())
                    .putBoolean(KEY_DET_USE_DILATION, detUseDilationSwitch.isChecked)
                    .putInt(KEY_REC_BATCH_SIZE, recBatchSizeSpinner.selectedItem.toString().toInt())
                    .putFloat(KEY_DET_THRESH, currentFloat(KEY_DET_THRESH, 0.20f))
                    .putFloat(KEY_DET_BOX_THRESH, currentFloat(KEY_DET_BOX_THRESH, 0.45f))
                    .putFloat(KEY_DET_UNCLIP_RATIO, currentFloat(KEY_DET_UNCLIP_RATIO, 1.40f))
                    .putFloat(KEY_REC_SCORE_THRESH, currentFloat(KEY_REC_SCORE_THRESH, 0.00f))
                    .apply()
                screenshotPrefs.edit()
                    .putString(
                        KEY_SCREENSHOT_FORMAT,
                        if (screenshotFormatSpinner.selectedItemPosition == 1) "jpeg" else "png"
                    )
                    .apply()
                val screenshotFormatLabel =
                    if (screenshotFormatSpinner.selectedItemPosition == 1) "JPEG" else "PNG"
                status.text = "Сохранено: detThresh=" + currentFloat(KEY_DET_THRESH, 0.20f) +
                    ", detBoxThresh=" + currentFloat(KEY_DET_BOX_THRESH, 0.45f) +
                    ", detUnclipRatio=" + currentFloat(KEY_DET_UNCLIP_RATIO, 1.40f) +
                    ", recScoreThresh=" + currentFloat(KEY_REC_SCORE_THRESH, 0.00f) +
                    ", detMaxSideLimit=" + selectedLimit +
                    " px. Настройки PP-OCRv6 применятся после полной остановки и повторного запуска приложения. Формат скриншотов: " +
                    screenshotFormatLabel + "."
                Toast.makeText(this@OcrSettingsActivity,
                    "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(save, matchWrap(dp(10)))

        status = addText(root, "", 13f, false, 0)
    }

    @Deprecated("Legacy activity result is used for compatibility with the host application")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREENSHOT_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val grantFlags = (data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        try {
            if (grantFlags != 0) {
                contentResolver.takePersistableUriPermission(uri, grantFlags)
            }
            getSharedPreferences(SCREENSHOT_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_SCREENSHOT_TREE_URI, uri.toString())
                .apply()
            updateScreenshotFolderLabel()
            status.text = "Папка для скриншотов сохранена."
        } catch (t: Throwable) {
            status.text = "Не удалось сохранить доступ к папке: " + (t.message ?: "ошибка")
        }
    }

    private fun addFloatControl(
        parent: LinearLayout,
        prefs: android.content.SharedPreferences,
        key: String,
        titleText: String,
        explanation: String,
        min: Float,
        max: Float,
        step: Float,
        defaultValue: Float,
        decimals: Int
    ) {
        addText(parent, titleText, 14f, true, 2)
        addText(parent, explanation, 12f, false, 2)
        val valueLabel = addText(parent, "", 12f, false, 0)
        val maxProgress = ((max - min) / step).roundToInt().coerceAtLeast(1)
        val saved = prefs.getFloat(key, defaultValue).coerceIn(min, max)
        val initialProgress = ((saved - min) / step).roundToInt().coerceIn(0, maxProgress)
        val seek = SeekBar(this).apply {
            this.max = maxProgress
            progress = initialProgress
        }
        parent.addView(seek, matchWrap(10))
        val spec = FloatControlSpec(titleText, min, step, maxProgress, decimals, seek, valueLabel)
        floatControls[key] = spec
        refreshFloatControl(spec)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshFloatControl(spec)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun addChoiceSpinner(parent: LinearLayout, titleText: String, choices: List<String>, selected: String): Spinner {
        addText(parent, titleText, 13f, true, 2)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@OcrSettingsActivity, android.R.layout.simple_spinner_item, choices).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(choices.indexOf(selected).coerceAtLeast(0))
        }
        parent.addView(spinner, matchWrap(12))
        return spinner
    }

    private fun runDiagnostics() {
        diagnosticsButton.isEnabled = false
        diagnosticsOutput.text = "Проверка выполняется. Модели могут загружаться впервые…"
        Thread({
            val lines = ArrayList<String>()
            var testBitmap: Bitmap? = null
            fun check(label: String, action: () -> String) {
                try { lines.add(label + ": " + action()) }
                catch (t: Throwable) {
                    var root: Throwable = t
                    while (root.cause != null && root.cause !== root) root = root.cause!!
                    lines.add(label + ": ОШИБКА — " + (root.message ?: root.javaClass.simpleName))
                    Log.e(TAG, "OCR diagnostic failed: " + label, t)
                }
            }
            try {
                val diagnosticBitmap = Bitmap.createBitmap(1280, 360, Bitmap.Config.ARGB_8888)
                testBitmap = diagnosticBitmap
                val canvas = Canvas(diagnosticBitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 92f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                canvas.drawText("COMIC OCR 123", 36f, 145f, paint)
                canvas.drawText("TEST PAGE 456", 36f, 275f, paint)

                var selectorOptions = emptySet<String>()
                check("Штатный OCR-селектор") {
                    val selector = Class.forName("vh.l")
                    val selected = selector.getMethod("c").invoke(null)?.toString() ?: "не определён"
                    selectorOptions = readSelectorOptions(selector)
                    "доступен; выбран: " + selected + "; пункты: " +
                        if (selectorOptions.isEmpty()) "список не удалось прочитать" else selectorOptions.joinToString()
                }
                check("ML Kit") {
                    val registered = selectorOptions.contains("ML Kit (Device Text Recognition)")
                    val hostPath = classAvailable("vh.j") && classAvailable("nf.d")
                    val sdk = classAvailable("com.google.mlkit.vision.text.TextRecognition")
                    "пункт меню=" + (if (registered) "да" else "не подтверждён") +
                        "; host OCR-путь=" + (if (hostPath) "найден" else "не найден") +
                        "; SDK=" + (if (sdk) "найден" else "не найден") +
                        "; фактический запрос этим тестом не выполнялся"
                }
                check("Cloud Text Recognition") {
                    if (selectorOptions.contains("Cloud Text Recognition"))
                        "провайдер зарегистрирован в штатном списке; реальное соединение не проверялось"
                    else "регистрация провайдера не подтверждена; сетевой запрос не выполнялся"
                }
                check("PP-OCRv6") {
                    val cls = Class.forName("com.tap_to_translate.snap_translate.hymt2.ocr.PaddleOcrLiveBridge")
                    val method = cls.getMethod("diagnosticSelfTest", Context::class.java, Bitmap::class.java)
                    method.invoke(null, applicationContext, diagnosticBitmap)?.toString() ?: "тест вернул пустой результат"
                }
                check("RT-DETR") {
                    val cls = Class.forName("com.tap_to_translate.snap_translate.hymt2.ocr.RtDetrLiveBridge")
                    val method = cls.getMethod("diagnosticRtDetr", Context::class.java, Bitmap::class.java)
                    method.invoke(null, applicationContext, testBitmap)?.toString() ?: "тест вернул пустой результат"
                }
                check("BubblePop") {
                    val cls = Class.forName("com.tap_to_translate.snap_translate.hymt2.ocr.RtDetrLiveBridge")
                    val method = cls.getMethod("diagnosticBubblePop", Context::class.java, Bitmap::class.java)
                    val result = method.invoke(null, applicationContext, testBitmap)?.toString() ?: "тест вернул пустой результат"
                    result + "; настройка использования сейчас: " +
                        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_BUBBLEPOP_PAGE_MASK, false)) "включена" else "выключена"
                }
            } catch (t: Throwable) {
                var root: Throwable = t
                while (root.cause != null && root.cause !== root) root = root.cause!!
                lines.add("Общая ошибка диагностики: " + (root.message ?: root.javaClass.simpleName))
                Log.e(TAG, "OCR diagnostics crashed", t)
            } finally {
                testBitmap?.recycle()
            }
            runOnUiThread {
                diagnosticsOutput.text = lines.joinToString("\n")
                diagnosticsButton.isEnabled = true
            }
        }, "taptranslate-ocr-diagnostics").start()
    }

    private fun classAvailable(name: String): Boolean = try {
        Class.forName(name, false, classLoader)
        true
    } catch (_: Throwable) { false }

    private fun readSelectorOptions(selector: Class<*>): Set<String> {
        val output = LinkedHashSet<String>()
        selector.declaredFields.forEach { field ->
            try {
                if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                field.isAccessible = true
                when (val value = field.get(null)) {
                    is Array<*> -> value.filterIsInstance<String>().forEach { output.add(it) }
                    is Iterable<*> -> value.filterIsInstance<String>().forEach { output.add(it) }
                }
            } catch (t: Throwable) { Log.d(TAG, "Could not inspect OCR selector field " + field.name, t) }
        }
        return output
    }

    private fun refreshFloatControl(spec: FloatControlSpec) {
        val formatted = String.format(Locale.US, "%." + spec.decimals + "f", spec.value())
        spec.valueLabel.text = spec.title + " = " + formatted
    }

    private fun setFloatControl(key: String, value: Float) {
        val spec = floatControls[key] ?: return
        val progress = ((value - spec.min) / spec.step).roundToInt().coerceIn(0, spec.maxProgress)
        spec.seek.progress = progress
        refreshFloatControl(spec)
    }

    private fun currentFloat(key: String, fallback: Float): Float =
        floatControls[key]?.value() ?: fallback

    private fun updateScreenshotFolderLabel() {
        val value = getSharedPreferences(SCREENSHOT_PREFS, MODE_PRIVATE)
            .getString(KEY_SCREENSHOT_TREE_URI, null)
        if (value.isNullOrBlank()) {
            screenshotFolderLabel.text = "Папка для скриншотов не выбрана."
            return
        }
        val folder = try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(value))
            id.substringAfter(':', id)
        } catch (_: Throwable) {
            "выбранная папка"
        }
        screenshotFolderLabel.text = "Папка для скриншотов: " + folder
    }

    private fun updateAlphaLabel() {
        val percent = alphaSeek.progress.coerceIn(0, 100)
        alphaValue.text = "Непрозрачность фона свободного текста: " + percent +
            "% (прозрачность: " + (100 - percent) + "%)"
    }

    private fun readCurrentBubbleAlpha(): Int {
        return try {
            val owner = Class.forName("ca.d2")
            val method = owner.getDeclaredMethod("a", java.lang.Boolean.TYPE)
            method.isAccessible = true
            (method.invoke(null, false) as Number).toInt().coerceIn(0, 255)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read host bubble alpha; using 255 as slider default", t)
            255
        }
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        bold: Boolean,
        bottom: Int
    ): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, bottom.coerceAtLeast(0) / 2)
        }
        parent.addView(view, matchWrap(bottom))
        return view
    }

    private fun matchWrap(bottom: Int): LinearLayout.LayoutParams {
        val density = resources.displayMetrics.density
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (bottom * density).toInt()
        }
    }

    companion object {
        private const val TAG = "TapTranslateOCRSettings"
        private const val PREFS_NAME = "taptranslate_ocr_settings"
        private const val KEY_ALPHA_ENABLED = "ocr_free_text_alpha_enabled"
        private const val KEY_BUBBLEPOP_PAGE_MASK = "ocr_bubblepop_page_mask_enabled"
        private const val KEY_BUBBLE_AUTO_WRAP = "ocr_bubblepop_auto_wrap"
        private const val KEY_ALPHA_PERCENT = "ocr_free_text_alpha_percent"
        private const val KEY_MAX_SIDE = "ocr_det_max_side_limit"
        private const val KEY_DET_THRESH = "ocr_det_thresh"
        private const val KEY_DET_BOX_THRESH = "ocr_det_box_thresh"
        private const val KEY_DET_UNCLIP_RATIO = "ocr_det_unclip_ratio"
        private const val KEY_REC_SCORE_THRESH = "ocr_rec_score_thresh"
        private const val KEY_DET_IMG_MODE = "ocr_det_img_mode"
        private const val KEY_DET_LIMIT_SIDE_LEN = "ocr_det_limit_side_len"
        private const val KEY_DET_LIMIT_TYPE = "ocr_det_limit_type"
        private const val KEY_DET_MAX_CANDIDATES = "ocr_det_max_candidates"
        private const val KEY_DET_USE_DILATION = "ocr_det_use_dilation"
        private const val KEY_DET_SCORE_MODE = "ocr_det_score_mode"
        private const val KEY_REC_BATCH_SIZE = "ocr_rec_batch_size"
        private const val SCREENSHOT_PREFS = "taptranslate_screenshot_settings"
        private const val KEY_SCREENSHOT_TREE_URI = "screenshot_tree_uri"
        private const val KEY_SCREENSHOT_FORMAT = "screenshot_format"
        private const val REQUEST_SCREENSHOT_FOLDER = 4107
    }
}
