package com.tap_to_translate.snap_translate.hymt2

import android.content.Context
import android.util.Log

/** Persisted inference and translation-prompt settings for the Hy-MT2 engine. */
data class HyMT2Options(
    val threads: Int,
    val contextSize: Int,
    val maxTokens: Int,
    val gpuLayers: Int,
    val repetitionPenalty: Float,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val useRecentContext: Boolean,
    val recentContextPairs: Int,
    val backgroundContext: String,
    val glossary: String,
    val stylePreset: String,
    val customStyle: String,
    val useVulkan: Boolean,
    val performanceProfile: String,
)

data class HyMT2Stats(
    val lastTokensPerSecond: Double,
    val averageTokensPerSecond: Double,
    val lastDurationMs: Long,
    val lastOutputChars: Int,
    val translationsCount: Int,
)

object HyMT2Settings {
    private const val TAG = "TapTranslateHyMT2"
    private const val PREFS = "hymt2_engine_settings"

    // The integrated AAR contains both CPU/NEON and Vulkan backends.
    const val VULKAN_BACKEND_COMPILED = true

    val threadChoices = listOf(2, 3, 4, 6, 8)
    val gpuLayerChoices = listOf(0, 2, 4, 6, 8, 12, 16, 24)
    val contextChoices = listOf(256, 512, 1024, 2048, 3072, 4096, 6144, 8192)
    val maxTokenChoices = listOf(256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096)
    val historyChoices = listOf(0, 1, 2, 3, 4)
    val profileChoices = listOf("Быстрый", "Сбалансированный", "Качественный", "Настраиваемый")
    val styleChoices = listOf(
        "Естественный перевод комиксов",
        "Разговорный и живой язык",
        "Более дословный перевод",
        "Драматичный стиль, ирония и сарказм",
        "Свой стиль (описать ниже)",
    )

    fun load(context: Context): HyMT2Options {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The previous CPU-only build always persisted use_vulkan=false and had
        // no GPU-layer preference. Migrate once to the tablet-tested v12 profile.
        if (!p.getBoolean("vulkan_v12_migrated", false)) {
            p.edit()
                .putBoolean("use_vulkan", true)
                .putInt("gpu_layers", 8)
                .putInt("threads", 6)
                .putFloat("repetition_penalty", 1.05f)
                .putBoolean("vulkan_v12_migrated", true)
                .apply()
        }
        val defaultThreads = minOf(6, Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
        return HyMT2Options(
            threads = p.getInt("threads", defaultThreads).coerceIn(1, 8),
            contextSize = p.getInt("context_size", 2048).coerceIn(256, 8192),
            maxTokens = p.getInt("max_tokens", 768).coerceIn(64, 4096),
            gpuLayers = p.getInt("gpu_layers", 8).coerceIn(0, 24),
            repetitionPenalty = p.getFloat("repetition_penalty", 1.05f).coerceIn(1.0f, 2.0f),
            temperature = p.getFloat("temperature", 0.7f).coerceIn(0f, 2f),
            topP = p.getFloat("top_p", 0.6f).coerceIn(0.05f, 1f),
            topK = p.getInt("top_k", 20).coerceIn(1, 100),
            useRecentContext = p.getBoolean("use_recent_context", true),
            recentContextPairs = p.getInt("recent_context_pairs", 2).coerceIn(0, 4),
            backgroundContext = p.getString("background_context", "") ?: "",
            glossary = p.getString("glossary", "") ?: "",
            stylePreset = p.getString("style_preset", styleChoices.first()) ?: styleChoices.first(),
            customStyle = p.getString("custom_style", "") ?: "",
            // Vulkan is enabled by default with the tablet-tested 8-layer profile.
            useVulkan = VULKAN_BACKEND_COMPILED && p.getBoolean("use_vulkan", true),
            performanceProfile = p.getString("performance_profile", profileChoices[1]) ?: profileChoices[1],
        )
    }

    fun save(context: Context, options: HyMT2Options) {
        require(options.threads in 1..8) { "Число потоков должно быть от 1 до 8" }
        require(options.contextSize in 256..8192) { "Размер контекста должен быть от 256 до 8192" }
        require(options.maxTokens in 64..4096) { "Лимит генерации должен быть от 64 до 4096" }
        require(options.gpuLayers in gpuLayerChoices) { "Недопустимое число GPU-слоёв" }
        require(options.repetitionPenalty in 1.0f..2.0f) { "Repetition penalty должна быть от 1,0 до 2,0" }
        require(options.temperature in 0f..2f) { "Temperature должна быть от 0 до 2" }
        require(options.topP in 0.05f..1f) { "Top-P должна быть от 0,05 до 1" }
        require(options.topK in 1..100) { "Top-K должен быть от 1 до 100" }
        require(options.recentContextPairs in 0..4) { "Число предыдущих реплик должно быть от 0 до 4" }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("threads", options.threads)
            .putInt("context_size", options.contextSize)
            .putInt("max_tokens", options.maxTokens)
            .putInt("gpu_layers", options.gpuLayers)
            .putFloat("repetition_penalty", options.repetitionPenalty)
            .putFloat("temperature", options.temperature)
            .putFloat("top_p", options.topP)
            .putInt("top_k", options.topK)
            .putBoolean("use_recent_context", options.useRecentContext)
            .putInt("recent_context_pairs", options.recentContextPairs)
            .putString("background_context", options.backgroundContext.take(4000))
            .putString("glossary", options.glossary.take(3000))
            .putString("style_preset", options.stylePreset)
            .putString("custom_style", options.customStyle.take(1000))
            .putBoolean("use_vulkan", options.useVulkan)
            .putString("performance_profile", options.performanceProfile)
            .apply()
        Log.i(TAG, "Hy-MT2 settings saved: threads=${options.threads}, context=${options.contextSize}, maxTokens=${options.maxTokens}, Vulkan=${options.useVulkan}, gpuLayers=${options.gpuLayers}, repetitionPenalty=${options.repetitionPenalty}")
    }

    fun recordTranslation(context: Context, durationMs: Long, tokensPerSecond: Double, outputChars: Int) {
        if (!tokensPerSecond.isFinite() || tokensPerSecond <= 0.0) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousSamples = (p.getString("stats_tps_samples", "") ?: "")
            .split(',')
            .mapNotNull { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
        val samples = (previousSamples + tokensPerSecond).takeLast(10)
        val newAverage = samples.average()
        p.edit()
            .putInt("stats_count", samples.size)
            .putString("stats_tps_samples", samples.joinToString(","))
            .putFloat("stats_last_tps", tokensPerSecond.toFloat())
            .putFloat("stats_average_tps", newAverage.toFloat())
            .putLong("stats_last_duration_ms", durationMs)
            .putInt("stats_last_output_chars", outputChars)
            .apply()
        Log.i(TAG, "Translation speed: ${"%.2f".format(java.util.Locale.US, tokensPerSecond)} tok/s; duration=${durationMs}ms; outputChars=$outputChars; rollingSamples=${samples.size}")
    }

    fun stats(context: Context): HyMT2Stats {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HyMT2Stats(
            lastTokensPerSecond = p.getFloat("stats_last_tps", 0f).toDouble(),
            averageTokensPerSecond = p.getFloat("stats_average_tps", 0f).toDouble(),
            lastDurationMs = p.getLong("stats_last_duration_ms", 0L),
            lastOutputChars = p.getInt("stats_last_output_chars", 0),
            translationsCount = p.getInt("stats_count", 0),
        )
    }
}
