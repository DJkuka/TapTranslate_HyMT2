package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** SAF-based model picker. The GGUF remains at its original location; it is never copied. */
class HyMT2SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var modelInfo: TextView
    private lateinit var chooseButton: Button
    private lateinit var loadButton: Button
    private lateinit var unloadButton: Button

    private var autoPicker = false
    private var pickerLaunched = false
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoPicker = intent.getBooleanExtra(EXTRA_AUTO_PICK, false)
        Log.i(TAG, "HyMT2SettingsActivity onCreate; autoPicker=$autoPicker")
        if (!autoPicker) {
            buildScreen()
            refreshState()
        } else {
            // Do not initialize UI/runtime before handing control to DocumentsUI.
            Log.i(TAG, "Auto-picker mode: skip settings UI and model runtime")
        }
    }

    override fun onResume() {
        super.onResume()
        if (autoPicker && !pickerLaunched) {
            pickerLaunched = true
            Log.i(TAG, "Auto-picker Activity resumed; preparing ACTION_OPEN_DOCUMENT")
            Handler(Looper.getMainLooper()).post { launchPicker() }
        }
    }

    private fun buildScreen() {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Hy-MT2 Offline"
            textSize = 24f
            gravity = Gravity.CENTER_VERTICAL
        }, match())

        root.addView(TextView(this).apply {
            text = "Локальный перевод на устройстве. Выбери исходный GGUF на SD-карте или другом носителе. Файл не копируется: приложение получает разрешение SAF и читает его непосредственно с исходного места."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad)
        }, match())

        modelInfo = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(modelInfo, match())

        chooseButton = Button(this).apply {
            text = "1. Выбрать GGUF на SD-карте"
            setOnClickListener { launchPicker() }
        }
        root.addView(chooseButton, match())

        loadButton = Button(this).apply {
            text = "2. Загрузить модель в RAM"
            setOnClickListener { loadModel() }
        }
        root.addView(loadButton, match())

        unloadButton = Button(this).apply {
            text = "Освободить память"
            setOnClickListener { unloadModel() }
        }
        root.addView(unloadButton, match())

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, pad)
            text = "Ожидание."
        }
        root.addView(status, match())

        root.addView(TextView(this).apply {
            text = "Нужен Hy-MT2-1.8B-Q4_K_M.gguf (около 1,13 ГБ). Проверяется сигнатура GGUF и возможность произвольного чтения. Дополнительная копия во внутреннюю память не создаётся. На время загрузки/перевода не извлекай карту памяти."
            textSize = 13f
            setPadding(0, pad, 0, pad)
        }, match())
    }

    private fun launchPicker() {
        if (importing || HyMT2Runtime.isLoaded()) {
            if (!autoPicker) Toast.makeText(this, "Сначала выгрузи модель из памяти.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            Log.i(TAG, "Launching ACTION_OPEN_DOCUMENT for GGUF; no-copy SAF mode")
            startActivityForResult(intent, REQUEST_MODEL)
            Log.i(TAG, "ACTION_OPEN_DOCUMENT startActivityForResult returned")
        } catch (t: Throwable) {
            Log.e(TAG, "ACTION_OPEN_DOCUMENT launch failed", t)
            if (autoPicker) finish() else status.text = "Не удалось открыть выбор файла: ${t.message}"
        }
    }

    @Deprecated("Using platform document picker for compatibility with the target app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL) return
        if (resultCode != RESULT_OK || data?.data == null) {
            Log.i(TAG, "Document picker cancelled or returned no URI")
            if (autoPicker) finish()
            return
        }
        val uri = data.data!!
        val grantedFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        Log.i(TAG, "Document selected: $uri; readGrant=$grantedFlags")
        rememberModel(uri, grantedFlags)
    }

    private fun rememberModel(uri: Uri, grantedFlags: Int) {
        if (importing) return
        importing = true
        if (!autoPicker) {
            chooseButton.isEnabled = false
            loadButton.isEnabled = false
            unloadButton.isEnabled = false
            status.text = "Проверяю GGUF на исходном носителе…"
        }

        Thread {
            try {
                if (grantedFlags == 0) error("DocumentsUI не выдал разрешение на чтение файла")
                contentResolver.takePersistableUriPermission(uri, grantedFlags)
                val size = validateSeekableGguf(uri)
                HyMT2Runtime.setSelectedModelUri(applicationContext, uri)
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "GGUF"
                Log.i(TAG, "Model URI stored without copying: name=$name size=$size uri=$uri")

                if (autoPicker) {
                    runOnUiThread {
                        Toast.makeText(this, "GGUF выбран на исходном носителе. Загружаю Hy-MT2…", Toast.LENGTH_LONG).show()
                        HyMT2Runtime.preload(this) { message ->
                            Log.i(TAG, "Auto-picker model preload result: $message")
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                } else {
                    runOnUiThread {
                        status.text = "GGUF выбран: $name (${formatBytes(size)}). Файл остаётся на исходном носителе."
                        refreshState()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SAF model selection failed", t)
                runOnUiThread {
                    if (autoPicker) {
                        Toast.makeText(this, "Не удалось открыть модель: ${t.message}", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        status.text = "Ошибка выбора модели: ${t.message ?: t.javaClass.simpleName}"
                    }
                }
            } finally {
                runOnUiThread {
                    importing = false
                    if (!autoPicker && ::chooseButton.isInitialized) {
                        chooseButton.isEnabled = !HyMT2Runtime.isLoaded()
                        refreshState()
                    }
                }
            }
        }.start()
    }

    /** Require a true regular/seekable file: llama.cpp needs random access and mmap. */
    private fun validateSeekableGguf(uri: Uri): Long {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: error("SAF не смог открыть выбранный файл")
        pfd.use { descriptor ->
            val stat = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                error("Провайдер вернул поток, а не обычный файл с произвольным доступом. Выбери файл через раздел SD-карты в системном проводнике.")
            }
            if (stat.st_size < MIN_MODEL_BYTES) {
                error("Файл слишком мал для Hy-MT2: ${formatBytes(stat.st_size)}")
            }
            val magic = ByteArray(4)
            val count = Os.pread(descriptor.fileDescriptor, magic, 0, 4, 0L)
            if (count != 4 || magic.toString(Charsets.US_ASCII) != "GGUF") {
                error("Это не GGUF-файл: сигнатура GGUF не найдена")
            }

            // The llama.cpp AAR takes a pathname, not a SAF URI or a FileDescriptor.
            // Verify the proc-fd pathname now so an incompatible provider is rejected
            // before we save the model selection or start translation.
            val procFdPath = "/proc/self/fd/${descriptor.fd}"
            val probe = Os.open(procFdPath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                val probeStat = Os.fstat(probe)
                if (!OsConstants.S_ISREG(probeStat.st_mode) || probeStat.st_size != stat.st_size) {
                    error("SAF-дескриптор нельзя повторно открыть как обычный файл через /proc/self/fd")
                }
                val probeMagic = ByteArray(4)
                val probeCount = Os.pread(probe, probeMagic, 0, 4, 0L)
                if (probeCount != 4 || probeMagic.toString(Charsets.US_ASCII) != "GGUF") {
                    error("Не удалось повторно прочитать GGUF через /proc/self/fd")
                }
            } finally {
                Os.close(probe)
            }
            Log.i(TAG, "Direct SAF path probe passed: $procFdPath; no-copy mode available")
            return stat.st_size
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun loadModel() {
        if (!HyMT2Runtime.hasModel(this)) {
            status.text = "Сначала выбери GGUF-модель на SD-карте."
            return
        }
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        unloadButton.isEnabled = false
        status.text = "Загружаю модель непосредственно с исходного носителя в RAM…"
        HyMT2Runtime.preload(this) { message ->
            status.text = message
            refreshState()
        }
    }

    private fun unloadModel() {
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        unloadButton.isEnabled = false
        status.text = "Освобождаю память…"
        HyMT2Runtime.unload { message ->
            status.text = message
            refreshState()
        }
    }

    private fun refreshState() {
        val uri = HyMT2Runtime.selectedModelUri(this)
        val legacy = HyMT2Runtime.modelFile(this)
        modelInfo.text = when {
            uri != null -> {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "GGUF"
                "Модель: $name\nИсточник: выбранный SAF URI (SD/внешний носитель)\nКопирование: отключено\nСостояние: ${if (HyMT2Runtime.isLoaded()) "загружена в RAM" else "выбрана, готова к загрузке"}"
            }
            legacy.isFile && legacy.length() >= MIN_MODEL_BYTES -> {
                "Найдена старая копия модели: ${legacy.name}\nРазмер: ${formatBytes(legacy.length())}\nЭта версия больше не использует старые копии. Выбери исходный GGUF через системный проводник. После успешной загрузки с SD старую копию можно удалить вручную."
            }
            else -> "Модель ещё не выбрана. Нужен GGUF-файл Hy-MT2-1.8B-Q4_K_M.gguf."
        }
        chooseButton.isEnabled = !importing && !HyMT2Runtime.isLoaded()
        loadButton.isEnabled = !importing && HyMT2Runtime.hasModel(this) && !HyMT2Runtime.isLoaded()
        unloadButton.isEnabled = HyMT2Runtime.isLoaded()
    }

    override fun onDestroy() {
        super.onDestroy()
        HyMT2Launcher.resetPickerLaunch()
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f ГБ", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> String.format(Locale.US, "%.0f МБ", value / (1024.0 * 1024.0))
        else -> "$value Б"
    }

    companion object {
        private const val TAG = "TapTranslateHyMT2"
        private const val REQUEST_MODEL = 7412
        private const val EXTRA_AUTO_PICK = "hymt2_auto_pick"
        private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
    }
}
