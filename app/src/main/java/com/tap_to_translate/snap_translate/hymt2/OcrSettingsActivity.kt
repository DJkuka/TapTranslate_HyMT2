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

/** Comic OCR display and detector preprocessing settings. */
class OcrSettingsActivity : Activity() {
    private lateinit var alphaSwitch: Switch
    private lateinit var alphaSeek: SeekBar
    private lateinit var alphaValue: TextView
    private lateinit var maxSideSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var screenshotFormatSpinner: Spinner
    private lateinit var screenshotFolderLabel: TextView


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

        addText(root, "ДЕТАЛИЗАЦИЯ PP-OCRv6", 16f, true, dp(8))
        addText(root,
            "Максимальная сторона изображения для детектора PP-OCRv6 (detMaxSideLimit). 4000 px — текущее значение. Меньшие значения уменьшают изображение перед распознаванием и могут ускорить обработку, но мелкие буквы могут теряться.",
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
            "Снимок сохраняет исходную страницу вместе с наложенным переводом, без верхней панели управления. Нумерация продолжается с первого свободного номера: 001, 002, 003 и далее.",
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
                    .apply()
                screenshotPrefs.edit()
                    .putString(
                        KEY_SCREENSHOT_FORMAT,
                        if (screenshotFormatSpinner.selectedItemPosition == 1) "jpeg" else "png"
                    )
                    .apply()
                status.text = "Сохранено. detMaxSideLimit=" + selectedLimit +
                    " px применится после полного перезапуска приложения. Формат скриншотов: " +
                    if (screenshotFormatSpinner.selectedItemPosition == 1) "JPEG" else "PNG" +
                    "."
                Toast.makeText(this@OcrSettingsActivity,
                    "Настройки OCR сохранены", Toast.LENGTH_SHORT).show()
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
        private const val SCREENSHOT_PREFS = "taptranslate_screenshot_settings"
        private const val KEY_SCREENSHOT_TREE_URI = "screenshot_tree_uri"
        private const val KEY_SCREENSHOT_FORMAT = "screenshot_format"
        private const val REQUEST_SCREENSHOT_FOLDER = 4107
    }
}
