package com.tap_to_translate.snap_translate.hymt2

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.Toast

/** Home shortcut sharing the native HAWK_OCR preference. */
object HomeOcrQuickSelector {
    private const val PREF_KEY = "HAWK_OCR"
    private const val DEFAULT_ENGINE = "PP-OCRv6 (Comics)"
    private const val ROW_ID = 0x7f0a01b7
    private const val LABEL_ID = 0x7f0a01bb
    private val options = arrayOf(
        "PP-OCRv6 (Comics)",
        "ML Kit (Device Text Recognition)",
        "Cloud Text Recognition"
    )

    @JvmStatic
    fun bind(root: View) {
        val row = root.findViewById<View>(ROW_ID) ?: return
        val label = root.findViewById<TextView>(LABEL_ID) ?: return
        sync(root)
        row.setOnClickListener { showChooser(row.context, label) }
    }

    @JvmStatic
    fun sync(root: View) {
        val label = root.findViewById<TextView>(LABEL_ID) ?: return
        label.text = currentEngine()
    }

    private fun showChooser(context: Context, label: TextView) {
        val selected = options.indexOf(currentEngine()).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(context)
            .setTitle("Движок распознавания текста")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                val choice = options.getOrElse(which) { DEFAULT_ENGINE }
                try {
                    val owner = Class.forName("qh.n")
                    owner.getMethod("b", Any::class.java, String::class.java)
                        .invoke(null, choice, PREF_KEY)
                    label.text = choice
                    dialog.dismiss()
                } catch (t: Throwable) {
                    Toast.makeText(context, "Не удалось сохранить выбор OCR", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun currentEngine(): String {
        return try {
            val owner = Class.forName("vh.l")
            owner.getMethod("c").invoke(null) as? String ?: DEFAULT_ENGINE
        } catch (_: Throwable) {
            try {
                val owner = Class.forName("qh.n")
                owner.getMethod("a", Any::class.java, String::class.java)
                    .invoke(null, DEFAULT_ENGINE, PREF_KEY) as? String ?: DEFAULT_ENGINE
            } catch (_: Throwable) {
                DEFAULT_ENGINE
            }
        }
    }
}
