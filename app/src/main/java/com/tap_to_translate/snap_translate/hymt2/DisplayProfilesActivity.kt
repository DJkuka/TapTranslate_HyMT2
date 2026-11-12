package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Manager for built-in and user-created text display profiles. */
class DisplayProfilesActivity : Activity() {
    private lateinit var profileSpinner: Spinner
    private lateinit var description: TextView
    private lateinit var status: TextView
    private var profiles: List<DisplayProfileInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Профили оформления"
        DisplayProfilesStore.ensureInitialized(this)
        buildScreen()
        reloadProfiles()
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        })

        addText(root, "ПРОФИЛЬ ОФОРМЛЕНИЯ", 20f, true, dp(8))
        addText(
            root,
            "Профиль хранит параметры отображения перевода: фон баблов и свободного текста, размер, шрифт, рамку, стиль, выравнивание и манга-настройки. OCR-движок, модели перевода и параметры детектора остаются общими.",
            14f,
            false,
            dp(14)
        )

        profileSpinner = Spinner(this)
        root.addView(profileSpinner, matchWrap(dp(8)))
        description = addText(root, "", 13f, false, dp(14))

        addButton(root, "Применить со следующей страницы") {
            val selected = selectedProfile() ?: return@addButton
            try {
                DisplayProfilesStore.scheduleApply(this, selected.id)
                showStatus("«" + selected.name + "» будет применён при обработке следующей страницы.")
            } catch (t: Throwable) {
                showStatus(t.message ?: "Не удалось выбрать профиль")
            }
        }

        addButton(root, "Сохранить текущие настройки в выбранный профиль") {
            val selected = selectedProfile() ?: return@addButton
            try {
                DisplayProfilesStore.saveCurrentTo(this, selected.id)
                showStatus("Текущие настройки сохранены в «" + selected.name + "».")
            } catch (t: Throwable) {
                showStatus(t.message ?: "Не удалось сохранить профиль")
            }
        }

        addButton(root, "Создать профиль из текущих настроек") {
            promptName("Новый профиль") { name ->
                try {
                    val id = DisplayProfilesStore.createFromCurrent(this, name)
                    reloadProfiles(id)
                    showStatus("Создан профиль «" + name.trim() + "».")
                } catch (t: Throwable) {
                    showStatus(t.message ?: "Не удалось создать профиль")
                }
            }
        }

        addButton(root, "Дублировать выбранный профиль") {
            val selected = selectedProfile() ?: return@addButton
            promptName("Копия профиля", selected.name + " — копия") { name ->
                try {
                    val id = DisplayProfilesStore.duplicate(this, selected.id, name)
                    reloadProfiles(id)
                    showStatus("Создана копия «" + name.trim() + "».")
                } catch (t: Throwable) {
                    showStatus(t.message ?: "Не удалось дублировать профиль")
                }
            }
        }

        addButton(root, "Удалить выбранный пользовательский профиль") {
            val selected = selectedProfile() ?: return@addButton
            if (selected.builtIn) {
                showStatus("Встроенные профили защищены от удаления.")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Удалить профиль?")
                    .setMessage("Профиль «" + selected.name + "» будет удалён.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ ->
                        try {
                            DisplayProfilesStore.delete(this, selected.id)
                            reloadProfiles("comics")
                            showStatus("Профиль удалён.")
                        } catch (t: Throwable) {
                            showStatus(t.message ?: "Не удалось удалить профиль")
                        }
                    }
                    .show()
            }
        }

        addButton(root, "Сбросить встроенные профили") {
            AlertDialog.Builder(this)
                .setTitle("Сбросить встроенные профили?")
                .setMessage("«Комиксы» вернётся к настройкам, сохранённым при первом запуске профилей. «Книги» вернётся к исходному книжному шаблону. Пользовательские профили не изменятся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сбросить") { _, _ ->
                    try {
                        DisplayProfilesStore.resetBuiltIns(this)
                        reloadProfiles()
                        showStatus("Встроенные профили восстановлены.")
                    } catch (t: Throwable) {
                        showStatus(t.message ?: "Не удалось восстановить профили")
                    }
                }
                .show()
        }

        addText(
            root,
            "Изменение параметров профиля не прерывает текущий перевод. После нажатия «Применить» новые параметры вступят в действие при следующей обработке страницы.",
            12f,
            false,
            dp(10)
        )
        status = addText(root, "", 13f, false, 0)
    }

    override fun onResume() {
        super.onResume()
        if (::profileSpinner.isInitialized) reloadProfiles()
    }

    private fun reloadProfiles(preferredId: String? = null) {
        profiles = DisplayProfilesStore.listProfiles(this)
        val activeId = DisplayProfilesStore.activeProfileId(this)
        val labels = profiles.map {
            val activeMark = if (it.id == activeId) " · активно" else ""
            val builtInMark = if (it.builtIn) " · встроенный" else ""
            it.name + builtInMark + activeMark
        }
        profileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val target = preferredId ?: activeId
        val index = profiles.indexOfFirst { it.id == target }.let { if (it >= 0) it else 0 }
        if (profiles.isNotEmpty()) profileSpinner.setSelection(index)
        updateDescription()
        profileSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                updateDescription()
            }
        }
    }

    private fun updateDescription() {
        val selected = selectedProfile()
        description.text = when {
            selected == null -> "Нет доступных профилей."
            selected.builtIn ->
                "Встроенный профиль. Его параметры можно изменить в главном экране настроек оформления и сохранить сюда кнопкой ниже. Удаление недоступно."
            else -> "Пользовательский профиль. Его можно применить, обновить текущими настройками, дублировать или удалить."
        }
    }

    private fun selectedProfile(): DisplayProfileInfo? {
        val index = profileSpinner.selectedItemPosition
        return profiles.getOrNull(index)
    }

    private fun promptName(titleText: String, initialName: String = "", onCreate: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(initialName)
            setSelection(text.length)
            hint = "Название профиля"
        }
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Создать") { _, _ -> onCreate(input.text.toString()) }
            .show()
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        parent.addView(button, matchWrap(8))
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        bold: Boolean,
        bottom: Int
    ): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        parent.addView(view, matchWrap(bottom))
        return view
    }

    private fun showStatus(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun matchWrap(bottom: Int): LinearLayout.LayoutParams {
        val density = resources.displayMetrics.density
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (bottom * density).toInt()
        }
    }
}
