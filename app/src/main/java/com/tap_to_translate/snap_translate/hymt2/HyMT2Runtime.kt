package com.tap_to_translate.snap_translate.hymt2

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.View
import android.widget.Toast
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Runtime bridge called from the original app's translation dispatcher (yh.f). */
object HyMT2Runtime {
    private const val TAG = "TapTranslateHyMT2"
    private const val MODEL_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
    private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
    private const val PREFS = "hymt2_model_selection"
    private const val KEY_MODEL_URI = "model_uri"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelMutex = Mutex()
    // llama.cpp exposes one context in the free AAR, so generations are serialized.
    private val inferenceMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fallbackNoticeShown = AtomicBoolean(false)

    @Volatile private var loadedModel: LlamaModel? = null
    @Volatile private var loadedSource: String? = null
    // Keep the SAF descriptor alive while llama.cpp has the model open/mapped.
    @Volatile private var loadedDescriptor: ParcelFileDescriptor? = null

    /** Path of the old copied model, used only to display its location for manual cleanup. */
    @JvmStatic
    fun modelFile(context: Context): File {
        val base = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        return File(base, MODEL_NAME)
    }

    @JvmStatic
    fun selectedModelUri(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_URI, null)
        return value?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    @JvmStatic
    fun setSelectedModelUri(context: Context, uri: Uri) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL_URI, uri.toString()).apply()
        Log.i(TAG, "Persisted SAF model URI: $uri")
    }

    @JvmStatic
    fun hasModel(context: Context): Boolean {
        // New builds only accept a persisted SAF URI. Legacy copied models are intentionally
        // ignored so an old 1.1-GB duplicate is never used silently.
        return selectedModelUri(context) != null
    }

    @JvmStatic
    fun isLoaded(): Boolean = loadedModel != null

    @JvmStatic
    fun preload(context: Context, callback: (String) -> Unit) {
        val app = context.applicationContext
        try {
            scope.launch {
                val message = try {
                    ensureLoaded(app)
                    "Hy-MT2 загружена в память. Исходный GGUF остаётся на выбранном носителе."
                } catch (t: Throwable) {
                    Log.e(TAG, "Model preload failed", t)
                    "Ошибка загрузки: ${t.message ?: t.javaClass.simpleName}"
                }
                mainHandler.post { callback(message) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to schedule model preload", t)
            mainHandler.post { callback("Не удалось запустить загрузчик Hy-MT2: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    @JvmStatic
    fun unload(callback: (String) -> Unit) {
        try {
            scope.launch {
                val message = try {
                    inferenceMutex.withLock {
                        modelMutex.withLock {
                            loadedModel?.let { Llama.releaseModel(it) }
                            loadedModel = null
                            loadedSource = null
                            loadedDescriptor?.close()
                            loadedDescriptor = null
                        }
                    }
                    "Модель выгружена из памяти. GGUF-файл остался на исходном носителе."
                } catch (t: Throwable) {
                    Log.e(TAG, "Model unload failed", t)
                    "Ошибка выгрузки: ${t.message ?: t.javaClass.simpleName}"
                }
                mainHandler.post { callback(message) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to schedule model unload", t)
            mainHandler.post { callback("Не удалось запустить выгрузку: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    /**
     * Called by smali as: translate(yh.f instance, text, sourceCode, targetCode).
     * Reflection keeps this integration independent from the obfuscated app classes.
     */
    @JvmStatic
    fun translate(host: Any, sourceText: String, sourceCode: String, targetCode: String) {
        val context = resolveContext(host)
        if (context == null) {
            fallbackToGoogleOffline(host, sourceText)
            return
        }
        val appContext = context.applicationContext
        if (!hasModel(appContext)) {
            HyMT2Launcher.openIfModelMissing(appContext)
            showFallbackNotice(appContext, "Файл Hy-MT2 не выбран. Открываю выбор GGUF; этот фрагмент переведёт Google Offline Translate.")
            fallbackToGoogleOffline(host, sourceText)
            return
        }

        try {
            scope.launch {
                try {
                    val result = inferenceMutex.withLock {
                        val model = ensureLoaded(appContext)
                        val sourceLanguage = languageName(sourceCode)
                        val targetLanguage = languageName(targetCode) ?: "Russian"
                        val sourceClause = sourceLanguage?.let { " from $it" } ?: ""
                        val prompt = "Translate the following text$sourceClause into $targetLanguage. " +
                            "Preserve every line break, numbering, punctuation, names, tone, humor and sarcasm. " +
                            "Do not omit or merge lines. Output only the translation, with no explanations.\n\n" + sourceText

                        Llama.complete(
                            model = model,
                            prompt = prompt,
                            systemPrompt = "You are a professional comic and manga translator. Produce natural, idiomatic dialogue in the requested target language. Preserve character voice, jokes, irony, wordplay and formatting. Never add commentary or translator notes.",
                            maxTokens = 768,
                        ).text.trim()
                    }

                    if (result.isBlank()) error("The model returned an empty translation")
                    val inputLineCount = sourceText.replace("\r\n", "\n").split('\n').size
                    val outputLineCount = result.replace("\r\n", "\n").split('\n').size
                    if (inputLineCount > 1 && inputLineCount != outputLineCount) {
                        error("Hy-MT2 changed line count ($inputLineCount → $outputLineCount)")
                    }
                    mainHandler.post { deliverResult(host, result, sourceText) }
                } catch (t: Throwable) {
                    Log.e(TAG, "Hy-MT2 translation failed; falling back to ML Kit", t)
                    mainHandler.post {
                        showFallbackNotice(appContext, "Ошибка Hy-MT2. Для этого фрагмента используется Google Offline Translate.")
                        fallbackToGoogleOffline(host, sourceText)
                    }
                }
            }
        } catch (t: Throwable) {
            // A broken/missing coroutine dependency must never crash the host translation service.
            Log.e(TAG, "Unable to schedule Hy-MT2 translation; falling back to ML Kit", t)
            showFallbackNotice(appContext, "Не удалось запустить Hy-MT2. Используется Google Offline Translate.")
            fallbackToGoogleOffline(host, sourceText)
        }
    }

    private suspend fun ensureLoaded(context: Context): LlamaModel = modelMutex.withLock {
        val selectedUri = selectedModelUri(context)
            ?: error("GGUF-файл Hy-MT2 не выбран. Выбери исходную модель через системный проводник.")
        val sourceKey = selectedUri.toString()

        val existing = loadedModel
        if (existing != null && loadedSource == sourceKey) return@withLock existing
        if (existing != null) {
            Llama.releaseModel(existing)
            loadedModel = null
            loadedSource = null
            loadedDescriptor?.close()
            loadedDescriptor = null
        }

        var descriptor: ParcelFileDescriptor? = null
        try {
            Log.i(TAG, "Opening selected model through SAF without copying: $selectedUri")
            descriptor = context.contentResolver.openFileDescriptor(selectedUri, "r")
                ?: error("SAF не смог открыть выбранный GGUF")
            val stat = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                error("Провайдер хранилища не предоставил обычный файл с произвольным доступом. Выбери файл через системный раздел SD-карты.")
            }
            if (stat.st_size < MIN_MODEL_BYTES) {
                error("Размер модели слишком мал: ${stat.st_size} байт")
            }
            verifyGgufHeader(descriptor)
            val fdNumber = descriptor.fd
            val modelPath = "/proc/self/fd/$fdNumber"
            verifyProcFdPath(modelPath, stat.st_size)
            Log.i(TAG, "SAF descriptor ready and reopenable: fd=$fdNumber, size=${stat.st_size}, path=$modelPath; no model data copied")

            val threads = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
            val created = Llama.loadModel(
                modelPath = modelPath,
                config = LlamaConfig(
                    contextSize = 2048,
                    threads = threads,
                    gpuLayers = 0,
                    temperature = 0.7f,
                    topP = 0.6f,
                    topK = 20,
                ),
            )
            loadedSource = sourceKey
            loadedModel = created
            loadedDescriptor = descriptor
            descriptor = null // Ownership moved to loadedDescriptor.
            Log.i(TAG, "Hy-MT2 loaded from $sourceKey; CPU threads=$threads")
            created
        } finally {
            descriptor?.close()
        }
    }

    /**
     * llama-android accepts a filesystem path rather than a ContentResolver URI or fd.
     * Probe the proc-fd path with the same pathname-open mechanism the native loader uses.
     * Keep the original SAF ParcelFileDescriptor open for the model's entire lifetime.
     */
    private fun verifyProcFdPath(path: String, expectedSize: Long) {
        val probe = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            val stat = Os.fstat(probe)
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                error("/proc/self/fd не указывает на обычный файл")
            }
            if (stat.st_size != expectedSize) {
                error("Размер файла через /proc/self/fd не совпал с исходным дескриптором")
            }
            val magic = ByteArray(4)
            val read = Os.pread(probe, magic, 0, magic.size, 0L)
            if (read != 4 || magic.toString(Charsets.US_ASCII) != "GGUF") {
                error("Не удалось повторно прочитать сигнатуру GGUF через /proc/self/fd")
            }
        } finally {
            Os.close(probe)
        }
    }

    private fun verifyGgufHeader(pfd: ParcelFileDescriptor) {
        val magic = ByteArray(4)
        val read = Os.pread(pfd.fileDescriptor, magic, 0, magic.size, 0L)
        if (read != 4 || magic.toString(Charsets.US_ASCII) != "GGUF") {
            error("Это не GGUF-файл: сигнатура GGUF не найдена")
        }
    }


    private fun resolveContext(host: Any): Context? {
        return try {
            val callback = host.javaClass.getField("a").get(host) ?: return null
            when (callback) {
                is Context -> callback
                is View -> callback.context
                else -> {
                    val getter = callback.javaClass.methods.firstOrNull {
                        it.name == "getContext" && it.parameterCount == 0
                    }
                    getter?.invoke(callback) as? Context
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot resolve host context", t)
            null
        }
    }

    private fun deliverResult(host: Any, translated: String, originalSource: String) {
        try {
            val cls = host.javaClass
            cls.getField("d").set(host, translated)
            val usePrimary = cls.getField("b").getBoolean(host)
            val callback = cls.getField("a").get(host)
            val method = if (usePrimary) "a" else "f"
            val targetMethod = callback.javaClass.getMethod(method, String::class.java)
            targetMethod.isAccessible = true
            targetMethod.invoke(callback, translated)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot deliver Hy-MT2 result to app callback", t)
            fallbackToGoogleOffline(host, originalSource)
        }
    }

    private fun fallbackToGoogleOffline(host: Any, sourceText: String) {
        try {
            val method = host.javaClass.getMethod("e", String::class.java)
            method.isAccessible = true
            method.invoke(host, sourceText)
        } catch (t: Throwable) {
            Log.e(TAG, "Google Offline Translate fallback failed", t)
            resolveContext(host)?.let {
                mainHandler.post {
                    Toast.makeText(it, "Не удалось выполнить перевод Hy-MT2 и резервным движком.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showFallbackNotice(context: Context, message: String) {
        if (fallbackNoticeShown.compareAndSet(false, true)) {
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun languageName(value: String): String? {
        val normalized = value.trim().replace('_', '-')
        if (normalized.isEmpty() || normalized.equals("und", true) || normalized.startsWith("auto", true)) return null
        if (normalized.length > 3 || normalized.contains(' ')) return normalized
        return try {
            val name = Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.ENGLISH)
            if (name.isBlank() || name.equals("und", true)) null else name
        } catch (_: Throwable) {
            null
        }
    }
}
