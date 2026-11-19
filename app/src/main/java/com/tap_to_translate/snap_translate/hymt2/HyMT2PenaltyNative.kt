package com.tap_to_translate.snap_translate.hymt2

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaModel
import dev.ffmpegkit.llama.LlamaResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HyMT2PenaltyNative {
    init { System.loadLibrary("hymt2_jni") }
    @Volatile private var handleField = LlamaModel::class.java.getDeclaredField("handle").apply { isAccessible = true }
    private fun handle(model: LlamaModel): Long {
        val value = handleField.getLong(model)
        check(value != 0L) { "Hy-MT2 model is already unloaded" }
        return value
    }
    internal fun handleForAffinity(model: LlamaModel): Long = handle(model)
    external fun nativeCountPromptTokens(handle: Long, prompt: String, systemPrompt: String): Int
    external fun nativeCpuTopologyJson(): String
    external fun nativeSetCpuAffinity(handle: Long, mask: Long, threads: Int): String
    external fun nativeEndCpuAffinity(handle: Long): String
    external fun nativeReleaseCpuAffinity(handle: Long): String

    fun countPromptTokens(model: LlamaModel, prompt: String, systemPrompt: String = ""): Int {
        val count = nativeCountPromptTokens(handle(model), prompt, systemPrompt)
        check(count > 0) { "Не удалось определить число токенов входного запроса" }
        return count
    }

    suspend fun complete(model: LlamaModel, prompt: String, systemPrompt: String = "", maxTokens: Int, context: android.content.Context, threads: Int): LlamaResult = withContext(Dispatchers.Default) {
        HyMT2CpuAffinity.begin(context.applicationContext, model, threads)
        try {
            Llama.complete(model = model, prompt = prompt, systemPrompt = systemPrompt, maxTokens = maxTokens)
        } finally {
            HyMT2CpuAffinity.end(model)
        }
    }
}
