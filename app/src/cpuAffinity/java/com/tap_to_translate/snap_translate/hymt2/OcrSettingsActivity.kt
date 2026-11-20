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
    private lateinit var maxSideSpinner: Spinner
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
                    .putInt(KEY_ALPHA_PERCENT, alphaSeek.progress.coerceIn(0, 100))
                    .putInt(KEY_MAX_SIDE, selectedLimit)
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
        private const val KEY_ALPHA_PERCENT = "ocr_free_text_alpha_percent"
        private const val KEY_MAX_SIDE = "ocr_det_max_side_limit"
        private const val KEY_DET_THRESH = "ocr_det_thresh"
        private const val KEY_DET_BOX_THRESH = "ocr_det_box_thresh"
        private const val KEY_DET_UNCLIP_RATIO = "ocr_det_unclip_ratio"
        private const val KEY_REC_SCORE_THRESH = "ocr_rec_score_thresh"
        private const val SCREENSHOT_PREFS = "taptranslate_screenshot_settings"
        private const val KEY_SCREENSHOT_TREE_URI = "screenshot_tree_uri"
        private const val KEY_SCREENSHOT_FORMAT = "screenshot_format"
        private const val REQUEST_SCREENSHOT_FOLDER = 4107
    }
}
