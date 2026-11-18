package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

/** Separate manager for language files downloaded by the original TapTranslate app. */
class LanguagePackManagerActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var wifiOnly: CheckBox
    private var downloadedMlKit = emptySet<String>()
    private var loadingMlKit = false
    private var mlKitError: String? = null
    private val busyMlKit = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Языковые пакеты"
        buildScreen()
        refreshMlKit()
    }

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(scroll)
        render()
    }

    private fun render() {
        if (!::root.isInitialized) return
        root.removeAllViews()

        addText("УПРАВЛЕНИЕ ЯЗЫКОВЫМИ ПАКЕТАМИ", 19f, true, 8)
        addText(
            "Здесь показаны языковые файлы, которые само приложение скачивает для Tesseract OCR и Google ML Kit Offline Translate. Встроенные PP-OCRv6 и RT-DETR модели сюда не входят.",
            13f, false, 14
        )

        addText("TESSERACT OCR", 17f, true, 4)
        addText(
            "Файлы .traineddata хранятся в выбранной общей папке. Для распознавания они копируются во внутреннюю рабочую папку приложения. Общие копии не удаляются вместе с приложением; после переустановки выбери ту же папку повторно.",
            12f, false, 8
        )
        addText("Общая папка: " + TesseractLanguagePackStore.sharedFolderLabel(this), 12f, true, 4)
        addButton("Выбрать / изменить общую папку", { chooseSharedFolder() }, true, 10)

        val hasFolder = TesseractLanguagePackStore.hasSharedFolder(this)
        val tesseractStatus = TesseractLanguagePackStore.statuses(this)
        val languages = TesseractLanguagePackStore.availableLanguages()
        if (languages.isEmpty()) {
            addText("Не удалось прочитать список языков из исходного приложения.", 12f, false, 8)
        }
        for (pack in languages) {
            val saved = tesseractStatus[pack.code]
            val local = saved?.internal == true
            val shared = saved?.shared == true
            val hasAnyCopy = local || shared
            val detail = when {
                local && shared -> "Установлен · рабочая и постоянная копии · " + formatBytes(saved?.bytes ?: 0L)
                shared -> "Сохранён в общей папке · " + formatBytes(saved?.bytes ?: 0L)
                local -> "Только рабочая копия · " + formatBytes(saved?.bytes ?: 0L)
                else -> "Не загружен"
            }
            val actions = ArrayList<Pair<String, () -> Unit>>()
            if (local) {
                actions.add(Pair("Удалить рабочую") { confirmDeleteTesseract(pack, shared) })
            }
            if (local && !shared && hasFolder) {
                actions.add(Pair("Сохранить в общей") {
                    TesseractLanguagePackStore.saveLocalToShared(this, pack.code) { showResult(it) }
                })
            }
            if (shared && !local) {
                actions.add(Pair("Восстановить") {
                    TesseractLanguagePackStore.restoreFromShared(this, pack.code) { showResult(it) }
                })
            }
            if (!local && !shared) {
                actions.add(Pair("Скачать") {
                    TesseractLanguagePackStore.download(this, pack) { showResult(it) }
                })
            }
            addPackRow(pack.name + " (" + pack.code + ")", detail, actions, hasFolder || local || shared)
        }
        if (!hasFolder) {
            addText(
                "Сначала выбери общую папку. Без неё можно просматривать список, но новые пакеты не будут доступны для постоянного хранения.",
                12f, false, 12
            )
        }

        addText("GOOGLE ML KIT · OFFLINE TRANSLATE", 17f, true, 4)
        addText(
            "Каждый языковой пакет хранится под управлением ML Kit. Можно заранее скачать язык или удалить только его, не затрагивая остальные модели.",
            12f, false, 6
        )
        wifiOnly = CheckBox(this).apply {
            text = "Загружать модели только по Wi-Fi"
            textSize = 13f
            isChecked = getPreferences(MODE_PRIVATE).getBoolean(KEY_WIFI_ONLY, true)
            setOnCheckedChangeListener { _, checked ->
                getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_WIFI_ONLY, checked).apply()
            }
        }
        root.addView(wifiOnly, params(6))
        when {
            loadingMlKit -> addText("Получаю список загруженных моделей ML Kit…", 12f, false, 4)
            mlKitError != null -> addText("Ошибка ML Kit: " + mlKitError, 12f, false, 4)
        }
        val supported = try {
            TranslateLanguage.getAllLanguages().distinct().sorted()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not obtain ML Kit supported languages", t)
            emptyList()
        }
        for (code in supported) {
            val name = Locale.forLanguageTag(code).getDisplayName(Locale("ru"))
                .replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString()
                }
            val isDownloaded = downloadedMlKit.contains(code)
            val busy = busyMlKit.contains(code)
            val detail = when {
                busy -> "Операция выполняется…"
                isDownloaded -> "Загружен на устройстве"
                else -> "Не загружен"
            }
            val actions = when {
                busy -> emptyList()
                isDownloaded -> listOf(Pair("Удалить") { confirmDeleteMlKit(code, name) })
                else -> listOf(Pair("Скачать") { downloadMlKit(code, name) })
            }
            addPackRow(name + " (" + code + ")", detail, actions, !busy)
        }
        addButton("Обновить список моделей ML Kit", { refreshMlKit() }, !loadingMlKit, 8)

        addText("ML KIT OCR", 17f, true, 4)
        addText(
            "Языковые модули распознавания ML Kit OCR обслуживаются Google Play Services. Это не файлы .traineddata и не отдельные пользовательские модели; приложение не может переместить или удалить их по одному через этот менеджер.",
            12f, false, 12
        )

        status = addText("Готово.", 12f, false, 0)
    }

    private fun chooseSharedFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getPreferences(MODE_PRIVATE).getString(KEY_LAST_FOLDER, null)?.let {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                }
            }
        }
        try {
            startActivityForResult(intent, REQUEST_FOLDER)
        } catch (t: Throwable) {
            showResult("Не удалось открыть выбор папки: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    @Deprecated("Legacy result callback is retained for APK host compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        getPreferences(MODE_PRIVATE).edit().putString(KEY_LAST_FOLDER, uri.toString()).apply()
        TesseractLanguagePackStore.attachFolder(this, uri, flags) { showResult(it) }
    }

    private fun confirmDeleteTesseract(
        pack: TesseractLanguagePackStore.LanguagePack,
        hasSharedCopy: Boolean
    ) {
        AlertDialog.Builder(this)
            .setTitle("Удалить рабочую копию?")
            .setMessage(
                if (hasSharedCopy) {
                    "Удалить пакет «" + pack.name + "» только из рабочей папки приложения? " +
                        "Постоянная копия в общей папке останется. Её можно будет восстановить без повторной загрузки."
                } else {
                    "Для пакета «" + pack.name + "» нет копии в общей папке. " +
                        "Рабочая копия будет удалена окончательно; при необходимости пакет можно будет скачать заново."
                }
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                TesseractLanguagePackStore.delete(this, pack.code) { showResult(it) }
            }
            .show()
    }

    private fun refreshMlKit() {
        if (loadingMlKit) return
        loadingMlKit = true
        mlKitError = null
        render()
        try {
            RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    downloadedMlKit = models.mapNotNull { it.language }.toSet()
                    loadingMlKit = false
                    render()
                }
                .addOnFailureListener { error ->
                    loadingMlKit = false
                    mlKitError = error.message ?: error.javaClass.simpleName
                    render()
                }
        } catch (t: Throwable) {
            loadingMlKit = false
            mlKitError = t.message ?: t.javaClass.simpleName
            render()
        }
    }

    private fun downloadMlKit(code: String, name: String) {
        if (!busyMlKit.add(code)) return
        render()
        try {
            val model = TranslateRemoteModel.Builder(code).build()
            val builder = DownloadConditions.Builder()
            if (getPreferences(MODE_PRIVATE).getBoolean(KEY_WIFI_ONLY, true)) builder.requireWifi()
            RemoteModelManager.getInstance()
                .download(model, builder.build())
                .addOnSuccessListener {
                    busyMlKit.remove(code)
                    Toast.makeText(this, "Пакет ML Kit «" + name + "» загружен.", Toast.LENGTH_LONG).show()
                    refreshMlKit()
                }
                .addOnFailureListener { error ->
                    busyMlKit.remove(code)
                    mlKitError = error.message ?: error.javaClass.simpleName
                    render()
                }
        } catch (t: Throwable) {
            busyMlKit.remove(code)
            mlKitError = t.message ?: t.javaClass.simpleName
            render()
        }
    }

    private fun confirmDeleteMlKit(code: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Удалить языковой пакет ML Kit?")
            .setMessage(
                "Удалить пакет для языка «" + name + "»? Другие языки останутся на устройстве. " +
                    "При следующем переводе приложение сможет загрузить этот пакет снова."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> deleteMlKit(code, name) }
            .show()
    }

    private fun deleteMlKit(code: String, name: String) {
        if (!busyMlKit.add(code)) return
        render()
        try {
            val model = TranslateRemoteModel.Builder(code).build()
            RemoteModelManager.getInstance()
                .deleteDownloadedModel(model)
                .addOnSuccessListener {
                    busyMlKit.remove(code)
                    Toast.makeText(this, "Пакет ML Kit «" + name + "» удалён.", Toast.LENGTH_SHORT).show()
                    refreshMlKit()
                }
                .addOnFailureListener { error ->
                    busyMlKit.remove(code)
                    mlKitError = error.message ?: error.javaClass.simpleName
                    render()
                }
        } catch (t: Throwable) {
            busyMlKit.remove(code)
            mlKitError = t.message ?: t.javaClass.simpleName
            render()
        }
    }

    private fun addPackRow(
        title: String,
        detail: String,
        actions: List<Pair<String, () -> Unit>>,
        enabled: Boolean
    ) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(3))
        }
        val name = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val state = TextView(this).apply {
            text = detail
            textSize = 12f
        }
        block.addView(name, params(1))
        block.addView(state, params(3))
        if (actions.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in actions) {
                val button = Button(this).apply {
                    text = label
                    textSize = 11f
                    minHeight = dp(40)
                    isEnabled = enabled
                    setPadding(dp(5), 0, dp(5), 0)
                    setOnClickListener { action() }
                }
                row.addView(button, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dp(5) })
            }
            block.addView(row, params(4))
        }
        root.addView(block, params(1))
        val divider = android.view.View(this).apply { setBackgroundColor(0x22000000) }
        root.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { bottomMargin = dp(3) })
    }

    private fun addButton(
        label: String,
        action: () -> Unit,
        enabled: Boolean,
        bottom: Int = 7
    ) {
        val button = Button(this).apply {
            text = label
            isEnabled = enabled
            setOnClickListener { action() }
        }
        root.addView(button, params(bottom))
    }

    private fun addText(
        value: String,
        size: Float,
        bold: Boolean,
        bottom: Int
    ): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(view, params(bottom))
        return view
    }

    private fun showResult(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (::status.isInitialized) status.text = message
        render()
    }

    private fun params(bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L -> String.format(Locale.US, "%.1f МБ", value / (1024.0 * 1024.0))
        value > 0L -> value.toString() + " Б"
        else -> "размер неизвестен"
    }

    companion object {
        private const val TAG = "TapTranslatePacks"
        private const val REQUEST_FOLDER = 4108
        private const val KEY_WIFI_ONLY = "mlkit_wifi_only"
        private const val KEY_LAST_FOLDER = "last_tesseract_folder"
    }
}
