package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
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
    private lateinit var alphaSeek: SeekBar
    private lateinit var alphaValue: TextView
    private lateinit var status: TextView
    private lateinit var screenshotFormatSpinner: Spinner
    private lateinit var screenshotFolderLabel: TextView
    private val floatControls = LinkedHashMap<String, FloatControlSpec>()
    private val intControls = LinkedHashMap<String, IntControlSpec>()
    private val spinnerControls = LinkedHashMap<String, SpinnerControlSpec>()
    private val switchControls = LinkedHashMap<String, Switch>()

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

    private data class IntControlSpec(
        val title: String,
        val min: Int,
        val step: Int,
        val maxProgress: Int,
        val unit: String,
        val seek: SeekBar,
        val valueLabel: TextView
    ) {
        fun value(): Int = min + seek.progress * step
    }

    private data class SpinnerControlSpec(
        val spinner: Spinner,
        val values: List<String>
    )


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
            "Порог пикселей на карте вероятностей. Полный диапазон 0–1: меньшее значение находит больше мелкого и слабого текста, но добавляет шум; большее отсекает слабые пиксели.",
            0.00f, 1.00f, 0.01f, 0.20f, 2)
        addFloatControl(root, prefs, KEY_DET_BOX_THRESH, "detBoxThresh",
            "Минимальная средняя оценка пикселей внутри рамки. Диапазон 0–1: повышение убирает слабые рамки, но может потерять бледные буквы и необычные шрифты.",
            0.00f, 1.00f, 0.01f, 0.45f, 2)
        addFloatControl(root, prefs, KEY_DET_UNCLIP_RATIO, "detUnclipRatio",
            "Коэффициент расширения рамки вокруг текста. Малые значения дают более тесные рамки; большие захватывают больше краёв и могут склеивать соседние строки. Доступен практический диапазон 0,10–10,00.",
            0.10f, 10.00f, 0.05f, 1.40f, 2)
        addFloatControl(root, prefs, KEY_REC_SCORE_THRESH, "recScoreThresh",
            "Минимальная уверенность распознавания строки. Диапазон 0–1: 0 сохраняет все непустые варианты; повышение отбрасывает сомнительные строки, но может убрать стилизованный текст.",
            0.00f, 1.00f, 0.01f, 0.00f, 2)

        addText(root, "МАСШТАБИРОВАНИЕ ДЕТЕКТОРА", 16f, true, dp(8))
        addIntControl(root, prefs, KEY_DET_LIMIT_SIDE_LEN, "detLimitSideLen",
            "Целевая длина стороны при предварительном масштабировании. Используется вместе с detLimitType. При min обычно не увеличивай её без необходимости: маленькие скриншоты могут сильно увеличиться.",
            32, 4096, 32, 64, " px")
        addSpinnerControl(root, prefs, KEY_DET_LIMIT_TYPE, "detLimitType",
            "Правило масштабирования: min поднимает короткую сторону до заданной длины; max ограничивает длинную сторону заданной длиной; resize_long точно задаёт длинную сторону. Для текущей конфигурации сохранён min.",
            listOf("min", "max", "resize_long"),
            listOf("min · короткая сторона не меньше", "max · длинная сторона не больше", "resize_long · задать длинную сторону"),
            "min")
        addIntControl(root, prefs, KEY_MAX_SIDE, "detMaxSideLimit",
            "Жёсткий верхний предел длинной стороны после масштабирования. Меньше — обычно быстрее и экономнее по памяти, но мелкие буквы могут потеряться. 4000 px — прежнее значение по умолчанию.",
            256, 8192, 32, 4000, " px")
        addSpinnerControl(root, prefs, KEY_DET_IMG_MODE, "detImgMode",
            "Порядок цветовых каналов на входе детектора. BGR — текущий и рекомендуемый режим для встроенной модели. RGB оставлен для экспериментов с совместимыми моделями.",
            listOf("BGR", "RGB"),
            listOf("BGR · текущий режим модели", "RGB · поменять порядок каналов"),
            "BGR")

        addText(root, "ПОСТОБРАБОТКА ДЕТЕКТОРА", 16f, true, dp(8))
        addIntControl(root, prefs, KEY_DET_MAX_CANDIDATES, "detMaxCandidates",
            "Максимум контуров-кандидатов, которые будут проверены после детекции. Повышение может сохранить больше мелких областей, но увеличивает время постобработки. Слишком низкое значение обрежет список кандидатов.",
            100, 20000, 100, 3000, " областей")
        addSwitchControl(root, prefs, KEY_DET_USE_DILATION, "detUseDilation",
            "Расширять маску детекции перед поиском контуров. Может соединять разорванные части букв, но иногда склеивает соседние слова или строки.",
            false)
        addSpinnerControl(root, prefs, KEY_DET_SCORE_MODE, "detScoreMode",
            "Способ вычисления оценки найденной рамки. fast быстрее и оценивает прямоугольную область; slow точнее по контуру, но может быть медленнее.",
            listOf("fast", "slow"),
            listOf("fast · быстрее", "slow · по контуру"),
            "fast")
        addText(root,
            "Форма рамки detBoxType в этой реализации закреплена как quad: полигональный режим poly ещё не реализован в геометрии обрезки и распознавания, поэтому он намеренно не предлагается.",
            12f, false, dp(16))

        addText(root, "РАСПОЗНАВАНИЕ И ПРОИЗВОДИТЕЛЬНОСТЬ", 16f, true, dp(8))
        addSpinnerControl(root, prefs, KEY_REC_BATCH_SIZE, "recBatchSize",
            "Сколько текстовых фрагментов распознавать одним вызовом ONNX Runtime. Больший batch может ускорить страницу с множеством строк, но расходует больше памяти. Фактическое значение автоматически ограничивается форматом входа ONNX-модели.",
            listOf("1", "2", "4", "8"),
            listOf("1 · минимальная память", "2", "4", "8 · максимальная нагрузка"),
            "1")
        addSpinnerControl(root, prefs, KEY_ORT_THREADS, "ONNX Runtime · потоки CPU",
            "Число потоков внутри каждого вызова ONNX Runtime. Больше не всегда быстрее: OCR конкурирует за CPU с Hy-MT2 и системными задачами. Начни с 2 и сравнивай 1, 2 и 4.",
            (1..8).map { it.toString() },
            (1..8).map { "$it" },
            "2")

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
            "Это стартовые наборы, а не измеренные оптимальные значения. Изменяй по одному параметру и сравнивай на одной странице. Настройки PP-OCRv6 применяются после полной остановки и повторного запуска приложения.",
            12f, false, dp(20))

        addText(root, "ДИАГНОСТИКА ФОРМЫ БАБЛОВ", 16f, true, dp(8))
        addText(root,
            "Экспериментальный BubblePop анализирует выбранную страницу и показывает найденные контуры поверх оригинала. На работу OCR и переводов эта проверка не влияет.",
            13f, false, dp(8))
        root.addView(Button(this).apply {
            text = "Открыть диагностику BubblePop"
            setOnClickListener {
                startActivity(Intent(this@OcrSettingsActivity, BubblePopDiagnosticActivity::class.java))
            }
        }, matchWrap(dp(16)))

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
                val selectedLimit = currentInt(KEY_MAX_SIDE, 4000)
                val editor = prefs.edit()
                    .putBoolean(KEY_ALPHA_ENABLED, alphaSwitch.isChecked)
                    .putInt(KEY_ALPHA_PERCENT, alphaSeek.progress.coerceIn(0, 100))
                    .putFloat(KEY_DET_THRESH, currentFloat(KEY_DET_THRESH, 0.20f))
                    .putFloat(KEY_DET_BOX_THRESH, currentFloat(KEY_DET_BOX_THRESH, 0.45f))
                    .putFloat(KEY_DET_UNCLIP_RATIO, currentFloat(KEY_DET_UNCLIP_RATIO, 1.40f))
                    .putFloat(KEY_REC_SCORE_THRESH, currentFloat(KEY_REC_SCORE_THRESH, 0.00f))
                intControls.forEach { (key, spec) -> editor.putInt(key, spec.value()) }
                spinnerControls.forEach { (key, spec) ->
                    editor.putString(key, spec.values.getOrElse(spec.spinner.selectedItemPosition) { spec.values.first() })
                }
                switchControls.forEach { (key, control) -> editor.putBoolean(key, control.isChecked) }
                editor.apply()
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
                    ", detLimitSideLen=" + currentInt(KEY_DET_LIMIT_SIDE_LEN, 64) +
                    ", detLimitType=" + currentString(KEY_DET_LIMIT_TYPE, "min") +
                    ", detMaxSideLimit=" + selectedLimit +
                    ", detMaxCandidates=" + currentInt(KEY_DET_MAX_CANDIDATES, 3000) +
                    ", detUseDilation=" + currentBoolean(KEY_DET_USE_DILATION, false) +
                    ", detScoreMode=" + currentString(KEY_DET_SCORE_MODE, "fast") +
                    ", recBatchSize=" + currentString(KEY_REC_BATCH_SIZE, "1") +
                    ", ortThreads=" + currentString(KEY_ORT_THREADS, "2") +
                    ". PP-OCRv6 применит их после полного перезапуска. Формат скриншотов: " +
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

    private fun addIntControl(
        parent: LinearLayout,
        prefs: android.content.SharedPreferences,
        key: String,
        titleText: String,
        explanation: String,
        min: Int,
        max: Int,
        step: Int,
        defaultValue: Int,
        unit: String
    ) {
        addText(parent, titleText, 14f, true, 2)
        addText(parent, explanation, 12f, false, 2)
        val valueLabel = addText(parent, "", 12f, false, 0)
        val maxProgress = ((max - min) / step).coerceAtLeast(1)
        val saved = prefs.getInt(key, defaultValue).coerceIn(min, max)
        val initialProgress = ((saved - min).toFloat() / step).roundToInt().coerceIn(0, maxProgress)
        val seek = SeekBar(this).apply {
            this.max = maxProgress
            progress = initialProgress
        }
        parent.addView(seek, matchWrap(10))
        val spec = IntControlSpec(titleText, min, step, maxProgress, unit, seek, valueLabel)
        intControls[key] = spec
        refreshIntControl(spec)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshIntControl(spec)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun refreshIntControl(spec: IntControlSpec) {
        spec.valueLabel.text = spec.title + " = " + spec.value() + spec.unit
    }

    private fun addSpinnerControl(
        parent: LinearLayout,
        prefs: android.content.SharedPreferences,
        key: String,
        titleText: String,
        explanation: String,
        values: List<String>,
        labels: List<String>,
        defaultValue: String
    ) {
        require(values.isNotEmpty() && values.size == labels.size)
        addText(parent, titleText, 14f, true, 2)
        addText(parent, explanation, 12f, false, 2)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@OcrSettingsActivity,
                android.R.layout.simple_spinner_item,
                labels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val saved = prefs.getString(key, defaultValue) ?: defaultValue
            setSelection(values.indexOf(saved).let { if (it >= 0) it else values.indexOf(defaultValue).coerceAtLeast(0) })
        }
        parent.addView(spinner, matchWrap(12))
        spinnerControls[key] = SpinnerControlSpec(spinner, values)
    }

    private fun addSwitchControl(
        parent: LinearLayout,
        prefs: android.content.SharedPreferences,
        key: String,
        titleText: String,
        explanation: String,
        defaultValue: Boolean
    ) {
        addText(parent, titleText, 14f, true, 2)
        addText(parent, explanation, 12f, false, 2)
        val control = Switch(this).apply {
            text = "Включить"
            isChecked = prefs.getBoolean(key, defaultValue)
        }
        parent.addView(control, matchWrap(12))
        switchControls[key] = control
    }

    private fun currentInt(key: String, fallback: Int): Int =
        intControls[key]?.value() ?: fallback

    private fun currentString(key: String, fallback: String): String {
        val spec = spinnerControls[key] ?: return fallback
        return spec.values.getOrElse(spec.spinner.selectedItemPosition) { fallback }
    }

    private fun currentBoolean(key: String, fallback: Boolean): Boolean =
        switchControls[key]?.isChecked ?: fallback

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
        private const val KEY_ALPHA_PERCENT = "ocr_free_text_alpha_percent"
        private const val KEY_MAX_SIDE = "ocr_det_max_side_limit"
        private const val KEY_DET_THRESH = "ocr_det_thresh"
        private const val KEY_DET_BOX_THRESH = "ocr_det_box_thresh"
        private const val KEY_DET_UNCLIP_RATIO = "ocr_det_unclip_ratio"
        private const val KEY_REC_SCORE_THRESH = "ocr_rec_score_thresh"
        private const val KEY_DET_LIMIT_SIDE_LEN = "ocr_det_limit_side_len"
        private const val KEY_DET_LIMIT_TYPE = "ocr_det_limit_type"
        private const val KEY_DET_IMG_MODE = "ocr_det_img_mode"
        private const val KEY_DET_MAX_CANDIDATES = "ocr_det_max_candidates"
        private const val KEY_DET_USE_DILATION = "ocr_det_use_dilation"
        private const val KEY_DET_SCORE_MODE = "ocr_det_score_mode"
        private const val KEY_REC_BATCH_SIZE = "ocr_rec_batch_size"
        private const val KEY_ORT_THREADS = "ocr_ort_threads"
        private const val SCREENSHOT_PREFS = "taptranslate_screenshot_settings"
        private const val KEY_SCREENSHOT_TREE_URI = "screenshot_tree_uri"
        private const val KEY_SCREENSHOT_FORMAT = "screenshot_format"
        private const val REQUEST_SCREENSHOT_FOLDER = 4107
    }
}
