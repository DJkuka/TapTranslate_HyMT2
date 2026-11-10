package com.tap_to_translate.snap_translate.hymt2

import dev.ffmpegkit.llama.LlamaModel
import dev.ffmpegkit.llama.LlamaResult
import org.json.JSONObject

/**
 * Small JNI extension for the pinned llama-android AAR.
 *
 * The upstream Llama API does not expose repetition_penalty or prompt token
 * counting. This bridge uses the same LlamaModel native handle and llama.cpp
 * b9878 API, without changing the prebuilt Vulkan/CPU backend libraries.
 */
internal object HyMT2PenaltyNative {
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

    external fun nativeComplete(
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

    fun countPromptTokens(model: LlamaModel, prompt: String): Int {
        val count = nativeCountPromptTokens(handle(model), prompt, "")
        check(count > 0) { "Не удалось определить число токенов входного запроса" }
        return count
    }

    fun complete(
        model: LlamaModel,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
    ): LlamaResult {
        require(maxTokens > 0) { "Для ответа не осталось токенов в выбранном контексте" }
        val json = nativeComplete(
            handle(model), prompt, "", maxTokens,
            temperature, topP, topK, repetitionPenalty, -1,
        )
        val result = JSONObject(json)
        if (result.has("error")) {
            error(result.optString("error", "Ошибка llama.cpp"))
        }
        return LlamaResult(
            text = result.optString("text", ""),
            tokensGenerated = result.optInt("tokensGenerated", 0),
            tokensPerSecond = result.optDouble("tokensPerSecond", 0.0).toFloat(),
            promptEvalTimeMs = result.optLong("promptEvalTimeMs", 0L),
            generateTimeMs = result.optLong("generateTimeMs", 0L),
        )
    }
}
