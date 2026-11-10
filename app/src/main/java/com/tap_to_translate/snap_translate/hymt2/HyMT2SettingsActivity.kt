package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.content.Intent
import android.widget.AdapterView
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Model manager and independent settings panel for the Hy-MT2 translation engine. */
class HyMT2SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var modelInfo: TextView
    private lateinit var statsInfo: TextView
    private lateinit var chooseButton: Button
    private lateinit var loadButton: Button
    private lateinit var unloadButton: Button

    private lateinit var profileSpinner: Spinner
    private lateinit var threadsSpinner: Spinner
    private lateinit var gpuLayerSpinner: Spinner
    private lateinit var contextSpinner: Spinner
    private lateinit var maxTokensSpinner: Spinner
    private lateinit var historySpinner: Spinner
    private lateinit var styleSpinner: Spinner
    private lateinit var temperatureInput: EditText
    private lateinit var topPInput: EditText
    private lateinit var topKInput: EditText
    private lateinit var repetitionPenaltyInput: EditText
    private lateinit var backgroundInput: EditText
    private lateinit var glossaryInput: EditText
    private lateinit var customStyleInput: EditText
    private lateinit var recentContextSwitch: Switch
    private lateinit var vulkanSwitch: Switch

    private var autoPicker = false
    private var pickerLaunched = false
    private var importing = false
    private var bindingControls = false
    private var lastProfileSelection: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoPicker = intent.getBooleanExtra(EXTRA_AUTO_PICK, false)
        Log.i(TAG, "HyMT2SettingsActivity onCreate; autoPicker=$autoPicker")
        if (!autoPicker) {
            buildScreen()
            loadSettingsIntoControls()
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
        setContentView(ScrollView(this).apply { addView(root) })

        addText(root, "Hy-MT2 Offline", 24f, true)
        addText(
            root,
            "Локальный перевод на устройстве. GGUF читается непосредственно с выбранного носителя через SAF; дополнительная копия модели не создаётся.",
            14f,
        )

        addSection(root, "МОДЕЛЬ")
        modelInfo = addText(root, "", 13f)
        chooseButton = addButton(root, "1. Выбрать GGUF на SD-карте") { launchPicker() }
        loadButton = addButton(root, "2. Загрузить модель в RAM") { loadModel() }
        unloadButton = addButton(root, "Освободить память") { unloadModel() }

        addSection(root, "ПРОИЗВОДИТЕЛЬНОСТЬ")
        addText(root, "Эти параметры влияют на скорость, расход RAM и длину доступного контекста. Изменение параметров загрузки потребует перезагрузить модель. Для ручной настройки сначала выбери профиль «Настраиваемый».", 13f)
        profileSpinner = addSpinner(root, "Профиль производительности", HyMT2Settings.profileChoices)
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingControls) return
                val selected = HyMT2Settings.profileChoices[position]
                if (selected != lastProfileSelection) {
                    lastProfileSelection = selected
                    applyPerformanceProfile(selected)
                }
            }
        }
        threadsSpinner = addSpinner(root, "Потоки CPU", HyMT2Settings.threadChoices.map { it.toString() })
        gpuLayerSpinner = addSpinner(root, "Слои Vulkan на GPU", HyMT2Settings.gpuLayerChoices.map { if (it == 0) "0 · CPU only" else it.toString() })
        contextSpinner = addSpinner(root, "Размер контекста (токены)", HyMT2Settings.contextChoices.map { it.toString() })
        maxTokensSpinner = addSpinner(root, "Максимум выходных токенов", HyMT2Settings.maxTokenChoices.map { it.toString() })
        temperatureInput = addEdit(root, "Temperature (0–2)", "0.7", decimal = true)
        topPInput = addEdit(root, "Top-P (0,05–1)", "0.6", decimal = true)
        topKInput = addEdit(root, "Top-K (1–100)", "20", decimal = false)
        repetitionPenaltyInput = addEdit(root, "Repetition penalty (1–2)", "1.05", decimal = true)

        addSection(root, "КОНТЕКСТ ПЕРЕВОДА")
        addText(root, "Фоновое описание мира, персонажей и ситуации. Hy-MT2 будет учитывать его, но не переводить.", 13f)
        backgroundInput = addEdit(root, "Фоновый контекст", "", multiline = true)
        backgroundInput.minLines = 3
        backgroundInput.maxLines = 6

        recentContextSwitch = addSwitch(root, "Учитывать предыдущие переведённые реплики", true)
        historySpinner = addSpinner(root, "Сколько предыдущих пар Source → Translation", HyMT2Settings.historyChoices.map { it.toString() })
        addText(root, "История действует только в оперативной памяти, привязана к текущему объекту перевода и автоматически очищается после 3 минут бездействия.", 12f)

        addText(root, "Словарь терминов (по одной паре на строку: оригинал = перевод)", 13f)
        glossaryInput = addEdit(root, "Например: Hell's Kitchen = Адская кухня", "", multiline = true)
        glossaryInput.minLines = 3
        glossaryInput.maxLines = 7

        addSection(root, "СТИЛЬ ПЕРЕВОДА")
        styleSpinner = addSpinner(root, "Профиль стиля", HyMT2Settings.styleChoices)
        customStyleInput = addEdit(root, "Дополнительные пожелания к стилю", "", multiline = true)
        customStyleInput.minLines = 2
        customStyleInput.maxLines = 5

        addSection(root, "УСКОРЕНИЕ GPU")
        vulkanSwitch = addSwitch(root, "Использовать Vulkan на Adreno 610", true)
        vulkanSwitch.isEnabled = HyMT2Settings.VULKAN_BACKEND_COMPILED
        addText(
            root,
            "Проверенный профиль для Lenovo Xiaoxin Pad 2022: Vulkan · 8 GPU layers + 6 CPU threads. Отключение Vulkan использует CPU/NEON; число GPU-слоёв при этом не применяется.",
            12f,
        )

        addSection(root, "СКОРОСТЬ И ДИАГНОСТИКА")
        statsInfo = addText(root, "Переводов пока нет.", 13f)
        addButton(root, "Очистить контекст предыдущих реплик") {
            HyMT2Runtime.clearRecentContext()
            status.text = "Контекст предыдущих реплик очищен."
        }
        addButton(root, "Сохранить настройки Hy-MT2") { saveSettings() }

        status = addText(root, "Ожидание.", 13f)
        addText(
            root,
            "Встроенного таймера, который прерывает локальную генерацию, нет. Лимит выходных токенов регулируется выше. Слишком большой context size и max tokens увеличивают время перевода и расход памяти.",
            12f,
        )
    }

    private fun loadSettingsIntoControls() {
        val options = HyMT2Settings.load(this)
        bindingControls = true
        lastProfileSelection = options.performanceProfile
        selectSpinner(profileSpinner, HyMT2Settings.profileChoices.indexOf(options.performanceProfile))
        selectSpinner(threadsSpinner, HyMT2Settings.threadChoices.indexOf(options.threads))
        selectSpinner(gpuLayerSpinner, HyMT2Settings.gpuLayerChoices.indexOf(options.gpuLayers))
        selectSpinner(contextSpinner, HyMT2Settings.contextChoices.indexOf(options.contextSize))
        selectSpinner(maxTokensSpinner, HyMT2Settings.maxTokenChoices.indexOf(options.maxTokens))
        selectSpinner(historySpinner, HyMT2Settings.historyChoices.indexOf(options.recentContextPairs))
        selectSpinner(styleSpinner, HyMT2Settings.styleChoices.indexOf(options.stylePreset))
        temperatureInput.setText(options.temperature.toString())
        topPInput.setText(options.topP.toString())
        topKInput.setText(options.topK.toString())
        repetitionPenaltyInput.setText(options.repetitionPenalty.toString())
        recentContextSwitch.isChecked = options.useRecentContext
        backgroundInput.setText(options.backgroundContext)
        glossaryInput.setText(options.glossary)
        customStyleInput.setText(options.customStyle)
        vulkanSwitch.isChecked = options.useVulkan
        bindingControls = false
        updateStats()
    }

    private fun saveSettings() {
        try {
            val old = HyMT2Settings.load(this)
            val threads = selectedInt(threadsSpinner, HyMT2Settings.threadChoices)
            val gpuLayers = selectedInt(gpuLayerSpinner, HyMT2Settings.gpuLayerChoices)
            val contextSize = selectedInt(contextSpinner, HyMT2Settings.contextChoices)
            val maxTokens = selectedInt(maxTokensSpinner, HyMT2Settings.maxTokenChoices)
            val historyCount = selectedInt(historySpinner, HyMT2Settings.historyChoices)
            val temperature = temperatureInput.text.toString().trim().replace(',', '.').toFloat()
            val topP = topPInput.text.toString().trim().replace(',', '.').toFloat()
            val topK = topKInput.text.toString().trim().toInt()
            val repetitionPenalty = repetitionPenaltyInput.text.toString().trim().replace(',', '.').toFloat()
            val style = styleSpinner.selectedItem?.toString() ?: HyMT2Settings.styleChoices.first()
            val options = HyMT2Options(
                threads = threads,
                contextSize = contextSize,
                maxTokens = maxTokens,
                gpuLayers = gpuLayers,
                repetitionPenalty = repetitionPenalty,
                temperature = temperature,
                topP = topP,
                topK = topK,
                useRecentContext = recentContextSwitch.isChecked,
                recentContextPairs = historyCount,
                backgroundContext = backgroundInput.text.toString(),
                glossary = glossaryInput.text.toString(),
                stylePreset = style,
                customStyle = customStyleInput.text.toString(),
                useVulkan = vulkanSwitch.isChecked,
                performanceProfile = profileSpinner.selectedItem?.toString() ?: HyMT2Settings.profileChoices.last(),
            )
            HyMT2Settings.save(this, options)
            val requiresReload = old.threads != options.threads ||
                old.contextSize != options.contextSize ||
                old.useVulkan != options.useVulkan || old.gpuLayers != options.gpuLayers ||
                old.temperature != options.temperature || old.topP != options.topP ||
                old.topK != options.topK || old.repetitionPenalty != options.repetitionPenalty

            if (HyMT2Runtime.isLoaded() && requiresReload) {
                status.text = "Настройки сохранены. Выгружаю модель, чтобы применить параметры загрузки…"
                HyMT2Runtime.unload { message ->
                    status.text = "Настройки сохранены. $message Теперь загрузи модель заново."
                    refreshState()
                }
            } else {
                status.text = if (requiresReload) {
                    "Настройки сохранены. При следующей загрузке модели будут применены новые параметры."
                } else {
                    "Настройки сохранены и будут применяться к следующим переводам."
                }
            }
            updateStats()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not save Hy-MT2 settings", t)
            status.text = "Проверь параметры: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun applyPerformanceProfile(profile: String) {
        when (profile) {
            HyMT2Settings.profileChoices[0] -> {
                selectSpinner(threadsSpinner, HyMT2Settings.threadChoices.indexOf(6))
                selectSpinner(gpuLayerSpinner, HyMT2Settings.gpuLayerChoices.indexOf(8))
                selectSpinner(contextSpinner, HyMT2Settings.contextChoices.indexOf(1024))
                selectSpinner(maxTokensSpinner, HyMT2Settings.maxTokenChoices.indexOf(256))
                temperatureInput.setText("0.5")
                topPInput.setText("0.6")
                topKInput.setText("20")
                selectSpinner(historySpinner, HyMT2Settings.historyChoices.indexOf(1))
            }
            HyMT2Settings.profileChoices[1] -> {
                selectSpinner(threadsSpinner, HyMT2Settings.threadChoices.indexOf(6))
                selectSpinner(gpuLayerSpinner, HyMT2Settings.gpuLayerChoices.indexOf(8))
                selectSpinner(contextSpinner, HyMT2Settings.contextChoices.indexOf(2048))
                selectSpinner(maxTokensSpinner, HyMT2Settings.maxTokenChoices.indexOf(768))
                temperatureInput.setText("0.7")
                topPInput.setText("0.6")
                topKInput.setText("20")
                selectSpinner(historySpinner, HyMT2Settings.historyChoices.indexOf(2))
            }
            HyMT2Settings.profileChoices[2] -> {
                selectSpinner(threadsSpinner, HyMT2Settings.threadChoices.indexOf(6))
                selectSpinner(gpuLayerSpinner, HyMT2Settings.gpuLayerChoices.indexOf(8))
                selectSpinner(contextSpinner, HyMT2Settings.contextChoices.indexOf(4096))
                selectSpinner(maxTokensSpinner, HyMT2Settings.maxTokenChoices.indexOf(1024))
                temperatureInput.setText("0.7")
                topPInput.setText("0.6")
                topKInput.setText("20")
                selectSpinner(historySpinner, HyMT2Settings.historyChoices.indexOf(3))
            }
            // Custom keeps all current values and allows direct editing of each parameter.
        }
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
                "Модель: $name\nИсточник: SAF URI (SD/внешний носитель)\nКопирование: отключено\nСостояние: ${if (HyMT2Runtime.isLoaded()) "загружена в RAM" else "выбрана, готова к загрузке"}"
            }
            legacy.isFile && legacy.length() >= MIN_MODEL_BYTES -> {
                "Найдена старая копия модели: ${legacy.name}\nРазмер: ${formatBytes(legacy.length())}\nЭта версия не использует старые копии. Выбери исходный GGUF через проводник."
            }
            else -> "Модель ещё не выбрана. Нужен GGUF-файл Hy-MT2-1.8B-Q4_K_M.gguf."
        }
        chooseButton.isEnabled = !importing && !HyMT2Runtime.isLoaded()
        loadButton.isEnabled = !importing && HyMT2Runtime.hasModel(this) && !HyMT2Runtime.isLoaded()
        unloadButton.isEnabled = HyMT2Runtime.isLoaded()
        updateStats()
    }

    private fun updateStats() {
        if (!::statsInfo.isInitialized) return
        val stats = HyMT2Settings.stats(this)
        statsInfo.text = if (stats.translationsCount == 0) {
            "Переводов пока нет. Скорость будет измеряться автоматически при каждом переводе."
        } else {
            "Последний перевод: ${String.format(Locale.US, "%.2f", stats.lastTokensPerSecond)} ток/с; ${formatDuration(stats.lastDurationMs)}; ${stats.lastOutputChars} символов.\n" +
                "Средняя скорость последних ${stats.translationsCount.coerceAtMost(10)} измерений: ${String.format(Locale.US, "%.2f", stats.averageTokensPerSecond)} ток/с."
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms >= 60_000 -> String.format(Locale.US, "%.1f мин", ms / 60_000.0)
        else -> String.format(Locale.US, "%.1f с", ms / 1000.0)
    }

    private fun addSection(root: LinearLayout, title: String) {
        val view = TextView(this).apply {
            text = title
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(7))
        }
        root.addView(view, match())
    }

    private fun addText(root: LinearLayout, value: String, size: Float, bold: Boolean = false): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(7))
        }
        root.addView(view, match())
        return view
    }

    private fun addSpinner(root: LinearLayout, title: String, values: List<String>): Spinner {
        addText(root, title, 13f)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        root.addView(spinner, match())
        return spinner
    }

    private fun addEdit(root: LinearLayout, title: String, hint: String, decimal: Boolean = false, multiline: Boolean = false): EditText {
        addText(root, title, 13f)
        val field = EditText(this).apply {
            this.hint = hint
            textSize = 14f
            setPadding(dp(9), dp(7), dp(9), dp(7))
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            if (multiline) {
                gravity = Gravity.TOP or Gravity.START
                setSingleLine(false)
            } else {
                setSingleLine(true)
            }
        }
        root.addView(field, match())
        return field
    }

    private fun addSwitch(root: LinearLayout, title: String, defaultValue: Boolean): Switch {
        val control = Switch(this).apply {
            text = title
            isChecked = defaultValue
            textSize = 14f
            setPadding(0, dp(7), 0, dp(7))
        }
        root.addView(control, match())
        return control
    }

    private fun addButton(root: LinearLayout, title: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = title
            setOnClickListener { action() }
        }
        root.addView(button, match())
        return button
    }

    private fun selectedInt(spinner: Spinner, values: List<Int>): Int {
        val index = spinner.selectedItemPosition.coerceIn(0, values.lastIndex)
        return values[index]
    }

    private fun selectSpinner(spinner: Spinner, index: Int) {
        val count = spinner.adapter?.count ?: 0
        if (count > 0) spinner.setSelection(index.coerceIn(0, count - 1))
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(7) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        HyMT2Launcher.resetPickerLaunch()
    }

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
