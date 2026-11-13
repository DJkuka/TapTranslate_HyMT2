package com.tap_to_translate.snap_translate.hymt2

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaModel
import dev.ffmpegkit.llama.LlamaResult

/**
 * Small JNI extension for the pinned llama-android AAR.
 *
 * Prompt-token counting uses a small JNI helper. Actual generation deliberately
 * delegates to the exact upstream Llama.complete() path used by the standalone
 * Vulkan test app, avoiding a second, independently maintained sampler loop.
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

    fun countPromptTokens(model: LlamaModel, prompt: String, systemPrompt: String = ""): Int {
        val count = nativeCountPromptTokens(handle(model), prompt, systemPrompt)
        check(count > 0) { "Не удалось определить число токенов входного запроса" }
        return count
    }

    suspend fun complete(
        model: LlamaModel,
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int,
    ): LlamaResult {
        require(maxTokens > 0) { "Для ответа не осталось токенов в выбранном контексте" }
        return Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
        )
    }
}
