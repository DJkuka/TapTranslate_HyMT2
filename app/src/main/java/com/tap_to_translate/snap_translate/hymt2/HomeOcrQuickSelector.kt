package com.tap_to_translate.snap_translate.hymt2

import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

/** Home shortcut sharing the native HAWK_OCR preference. */
object HomeOcrQuickSelector {
    private const val PREF_KEY = "HAWK_OCR"
    private const val DEFAULT_ENGINE = "PP-OCRv6 (Comics)"
    private val options = arrayOf(
        "PP-OCRv6 (Comics)",
        "ML Kit (Device Text Recognition)",
        "Cloud Text Recognition"
    )

    @JvmStatic
    fun bind(root: View) {
        Log.e("HomeOcrQuickSelector", "bind invoked")
        val rowId = resourceId(root, "fragment_home_ll_OCR_engine")
        val labelId = resourceId(root, "fragment_home_tv_OCR_engine")
        val row = root.findViewById<View>(rowId) ?: run {
            Log.e("HomeOcrQuickSelector", "Home OCR row ID not found; id=" + rowId)
            return
        }
        val label = root.findViewById<TextView>(labelId) ?: run {
            Log.e("HomeOcrQuickSelector", "Home OCR label ID not found; id=" + labelId)
            return
        }
        sync(root)
        row.isEnabled = true
        row.isClickable = true
        row.isFocusable = true
        label.isEnabled = true
        label.isClickable = true
        val click = View.OnClickListener { clicked ->
            Log.e("HomeOcrQuickSelector", "OCR row clicked")
            showChooser(clicked, label)
        }
        row.setOnClickListener(click)
        label.setOnClickListener(click)
        Log.e("HomeOcrQuickSelector", "bound row shown=" + row.isShown + " label shown=" + label.isShown)
    }

    @JvmStatic
    fun sync(root: View) {
        val labelId = resourceId(root, "fragment_home_tv_OCR_engine")
        val label = root.findViewById<TextView>(labelId) ?: return
        label.text = currentEngine()
    }

    private fun resourceId(root: View, name: String): Int =
        root.resources.getIdentifier(name, "id", root.context.packageName)

    private fun showChooser(anchor: View, label: TextView) {
        try {
            val selected = currentEngine()
            val popup = PopupMenu(anchor.context, anchor)
            options.forEachIndexed { index, option ->
                val item = popup.menu.add(0, index, index, option)
                item.isCheckable = true
                item.isChecked = option == selected
            }
            popup.setOnMenuItemClickListener { item ->
                val choice = options.getOrNull(item.itemId) ?: DEFAULT_ENGINE
                try {
                    val owner = Class.forName("qh.n")
                    owner.getMethod("b", Any::class.java, String::class.java)
                        .invoke(null, choice, PREF_KEY)
                    label.text = choice
                    Log.e("HomeOcrQuickSelector", "OCR engine selected=" + choice)
                } catch (t: Throwable) {
                    Log.e("HomeOcrQuickSelector", "Could not persist HAWK_OCR", t)
                    Toast.makeText(anchor.context, "Не удалось сохранить выбор OCR", Toast.LENGTH_SHORT).show()
                }
                true
            }
            popup.show()
            Log.e("HomeOcrQuickSelector", "OCR PopupMenu.show() called")
        } catch (t: Throwable) {
            Log.e("HomeOcrQuickSelector", "Could not show OCR PopupMenu", t)
            Toast.makeText(anchor.context, "Не удалось открыть список OCR", Toast.LENGTH_SHORT).show()
        }
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
