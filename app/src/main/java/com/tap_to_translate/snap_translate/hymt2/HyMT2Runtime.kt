package com.tap_to_translate.snap_translate.hymt2

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelMutex = Mutex()
    // llama.cpp model contexts are not shared between simultaneous generations.
    private val inferenceMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fallbackNoticeShown = AtomicBoolean(false)

    @Volatile private var loadedModel: LlamaModel? = null
    @Volatile private var loadedPath: String? = null

    @JvmStatic
    fun modelFile(context: Context): File {
        val base = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        return File(base, MODEL_NAME)
    }

    @JvmStatic
    fun hasModel(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_MODEL_BYTES
    }

    @JvmStatic
    fun isLoaded(): Boolean = loadedModel != null

    @JvmStatic
    fun preload(context: Context, callback: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val message = try {
                ensureLoaded(app)
                "Hy-MT2 загружена в память. Можно выбирать Hy-MT2 Offline в списке движков."
            } catch (t: Throwable) {
                Log.e(TAG, "Model preload failed", t)
                "Ошибка загрузки: ${t.message ?: t.javaClass.simpleName}"
            }
            mainHandler.post { callback(message) }
        }
    }

    @JvmStatic
    fun unload(callback: (String) -> Unit) {
        scope.launch {
            val message = try {
                inferenceMutex.withLock {
                    modelMutex.withLock {
                        loadedModel?.let { Llama.releaseModel(it) }
                        loadedModel = null
                        loadedPath = null
                    }
                }
                "Модель выгружена из памяти. Файл GGUF сохранён."
            } catch (t: Throwable) {
                Log.e(TAG, "Model unload failed", t)
                "Ошибка выгрузки: ${t.message ?: t.javaClass.simpleName}"
            }
            mainHandler.post { callback(message) }
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
            showFallbackNotice(appContext, "Файл Hy-MT2 не выбран. Используется Google Offline Translate.")
            fallbackToGoogleOffline(host, sourceText)
            return
        }

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
    }

    private suspend fun ensureLoaded(context: Context): LlamaModel = modelMutex.withLock {
        val file = modelFile(context)
        if (!file.isFile || file.length() < MIN_MODEL_BYTES) {
            error("GGUF-файл Hy-MT2 не найден или повреждён")
        }
        val existing = loadedModel
        if (existing != null && loadedPath == file.absolutePath) return@withLock existing
        if (existing != null) {
            Llama.releaseModel(existing)
            loadedModel = null
            loadedPath = null
        }
        val threads = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
        val created = Llama.loadModel(
            modelPath = file.absolutePath,
            config = LlamaConfig(
                contextSize = 2048,
                threads = threads,
                gpuLayers = 0,
                temperature = 0.7f,
                topP = 0.6f,
                topK = 20,
            ),
        )
        loadedPath = file.absolutePath
        loadedModel = created
        Log.i(TAG, "Hy-MT2 loaded: ${file.length()} bytes; CPU threads=$threads")
        created
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
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // The original app stores human-readable language names (e.g. "English"),
    // not only BCP-47 codes. Keep those labels intact for the model prompt.
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
