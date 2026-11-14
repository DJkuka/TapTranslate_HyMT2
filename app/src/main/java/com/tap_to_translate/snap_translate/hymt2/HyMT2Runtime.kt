package com.tap_to_translate.snap_translate.hymt2

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import java.util.ArrayDeque
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Runtime bridge called from the original app's translation dispatcher (yh.f). */
object HyMT2Runtime {
    private const val TAG = "TapTranslateHyMT2"
    private const val MODEL_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
    private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
    private const val PREFS = "hymt2_model_selection"
    private const val KEY_MODEL_URI = "model_uri"
    private const val PERF_TAG = "TapTranslatePerf"
    private val requestCounter = AtomicLong(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelMutex = Mutex()
    // llama.cpp exposes one context in the free AAR, so generations are serialized.
    private val inferenceMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fallbackNoticeShown = AtomicBoolean(false)
    private data class HistoryEntry(
        val sourceLanguage: String,
        val targetLanguage: String,
        val source: String,
        val translated: String,
        val createdAt: Long,
    )
    private val recentHistory = WeakHashMap<Any, ArrayDeque<HistoryEntry>>()

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
        // New builds only accept a URI for which Android still holds a persistable read grant.
        // A stale string in SharedPreferences must not suppress the document picker.
        val app = context.applicationContext
        val uri = selectedModelUri(app) ?: return false
        if (loadedModel != null && loadedSource == uri.toString()) return true

        val hasPersistedReadGrant = try {
            app.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to inspect persisted SAF permissions for $uri", t)
            false
        }
        if (!hasPersistedReadGrant) {
            clearStaleModelUri(app, uri, "no persisted read permission")
            return false
        }

        // A persisted permission record alone is not sufficient: providers can revoke access,
        // removable storage can disappear, or a document can be deleted. Probe the descriptor
        // before advertising this URI as a usable model.
        return try {
            val descriptor = app.contentResolver.openFileDescriptor(uri, "r")
                ?: error("SAF returned no file descriptor")
            descriptor.use {
                val stat = Os.fstat(it.fileDescriptor)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < MIN_MODEL_BYTES) {
                    error("Selected model is not an accessible regular GGUF file")
                }
                val magic = ByteArray(4)
                val read = Os.pread(it.fileDescriptor, magic, 0, magic.size, 0L)
                if (read != 4 || magic.toString(Charsets.US_ASCII) != "GGUF") {
                    error("Selected model is not a readable GGUF file")
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Stored model URI is no longer readable; clearing it so the picker can reopen: $uri", t)
            clearStaleModelUri(app, uri, t.javaClass.simpleName)
            false
        }
    }

    private fun clearStaleModelUri(context: Context, uri: Uri, reason: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MODEL_URI)
            .apply()
        Log.w(TAG, "Cleared stale Hy-MT2 model URI ($reason): $uri")
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

        val requestId = requestCounter.incrementAndGet()
        val requestStartNs = SystemClock.elapsedRealtimeNanos()
        try {
            scope.launch {
                val coroutineStartNs = SystemClock.elapsedRealtimeNanos()
                Log.i(PERF_TAG, "event=hy_request_started request=$requestId coroutine_queue_ms=${(coroutineStartNs - requestStartNs) / 1_000_000L} source_chars=${sourceText.length}")
                try {
                    val inferenceLockStartNs = SystemClock.elapsedRealtimeNanos()
                    val result = inferenceMutex.withLock {
                        val inferenceLockWaitMs = (SystemClock.elapsedRealtimeNanos() - inferenceLockStartNs) / 1_000_000L
                        val modelReadyStartNs = SystemClock.elapsedRealtimeNanos()
                        val model = ensureLoaded(appContext)
                        val modelReadyMs = (SystemClock.elapsedRealtimeNanos() - modelReadyStartNs) / 1_000_000L
                        val options = HyMT2Settings.load(appContext)
                        val sourceLanguage = languageName(sourceCode)
                        val sourceLanguageKey = sourceLanguage ?: "auto"
                        val targetLanguage = languageName(targetCode) ?: "Russian"
                        val systemPrompt = buildTranslationSystemPrompt(sourceLanguage, targetLanguage)
                        Log.i(TAG, "Hy-MT2 request: sourceCode=$sourceCode source=${sourceLanguage ?: "auto"} targetCode=$targetCode target=$targetLanguage sourceChars=${sourceText.length} background=${options.backgroundContext.isNotBlank()} glossary=${options.glossary.isNotBlank()} style=${options.stylePreset}; context=${options.contextSize} maxTokens=${options.maxTokens} temperature=${options.temperature} topP=${options.topP} topK=${options.topK} threads=${options.threads} Vulkan=${options.useVulkan} gpuLayers=${if (options.useVulkan) options.gpuLayers else 0}; generation=Llama.complete")
                        val contextPairs = if (options.useRecentContext && options.recentContextPairs > 0) {
                            recentContextFor(host, sourceLanguageKey, targetLanguage, options.recentContextPairs)
                        } else emptyList()
                        // The host app serializes its OCR list as one text block per line.
                        // ScreenTranslateService.f() splits the returned String on '\\n' and
                        // requires the output line count to equal the original OCR block count.
                        // Translate blocks independently so model-generated line wrapping can
                        // never break that contract.
                        val sourceBlocks = java.util.regex.Pattern.compile("\\n").split(sourceText).toList()
                        if (sourceBlocks.isEmpty()) error("Входной OCR-текст пуст")
                        val translatedBlocks = ArrayList<String>(sourceBlocks.size)
                        val translationStartedAt = SystemClock.elapsedRealtime()
                        val translationWorkStartNs = SystemClock.elapsedRealtimeNanos()
                        val nonBlankBlocks = sourceBlocks.count { it.isNotBlank() }
                        var generatedTokens = 0
                        var generatedTimeMs = 0L
                        var promptTokensTotal = 0
                        var promptEvalTimeTotalMs = 0L
                        var completionWallTotalMs = 0L
                        var promptCountTotalMs = 0L
                        var modelCalls = 0

                        sourceBlocks.forEachIndexed { index, sourceBlock ->
                            if (sourceBlock.isBlank()) {
                                translatedBlocks += ""
                            } else {
                                val cleanSourceBlock = sourceBlock.trim()
                                val promptBuildStartNs = SystemClock.elapsedRealtimeNanos()
                                val prompt = buildTranslationPrompt(
                                    sourceText = cleanSourceBlock,
                                    targetLanguage = targetLanguage,
                                )
                                val promptBuildMs = (SystemClock.elapsedRealtimeNanos() - promptBuildStartNs) / 1_000_000L
                                val tokenCountStartNs = SystemClock.elapsedRealtimeNanos()
                                val promptTokens = HyMT2PenaltyNative.countPromptTokens(model, prompt, systemPrompt)
                                val tokenCountMs = (SystemClock.elapsedRealtimeNanos() - tokenCountStartNs) / 1_000_000L
                                val availableOutputTokens = (options.contextSize - promptTokens - 32).coerceAtLeast(0)
                                if (availableOutputTokens < 8) {
                                    error("OCR-блок ${index + 1}/${sourceBlocks.size} не помещается в контекст ${options.contextSize}. Выбери больший контекст.")
                                }
                                val effectiveMaxTokens = min(options.maxTokens, availableOutputTokens)
                                val completionStartNs = SystemClock.elapsedRealtimeNanos()
                                val completion = HyMT2PenaltyNative.complete(
                                    model = model,
                                    prompt = prompt,
                                    systemPrompt = systemPrompt,
                                    maxTokens = effectiveMaxTokens,
                                )
                                val completionWallMs = (SystemClock.elapsedRealtimeNanos() - completionStartNs) / 1_000_000L
                                val postprocessStartNs = SystemClock.elapsedRealtimeNanos()
                                // One OCR block must yield exactly one output line. If the
                                // model wraps a sentence, flatten those wraps within this block.
                                val translatedBlock = completion.text
                                    .replace('\r', '\n')
                                    .split('\n')
                                    .joinToString(" ") { it.trim() }
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                                if (translatedBlock.isBlank()) {
                                    error("Hy-MT2 вернул пустой перевод для OCR-блока ${index + 1}/${sourceBlocks.size}")
                                }
                                translatedBlocks += translatedBlock
                                generatedTokens += completion.tokensGenerated
                                generatedTimeMs += completion.generateTimeMs
                                val postprocessMs = (SystemClock.elapsedRealtimeNanos() - postprocessStartNs) / 1_000_000L
                                translatedBlocks += translatedBlock
                                generatedTokens += completion.tokensGenerated
                                generatedTimeMs += completion.generateTimeMs
                                promptTokensTotal += promptTokens
                                promptEvalTimeTotalMs += completion.promptEvalTimeMs
                                completionWallTotalMs += completionWallMs
                                promptCountTotalMs += tokenCountMs
                                modelCalls++
                                Log.i(PERF_TAG, "event=hy_block request=$requestId block=${index + 1}/${sourceBlocks.size} source_chars=${cleanSourceBlock.length} prompt_chars=${prompt.length} prompt_build_ms=$promptBuildMs token_count_ms=$tokenCountMs prompt_tokens=$promptTokens prompt_eval_ms=${completion.promptEvalTimeMs} generate_ms=${completion.generateTimeMs} completion_wall_ms=$completionWallMs postprocess_ms=$postprocessMs generated_tokens=${completion.tokensGenerated} output_chars=${translatedBlock.length} tps=${"%.2f".format(Locale.US, completion.tokensPerSecond)}")
                                if (completion.tokensGenerated >= effectiveMaxTokens) {
                                    Log.w(TAG, "OCR block ${index + 1}/${sourceBlocks.size} reached the output token limit; result may be truncated")
                                }
                            }
                        }

                        if (translatedBlocks.size != sourceBlocks.size) {
                            error("Нарушено соответствие OCR-блоков: вход=${sourceBlocks.size}, выход=${translatedBlocks.size}")
                        }
                        val translated = translatedBlocks.joinToString("\n")
                        val elapsedMs = (SystemClock.elapsedRealtime() - translationStartedAt).coerceAtLeast(1L)
                        val aggregateTokensPerSecond = if (generatedTimeMs > 0L) {
                            generatedTokens * 1000.0 / generatedTimeMs
                        } else 0.0
                        val translationWorkMs = (SystemClock.elapsedRealtimeNanos() - translationWorkStartNs) / 1_000_000L
                        Log.i(PERF_TAG, "event=hy_translation_complete request=$requestId source_chars=${sourceText.length} blocks=${sourceBlocks.size} nonblank_blocks=$nonBlankBlocks model_calls=$modelCalls inference_lock_wait_ms=$inferenceLockWaitMs model_ready_ms=$modelReadyMs prompt_tokens_total=$promptTokensTotal prompt_count_ms=$promptCountTotalMs prompt_eval_ms_total=$promptEvalTimeTotalMs generate_ms_total=$generatedTimeMs completion_wall_ms_total=$completionWallTotalMs generated_tokens=$generatedTokens translation_work_ms=$translationWorkMs request_elapsed_ms=${(SystemClock.elapsedRealtimeNanos() - requestStartNs) / 1_000_000L}")
                        Log.i(TAG, "OCR block mapping preserved: input=${sourceBlocks.size}, output=${translatedBlocks.size}")
                        rememberRecentContext(host, sourceLanguageKey, targetLanguage, sourceText, translated)
                        HyMT2Settings.recordTranslation(
                            appContext,
                            elapsedMs,
                            aggregateTokensPerSecond,
                            translated.length,
                        )
                        translated
                    }

                    val resultReadyNs = SystemClock.elapsedRealtimeNanos()
                    mainHandler.post {
                        val mainDispatchMs = (SystemClock.elapsedRealtimeNanos() - resultReadyNs) / 1_000_000L
                        val deliveryStartNs = SystemClock.elapsedRealtimeNanos()
                        deliverResult(host, result, sourceText)
                        val deliveryMs = (SystemClock.elapsedRealtimeNanos() - deliveryStartNs) / 1_000_000L
                        Log.i(PERF_TAG, "event=hy_result_delivery request=$requestId main_dispatch_wait_ms=$mainDispatchMs callback_ms=$deliveryMs total_request_ms=${(SystemClock.elapsedRealtimeNanos() - requestStartNs) / 1_000_000L}")
                    }
                } catch (t: Throwable) {
                    Log.w(PERF_TAG, "event=hy_translation_failed request=$requestId elapsed_ms=${(SystemClock.elapsedRealtimeNanos() - requestStartNs) / 1_000_000L} error=${t.javaClass.simpleName}")
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

    private fun buildTranslationSystemPrompt(sourceLanguage: String?, targetLanguage: String): String {
        // Match the short, known-good system instruction from the standalone Vulkan test.
        // Hy-MT2 is a translation model; avoid verbose role/persona text here.
        return if (
            sourceLanguage.equals("English", ignoreCase = true) &&
            targetLanguage.equals("Russian", ignoreCase = true)
        ) {
            "You are a professional English-to-Russian comic translator. Use natural idiomatic Russian. Preserve speaker tone, humor, sarcasm, names and meaning. Do not add explanations."
        } else {
            "You are a professional translator. Translate accurately and naturally. Preserve meaning and tone. Do not add explanations."
        }
    }

    private fun buildTranslationPrompt(
        sourceText: String,
        targetLanguage: String,
    ): String {
        // Exact user prompt used by the working standalone Vulkan test.
        // Background, glossary and style are intentionally bypassed during this
        // controlled comparison so the integrated request cannot drift.
        return """Translate the following text into $targetLanguage. Output only the translated result without explanations:

$sourceText"""
    }

    private fun recentContextFor(
        host: Any,
        sourceLanguage: String,
        targetLanguage: String,
        count: Int,
    ): List<Pair<String, String>> = synchronized(recentHistory) {
        val queue = recentHistory[host] ?: return@synchronized emptyList()
        val cutoff = SystemClock.elapsedRealtime() - 180_000L
        while (queue.isNotEmpty() && queue.peekFirst().createdAt < cutoff) queue.removeFirst()
        queue.filter {
            it.sourceLanguage == sourceLanguage && it.targetLanguage == targetLanguage
        }.takeLast(count).map { it.source to it.translated }
    }

    private fun rememberRecentContext(
        host: Any,
        sourceLanguage: String,
        targetLanguage: String,
        source: String,
        translated: String,
    ) = synchronized(recentHistory) {
        val queue = recentHistory.getOrPut(host) { ArrayDeque() }
        while (queue.size >= 4) queue.removeFirst()
        queue.addLast(
            HistoryEntry(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                source = source.take(1200),
                translated = translated.take(1200),
                createdAt = SystemClock.elapsedRealtime(),
            )
        )
    }

    @JvmStatic
    fun clearRecentContext() {
        synchronized(recentHistory) { recentHistory.clear() }
        Log.i(TAG, "Recent dialogue context cleared")
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

            val options = HyMT2Settings.load(context)
            val threads = options.threads.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            val created = Llama.loadModel(
                modelPath = modelPath,
                config = LlamaConfig(
                    contextSize = options.contextSize,
                    threads = threads,
                    gpuLayers = if (options.useVulkan) options.gpuLayers else 0,
                    temperature = options.temperature,
                    topP = options.topP,
                    topK = options.topK,
                ),
            )
            loadedSource = sourceKey
            loadedModel = created
            loadedDescriptor = descriptor
            descriptor = null // Ownership moved to loadedDescriptor.
            Log.i(TAG, "Hy-MT2 loaded from $sourceKey; CPU threads=$threads, context=${options.contextSize}, Vulkan=${options.useVulkan}, GPU layers=${if (options.useVulkan) options.gpuLayers else 0}")
            Log.i(TAG, "llama.cpp system info: ${Llama.getSystemInfo()}")
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
        if (normalized.contains(' ')) return normalized.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        return try {
            val locale = Locale.forLanguageTag(normalized)
            val name = locale.getDisplayLanguage(Locale.ENGLISH)
            val resolved = if (name.isBlank() || name.equals("und", true)) normalized else name
            resolved.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        } catch (_: Throwable) {
            normalized
        }
    }
}
