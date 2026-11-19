package com.tap_to_translate.snap_translate.hymt2

import android.content.Context
import android.util.Log
import dev.ffmpegkit.llama.LlamaModel
import org.json.JSONObject

object HyMT2CpuAffinity {
    private const val TAG = "HyMT2CpuAffinity"
    private const val PREFS = "hymt2_cpu_affinity"
    const val SYSTEM = "system"
    const val AUTO = "performance"
    const val MANUAL = "custom"

    data class Cpu(val id: Int, val online: Boolean, val allowed: Boolean, val maxFreqKhz: Long, val capacity: Long, val performance: Boolean)
    data class Topology(val affinitySupported: Boolean, val classificationSource: String, val onlineMask: Long, val cpus: List<Cpu>)

    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", SYSTEM) ?: SYSTEM
    fun mask(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("mask", 0L)

    fun save(context: Context, mode: String, mask: Long) {
        require(mode in setOf(SYSTEM, AUTO, MANUAL))
        require(mode != MANUAL || mask != 0L) { "Выбери хотя бы одно ядро" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode).putLong("mask", mask).apply()
    }

    fun topology(): Topology {
        val json = JSONObject(HyMT2PenaltyNative.nativeCpuTopologyJson())
        val arr = json.getJSONArray("cpus")
        val cpus = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Cpu(c.getInt("id"), c.getBoolean("online"), c.getBoolean("allowed"), c.optLong("maxFreqKhz"), c.optLong("capacity"), c.getBoolean("performance"))
        }
        return Topology(json.optBoolean("affinitySupported"), json.optString("classificationSource", "unknown"), parseMask(json.optString("onlineMask")), cpus)
    }

    fun selectedMask(context: Context, topology: Topology): Pair<Long, String> = when (mode(context)) {
        AUTO -> {
            if (topology.classificationSource == "unknown") 0L to "Не удалось определить производительные ядра; используется системный планировщик."
            else {
                val selected = topology.cpus.filter { it.online && it.allowed && it.performance }.fold(0L) { acc, cpu -> acc or (1L shl cpu.id) }
                if (selected == 0L) 0L to "Производительные ядра недоступны; используется системный планировщик."
                else selected to "Автоматически выбраны производительные ядра."
            }
        }
        MANUAL -> {
            val allowed = topology.cpus.filter { it.online && it.allowed }.fold(0L) { acc, cpu -> acc or (1L shl cpu.id) }
            (mask(context) and allowed) to "Ручной выбор ядер."
        }
        else -> 0L to "Системный планировщик Android."
    }

    fun begin(context: Context, model: LlamaModel, threads: Int) {
        val topology = topology()
        val (mask, description) = selectedMask(context, topology)
        val result = JSONObject(HyMT2PenaltyNative.nativeSetCpuAffinity(HyMT2PenaltyNative.handleForAffinity(model), mask, threads))
        Log.i(TAG, "$description requested=${result.optString("requestedMask")} effective=${result.optString("effectiveMask")} result=$result")
        if (!result.optBoolean("ok")) Log.w(TAG, "CPU affinity was not applied: $result")
    }

    fun end(model: LlamaModel) {
        runCatching { Log.i(TAG, "CPU affinity end: ${HyMT2PenaltyNative.nativeEndCpuAffinity(HyMT2PenaltyNative.handleForAffinity(model))}") }
            .onFailure { Log.w(TAG, "Unable to finish CPU affinity scope", it) }
    }
    fun release(model: LlamaModel) {
        runCatching { Log.i(TAG, "CPU affinity release: ${HyMT2PenaltyNative.nativeReleaseCpuAffinity(HyMT2PenaltyNative.handleForAffinity(model))}") }
            .onFailure { Log.w(TAG, "Unable to release CPU affinity pool", it) }
    }
    private fun parseMask(value: String): Long = runCatching { java.lang.Long.parseUnsignedLong(value.removePrefix("0x"), 16) }.getOrDefault(0L)
}
