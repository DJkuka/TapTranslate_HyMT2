package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class HyMT2SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var modelInfo: TextView
    private lateinit var chooseButton: Button
    private lateinit var loadButton: Button
    private lateinit var unloadButton: Button
    private var copying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshState()
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
            text = "Локальный перевод на устройстве. Модель не входит в APK и выбирается отдельно в формате GGUF."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad)
        }, match())

        modelInfo = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(modelInfo, match())

        chooseButton = Button(this).apply {
            text = "1. Выбрать GGUF-модель"
            setOnClickListener { chooseModel() }
        }
        root.addView(chooseButton, match())

        loadButton = Button(this).apply {
            text = "2. Загрузить модель в память"
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
            text = "Для первого запуска выбери Hy-MT2-1.8B-Q4_K_M.gguf (около 1,13 ГБ). Файл будет скопирован в приватную папку приложения. Оставь дополнительно не менее 1,3 ГБ свободного места. Загрузка модели требует свободной оперативной памяти; на время перевода не закрывай приложение."
            textSize = 13f
            setPadding(0, pad, 0, pad)
        }, match())
    }

    private fun chooseModel() {
        if (copying) return
        if (HyMT2Runtime.isLoaded()) {
            Toast.makeText(this, "Сначала выгрузи модель из памяти.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_MODEL)
    }

    @Deprecated("Using platform document picker for compatibility with the target app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MODEL && resultCode == RESULT_OK) {
            data?.data?.let(::importModel)
        }
    }

    private fun importModel(uri: Uri) {
        if (copying) return
        copying = true
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        unloadButton.isEnabled = false
        status.text = "Проверяю выбранный файл…"

        Thread {
            var pendingTemp: File? = null
            try {
                val folder = getExternalFilesDir("models") ?: File(filesDir, "models")
                if (!folder.exists() && !folder.mkdirs()) error("Не удалось создать папку моделей")
                val stat = StatFs(folder.absolutePath)
                val free = stat.availableBytes
                val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                val expected = afd?.use { it.length } ?: -1L
                if (expected > 0 && free < expected + 128L * 1024L * 1024L) {
                    error("Недостаточно свободного места: нужно около ${formatBytes(expected + 128L * 1024L * 1024L)}.")
                }

                val destination = HyMT2Runtime.modelFile(this)
                val temp = File(folder, destination.name + ".part")
                pendingTemp = temp
                val backup = File(folder, destination.name + ".bak")
                temp.delete()
                contentResolver.openInputStream(uri)?.use { raw ->
                    BufferedInputStream(raw, 1024 * 1024).use { input ->
                        BufferedOutputStream(FileOutputStream(temp), 1024 * 1024).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            var reported = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                total += count
                                if (total - reported >= 64L * 1024L * 1024L) {
                                    reported = total
                                    val progress = "Копирование модели: ${formatBytes(total)}"
                                    runOnUiThread { status.text = progress }
                                }
                            }
                        }
                    }
                } ?: error("Проводник не смог открыть файл")

                if (temp.length() < MIN_MODEL_BYTES) error("Файл слишком мал для Hy-MT2: ${formatBytes(temp.length())}")
                FileInputStream(temp).use { stream ->
                    val magic = ByteArray(4)
                    if (stream.read(magic) != 4 || magic.toString(Charsets.US_ASCII) != "GGUF") {
                        error("Это не GGUF-файл: сигнатура GGUF не найдена")
                    }
                }

                backup.delete()
                if (destination.exists() && !destination.renameTo(backup)) error("Не удалось сохранить предыдущую модель")
                if (!temp.renameTo(destination)) {
                    if (backup.exists()) backup.renameTo(destination)
                    error("Не удалось переместить проверенную модель в папку приложения")
                }
                backup.delete()
                runOnUiThread {
                    status.text = "Модель скопирована и проверена. Теперь нажми «Загрузить модель в память»."
                    refreshState()
                }
            } catch (t: Throwable) {
                pendingTemp?.delete()
                runOnUiThread { status.text = "Ошибка импорта: ${t.message ?: t.javaClass.simpleName}" }
            } finally {
                runOnUiThread {
                    copying = false
                    chooseButton.isEnabled = true
                    loadButton.isEnabled = true
                    unloadButton.isEnabled = HyMT2Runtime.isLoaded()
                    refreshState()
                }
            }
        }.start()
    }

    private fun loadModel() {
        if (!HyMT2Runtime.hasModel(this)) {
            status.text = "Сначала выбери GGUF-модель."
            return
        }
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        unloadButton.isEnabled = false
        status.text = "Загрузка модели в RAM. Первичная загрузка может занять некоторое время…"
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
        val file = HyMT2Runtime.modelFile(this)
        modelInfo.text = if (file.isFile) {
            "Файл модели: ${file.name}\nРазмер: ${formatBytes(file.length())}\nПуть: ${file.absolutePath}\nСостояние: ${if (HyMT2Runtime.isLoaded()) "загружена в RAM" else "сохранена на диске"}"
        } else {
            "Модель ещё не выбрана. Нужен GGUF-файл Hy-MT2-1.8B-Q4_K_M.gguf."
        }
        chooseButton.isEnabled = !copying && !HyMT2Runtime.isLoaded()
        loadButton.isEnabled = !copying && file.isFile && !HyMT2Runtime.isLoaded()
        unloadButton.isEnabled = HyMT2Runtime.isLoaded()
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
        private const val REQUEST_MODEL = 7412
        private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
    }
}
