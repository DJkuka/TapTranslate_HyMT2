package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class HyMT2CpuSettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var modes: RadioGroup
    private lateinit var manualContainer: LinearLayout
    private val cpuChecks = linkedMapOf<Int, CheckBox>()
    private var topology: HyMT2CpuAffinity.Topology? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (18 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text = "Распределение CPU"; textSize = 23f })
        root.addView(TextView(this).apply {
            text = "Настройка применяется только к вычислительным потокам Hy-MT2. Android может ограничивать доступные ядра."
            textSize = 14f
        })
        modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        addMode(HyMT2CpuAffinity.SYSTEM, "Системный планировщик Android")
        addMode(HyMT2CpuAffinity.AUTO, "Производительные ядра (авто)")
        addMode(HyMT2CpuAffinity.MANUAL, "Ручной выбор ядер")
        root.addView(modes)
        manualContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(manualContainer)
        root.addView(Button(this).apply {
            text = "Обновить сведения о процессоре"
            setOnClickListener { loadTopology() }
        })
        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener { save() }
        })
        root.addView(Button(this).apply {
            text = "Назад"
            setOnClickListener { finish() }
        })
        loadTopology()
        when (HyMT2CpuAffinity.mode(this)) {
            HyMT2CpuAffinity.AUTO -> modes.check(1)
            HyMT2CpuAffinity.MANUAL -> modes.check(2)
            else -> modes.check(0)
        }
        modes.setOnCheckedChangeListener { _, checkedId ->
            manualContainer.visibility = if (checkedId == 2) View.VISIBLE else View.GONE
        }
        manualContainer.visibility = if (modes.checkedRadioButtonId == 2) View.VISIBLE else View.GONE
    }

    private fun addMode(value: String, title: String) {
        modes.addView(RadioButton(this).apply {
            text = title
            tag = value
            id = when (value) {
                HyMT2CpuAffinity.SYSTEM -> 0
                HyMT2CpuAffinity.AUTO -> 1
                else -> 2
            }
        })
    }

    private fun loadTopology() {
        try {
            val t = HyMT2CpuAffinity.topology()
            topology = t
            manualContainer.removeAllViews()
            cpuChecks.clear()
            manualContainer.addView(TextView(this).apply {
                text = "Онлайн: " + t.cpus.count { it.online } +
                    "; доступны приложению: " + t.cpus.count { it.online && it.allowed } +
                    "; источник классификации: " + t.classificationSource
                textSize = 13f
            })
            if (!t.affinitySupported) {
                manualContainer.addView(TextView(this).apply {
                    text = "Android не предоставил маску доступных ядер. Ручная привязка может быть недоступна."
                    textSize = 13f
                })
            }
            val saved = HyMT2CpuAffinity.mask(this)
            t.cpus.forEach { cpu ->
                val label = when {
                    !cpu.online -> "отключено"
                    !cpu.allowed -> "недоступно приложению"
                    cpu.performance && t.classificationSource != "unknown" -> "производительное"
                    t.classificationSource == "unknown" -> "тип не определён"
                    else -> "энергоэффективное / обычное"
                }
                val cb = CheckBox(this).apply {
                    text = "CPU ${cpu.id} · $label" +
                        if (cpu.capacity > 0) " · capacity ${cpu.capacity}" else if (cpu.maxFreqKhz > 0) " · ${cpu.maxFreqKhz / 1000} MHz" else ""
                    isEnabled = cpu.online && cpu.allowed
                    isChecked = (saved and (1L shl cpu.id)) != 0L
                }
                cpuChecks[cpu.id] = cb
                manualContainer.addView(cb)
            }
        } catch (t: Throwable) {
            topology = null
            manualContainer.removeAllViews()
            manualContainer.addView(TextView(this).apply {
                text = "Не удалось прочитать топологию CPU: ${t.message}"
            })
        }
    }

    private fun save() {
        val mode = when (modes.checkedRadioButtonId) {
            1 -> HyMT2CpuAffinity.AUTO
            2 -> HyMT2CpuAffinity.MANUAL
            else -> HyMT2CpuAffinity.SYSTEM
        }
        var mask = 0L
        if (mode == HyMT2CpuAffinity.MANUAL) {
            cpuChecks.forEach { (id, cb) -> if (cb.isChecked && cb.isEnabled) mask = mask or (1L shl id) }
        }
        try {
            HyMT2CpuAffinity.save(this, mode, mask)
            Toast.makeText(this, "Настройки CPU сохранены", Toast.LENGTH_SHORT).show()
            finish()
        } catch (t: Throwable) {
            Toast.makeText(this, t.message ?: "Не удалось сохранить настройки", Toast.LENGTH_LONG).show()
        }
    }
}
