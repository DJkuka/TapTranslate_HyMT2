package com.tap_to_translate.snap_translate.hymt2

import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaException
import dev.ffmpegkit.llama.LlamaModel
import dev.ffmpegkit.llama.LlamaResult
import org.json.JSONObject

/**
 * Hy-MT2-specific JNI inference bridge for the pinned llama-android AAR.
 * Counting and generation share the same chat-template/BOS treatment.
 */
object HyMT2PenaltyNative {
    init {
        System.loadLibrary("hymt2_jni")
    }

    @Volatile
    private var handleField = LlamaModel::class.java.getDeclaredField("handle").apply {
        isAccessible = true
    }

    private fun handle(model: LlamaModel): Long {
        val value = handleField.getLong(model)
        check(value != 0L) { "Hy-MT2 model is already unloaded" }
        return value
    }

    external fun nativeCountPromptTokens(handle: Long, prompt: String, systemPrompt: String): Int

    external fun nativeCpuTopologyJson(): String

    external fun nativeSetCpuAffinity(mask: Long): String

    fun cpuTopology(): org.json.JSONArray =
        JSONObject(nativeCpuTopologyJson()).getJSONArray("cpus")

    fun applyCpuAffinity(mask: Long): String = nativeSetCpuAffinity(mask)

    private external fun nativeComplete(
        handle: Long,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        seed: Int,
    ): String

    fun countPromptTokens(model: LlamaModel, prompt: String, systemPrompt: String = ""): Int {
        val count = nativeCountPromptTokens(handle(model), prompt, systemPrompt)
        check(count > 0) { "Не удалось определить число токенов входного запроса" }
        return count
    }

    fun complete(
        model: LlamaModel,
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int,
        repetitionPenalty: Float = 1.05f,
    ): LlamaResult {
        require(maxTokens > 0) { "Для ответа не осталось токенов в выбранном контексте" }
        val config = model.config
        val json = JSONObject(
            nativeComplete(
                handle(model), prompt, systemPrompt, maxTokens,
                config.temperature, config.topP, config.topK,
                repetitionPenalty, config.seed,
            )
        )
        if (json.has("error")) {
            throw LlamaException.InferenceFailed(
                json.optString("error", "Hy-MT2 native inference failed")
            )
        }
        return LlamaResult(
            text = json.optString("text"),
            tokensGenerated = json.optInt("tokensGenerated"),
            tokensPerSecond = json.optDouble("tokensPerSecond", 0.0).toFloat(),
            promptEvalTimeMs = json.optLong("promptEvalTimeMs"),
            generateTimeMs = json.optLong("generateTimeMs"),
        )
    }
}
