package com.tap_to_translate.snap_translate.hymt2

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DisplayProfileInfo(
    val id: String,
    val name: String,
    val builtIn: Boolean
)

/**
 * Stores display presets separately from OCR-engine and translation settings.
 * A selected preset is applied only when the host starts rendering the next
 * translated page, never at the moment the user selects it.
 */
object DisplayProfilesStore {
    private const val TAG = "TapTranslateProfiles"
    private const val PREFS_NAME = "taptranslate_display_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_BUILTIN_DEFAULTS = "builtin_defaults_json"
    private const val KEY_PENDING_ID = "pending_profile_id"
    private const val KEY_ACTIVE_ID = "active_profile_id"
    private const val KEY_FIXED_FONT_MIGRATED = "fixed_font_size_migrated"
    private const val KEY_FIXED_FONT_SIZE = "HAWK_FIXED_FONT_SIZE"

    private const val OCR_PREFS = "taptranslate_ocr_settings"
    private const val KEY_FREE_ALPHA_ENABLED = "ocr_free_text_alpha_enabled"
    private const val KEY_FREE_ALPHA_PERCENT = "ocr_free_text_alpha_percent"

    private val hawkDefaults: LinkedHashMap<String, Any> = linkedMapOf(
        "HAWK_BG_TRANS" to 236,
        "HAWK_FONT_FULL" to "Roboto-Medium",
        "HAWK_FONT_SIZE" to 20,
        KEY_FIXED_FONT_SIZE to false,
        "HAWK_BG_STROKE" to true,
        "HAWK_MANGA_MODE" to false,
        "HAWK_MANGA_FONT" to true,
        "HAWK_MANGA_BACKGROUND" to true,
        "HAWK_VERTICAL" to false,
        "HAWK_UPCASE" to true,
        "HAWK_CENTER" to true,
        "text_style" to 0,
        "text_style_custom_bg_color" to 0xFFFFF3B0.toInt(),
        "text_style_custom_text_color" to 0xFF3D3200.toInt()
    )

    private val stringKeys = setOf("HAWK_FONT_FULL")
    private val intKeys = setOf(
        "HAWK_BG_TRANS", "HAWK_FONT_SIZE", "text_style",
        "text_style_custom_bg_color", "text_style_custom_text_color"
    )

    @Synchronized
    fun ensureInitialized(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_PROFILES) && prefs.contains(KEY_BUILTIN_DEFAULTS)) {
            migrateFixedFontFlag(context, prefs)
            return
        }

        // Migration is deliberately read-only with respect to the active host
        // settings. The first Comics preset is a snapshot of the user's current
        // configuration, so opening this screen does not change appearance.
        val comics = snapshotCurrent(context).apply {
            optJSONObject("host")?.put(KEY_FIXED_FONT_SIZE, false)
        }
        val books = createBooksSnapshot(context)
        val profiles = JSONArray()
            .put(profileJson("comics", "Комиксы", true, comics))
            .put(profileJson("books", "Книги", true, books))

        prefs.edit()
            .putString(KEY_PROFILES, profiles.toString())
            .putString(KEY_BUILTIN_DEFAULTS, profiles.toString())
            .putString(KEY_ACTIVE_ID, "comics")
            .remove(KEY_PENDING_ID)
            .apply()
        prefs.edit().putBoolean(KEY_FIXED_FONT_MIGRATED, true).apply()
    }

    @Synchronized
    fun listProfiles(context: Context): List<DisplayProfileInfo> {
        ensureInitialized(context)
        val array = readProfiles(context)
        val result = ArrayList<DisplayProfileInfo>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(
                DisplayProfileInfo(
                    item.optString("id"),
                    item.optString("name"),
                    item.optBoolean("builtIn", false)
                )
            )
        }
        return result
    }

    @Synchronized
    fun activeProfileId(context: Context): String {
        ensureInitialized(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_ID, "comics") ?: "comics"
    }

    @Synchronized
    fun fixedFontSize(context: Context, profileId: String): Boolean {
        ensureInitialized(context)
        val profile = findProfile(readProfiles(context), profileId) ?: return profileId == "books"
        val host = profile.optJSONObject("values")?.optJSONObject("host")
        return host?.optBoolean(KEY_FIXED_FONT_SIZE, profileId == "books") ?: (profileId == "books")
    }

    @Synchronized
    fun setFixedFontSize(context: Context, profileId: String, enabled: Boolean) {
        ensureInitialized(context)
        val array = readProfiles(context)
        val profile = findProfile(array, profileId) ?: throw IllegalArgumentException("Профиль не найден")
        val values = JSONObject(profile.optJSONObject("values")?.toString() ?: "{}")
        val host = JSONObject(values.optJSONObject("host")?.toString() ?: "{}")
        host.put(KEY_FIXED_FONT_SIZE, enabled)
        values.put("host", host)
        profile.put("values", values)
        writeProfiles(context, array)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE_ID, "comics") == profileId) {
            writeHawk(KEY_FIXED_FONT_SIZE, enabled)
            notifyHostSettingsChanged()
        }
    }

    @Synchronized
    fun scheduleApply(context: Context, profileId: String) {
        ensureInitialized(context)
        if (findProfile(readProfiles(context), profileId) == null) {
            throw IllegalArgumentException("Профиль не найден")
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_ID, profileId).apply()
    }

    @Synchronized
    fun saveCurrentTo(context: Context, profileId: String) {
        ensureInitialized(context)
        val array = readProfiles(context)
        val index = findProfileIndex(array, profileId)
        require(index >= 0) { "Профиль не найден" }
        val old = array.getJSONObject(index)
        old.put("values", snapshotCurrent(context))
        writeProfiles(context, array)
    }

    @Synchronized
    fun createFromCurrent(context: Context, name: String): String {
        ensureInitialized(context)
        val cleanName = validateNewName(context, name)
        val id = "user_" + UUID.randomUUID().toString()
        val array = readProfiles(context).put(
            profileJson(id, cleanName, false, snapshotCurrent(context))
        )
        writeProfiles(context, array)
        return id
    }

    @Synchronized
    fun duplicate(context: Context, sourceId: String, name: String): String {
        ensureInitialized(context)
        val cleanName = validateNewName(context, name)
        val source = findProfile(readProfiles(context), sourceId)
            ?: throw IllegalArgumentException("Исходный профиль не найден")
        val values = JSONObject(source.optJSONObject("values")?.toString() ?: "{}")
        val id = "user_" + UUID.randomUUID().toString()
        writeProfiles(
            context,
            readProfiles(context).put(profileJson(id, cleanName, false, values))
        )
        return id
    }

    @Synchronized
    fun delete(context: Context, profileId: String) {
        ensureInitialized(context)
        val array = readProfiles(context)
        val index = findProfileIndex(array, profileId)
        require(index >= 0) { "Профиль не найден" }
        require(!array.getJSONObject(index).optBoolean("builtIn", false)) {
            "Встроенные профили нельзя удалить"
        }

        val replacement = JSONArray()
        for (i in 0 until array.length()) {
            if (i != index) replacement.put(array.getJSONObject(i))
        }
        writeProfiles(context, replacement)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingId = prefs.getString(KEY_PENDING_ID, null)
        val activeWasDeleted = prefs.getString(KEY_ACTIVE_ID, null) == profileId
        val editor = prefs.edit()
        if (pendingId == profileId) editor.remove(KEY_PENDING_ID)
        // Do not label Comics active until its settings have actually been applied.
        // If the deleted profile was active, queue a safe built-in fallback for
        // the next page, unless another valid profile is already queued.
        if (activeWasDeleted && (pendingId == null || pendingId == profileId)) {
            editor.putString(KEY_PENDING_ID, "comics")
        }
        editor.apply()
    }

    @Synchronized
    fun resetBuiltIns(context: Context) {
        ensureInitialized(context)
        val defaults = JSONArray(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_BUILTIN_DEFAULTS, "[]")
        )
        val current = readProfiles(context)
        val customProfiles = JSONArray()
        for (i in 0 until current.length()) {
            val profile = current.optJSONObject(i) ?: continue
            if (!profile.optBoolean("builtIn", false)) customProfiles.put(profile)
        }
        for (i in 0 until defaults.length()) {
            customProfiles.put(JSONObject(defaults.getJSONObject(i).toString()))
        }
        writeProfiles(context, customProfiles)

        // When a built-in profile is currently active, reset its stored values
        // and queue those defaults for the next page. Preserve a user's other
        // explicit pending selection.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getString(KEY_PENDING_ID, null)
        val active = prefs.getString(KEY_ACTIVE_ID, null)
        if (pending == null && (active == "comics" || active == "books")) {
            prefs.edit().putString(KEY_PENDING_ID, active).apply()
        }
    }

    /**
     * Called by ScreenTranslateService only after a non-empty OCR/translation
     * result is ready for the next page.
     */
    @JvmStatic
    @Synchronized
    fun applyPendingProfile(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingId = prefs.getString(KEY_PENDING_ID, null) ?: return
        try {
            val profile = findProfile(readProfiles(context), pendingId)
            if (profile == null) {
                prefs.edit().remove(KEY_PENDING_ID).apply()
                Log.w(TAG, "Pending profile no longer exists: " + pendingId)
                return
            }
            val values = profile.optJSONObject("values")
                ?: throw IllegalStateException("Пустые параметры профиля")
            applySnapshot(context, values)

            // Refresh both already-created overlay renderers after all values
            // have been committed, so the next translated page uses one set.
            notifyHostSettingsChanged()
            prefs.edit()
                .putString(KEY_ACTIVE_ID, pendingId)
                .remove(KEY_PENDING_ID)
                .apply()
            Log.i(TAG, "Applied profile on next page: " + profile.optString("name"))
        } catch (t: Throwable) {
            Log.e(TAG, "Could not apply pending display profile", t)
            prefs.edit().remove(KEY_PENDING_ID).apply()
        }
    }

    private fun createBooksSnapshot(context: Context): JSONObject {
        val host = JSONObject()
        for ((key, defaultValue) in hawkDefaults) {
            val value = when (key) {
                KEY_FIXED_FONT_SIZE -> true
                "HAWK_MANGA_MODE" -> false
                "HAWK_MANGA_FONT" -> true
                "HAWK_MANGA_BACKGROUND" -> true
                "HAWK_VERTICAL" -> false
                "HAWK_UPCASE" -> false
                "HAWK_CENTER" -> false
                "HAWK_BG_STROKE" -> false
                "HAWK_FONT_FULL" -> "Roboto-Medium"
                "HAWK_FONT_SIZE" -> 20
                "HAWK_BG_TRANS" -> 236
                "text_style" -> 0
                else -> defaultValue
            }
            host.put(key, value)
        }
        val bubble = host.optInt("HAWK_BG_TRANS", 236)
        val freePercent = context.getSharedPreferences(OCR_PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FREE_ALPHA_PERCENT, ((bubble * 100 + 127) / 255).coerceIn(0, 100))
        val ocr = JSONObject()
            .put(KEY_FREE_ALPHA_ENABLED, false)
            .put(KEY_FREE_ALPHA_PERCENT, freePercent)
        return JSONObject().put("host", host).put("ocr", ocr)
    }

    private fun migrateFixedFontFlag(context: Context, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_FIXED_FONT_MIGRATED, false)) return

        fun migrate(source: JSONArray): JSONArray {
            val updated = JSONArray()
            for (i in 0 until source.length()) {
                val original = source.optJSONObject(i) ?: continue
                val profile = JSONObject(original.toString())
                val values = JSONObject(profile.optJSONObject("values")?.toString() ?: "{}")
                val host = JSONObject(values.optJSONObject("host")?.toString() ?: "{}")
                if (!host.has(KEY_FIXED_FONT_SIZE)) {
                    host.put(KEY_FIXED_FONT_SIZE, profile.optString("id") == "books")
                }
                values.put("host", host)
                profile.put("values", values)
                updated.put(profile)
            }
            return updated
        }

        val profiles = migrate(readProfiles(context))
        val defaults = migrate(JSONArray(prefs.getString(KEY_BUILTIN_DEFAULTS, "[]") ?: "[]"))
        prefs.edit()
            .putString(KEY_PROFILES, profiles.toString())
            .putString(KEY_BUILTIN_DEFAULTS, defaults.toString())
            .putBoolean(KEY_FIXED_FONT_MIGRATED, true)
            .apply()

        val activeId = prefs.getString(KEY_ACTIVE_ID, "comics") ?: "comics"
        val activeValue = findProfile(profiles, activeId)
            ?.optJSONObject("values")?.optJSONObject("host")
            ?.optBoolean(KEY_FIXED_FONT_SIZE, activeId == "books")
            ?: (activeId == "books")
        try {
            writeHawk(KEY_FIXED_FONT_SIZE, activeValue)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not migrate active fixed-font preference", t)
        }
    }

    private fun snapshotCurrent(context: Context): JSONObject {
        val host = JSONObject()
        for ((key, defaultValue) in hawkDefaults) {
            val value = if (key == "text_style_custom_bg_color" ||
                key == "text_style_custom_text_color"
            ) {
                readStyleColor(key, defaultValue)
            } else {
                readHawk(key, defaultValue)
            }
            host.put(key, value)
        }

        val bubble = (host.opt("HAWK_BG_TRANS") as? Number)?.toInt() ?: 236
        val ocrPrefs = context.getSharedPreferences(OCR_PREFS, Context.MODE_PRIVATE)
        val freePercent = ocrPrefs.getInt(
            KEY_FREE_ALPHA_PERCENT,
            ((bubble * 100 + 127) / 255).coerceIn(0, 100)
        )
        val ocr = JSONObject()
            .put(KEY_FREE_ALPHA_ENABLED, ocrPrefs.getBoolean(KEY_FREE_ALPHA_ENABLED, false))
            .put(KEY_FREE_ALPHA_PERCENT, freePercent)
        return JSONObject().put("host", host).put("ocr", ocr)
    }

    private fun readStyleColor(key: String, defaultValue: Any): Any {
        return try {
            val owner = Class.forName(
                "com.tap_to_translate.snap_translate.domain.main.widgets.custom.WidgetChooseStyleNew"
            )
            val methodName = if (key == "text_style_custom_bg_color") {
                "getCustomBackgroundColor"
            } else {
                "getCustomTextColor"
            }
            methodName.let { owner.getMethod(it).invoke(null) } ?: defaultValue
        } catch (t: Throwable) {
            readHawk(key, defaultValue)
        }
    }

    private fun readHawk(key: String, defaultValue: Any): Any {
        return try {
            val owner = Class.forName("qh.n")
            owner.getMethod("a", Any::class.java, String::class.java)
                .invoke(null, defaultValue, key) ?: defaultValue
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read host preference " + key, t)
            defaultValue
        }
    }

    private fun writeHawk(key: String, value: Any) {
        val owner = Class.forName("qh.n")
        owner.getMethod("b", Any::class.java, String::class.java)
            .invoke(null, value, key)
    }

    private fun applySnapshot(context: Context, snapshot: JSONObject) {
        val host = snapshot.optJSONObject("host")
            ?: throw IllegalArgumentException("Параметры оформления не найдены")
        for ((key, defaultValue) in hawkDefaults) {
            val value = when (defaultValue) {
                is Boolean -> host.opt(key) as? Boolean ?: defaultValue
                is Int -> (host.opt(key) as? Number)?.toInt() ?: defaultValue
                is String -> host.optString(key, defaultValue)
                else -> defaultValue
            }
            writeHawk(key, value)
        }

        // The host manga dialog performs a one-time migration of these flags.
        // Mark it complete after the profile's explicit values have been written,
        // otherwise its first later opening could overwrite the selected preset.
        writeHawk("HAWK_MANGA_DEFAULTS_V1", true)

        val ocr = snapshot.optJSONObject("ocr") ?: JSONObject()
        context.getSharedPreferences(OCR_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FREE_ALPHA_ENABLED, ocr.optBoolean(KEY_FREE_ALPHA_ENABLED, false))
            .putInt(KEY_FREE_ALPHA_PERCENT, ocr.optInt(KEY_FREE_ALPHA_PERCENT, 100).coerceIn(0, 100))
            .apply()
    }

    private fun notifyHostSettingsChanged() {
        try {
            val notifier = Class.forName(
                "com.tap_to_translate.snap_translate.hymt2.DisplayProfilesHostNotifier"
            )
            notifier.getMethod("postDisplayRefresh").invoke(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Host settings event could not be posted; overlay refresh may be delayed", t)
        }
    }

    private fun validateNewName(context: Context, value: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "Введите название профиля" }
        require(name.length <= 40) { "Название слишком длинное (максимум 40 символов)" }
        val duplicate = listProfiles(context).any { it.name.equals(name, ignoreCase = true) }
        require(!duplicate) { "Профиль с таким названием уже существует" }
        return name
    }

    private fun profileJson(
        id: String,
        name: String,
        builtIn: Boolean,
        values: JSONObject
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("builtIn", builtIn)
        .put("values", JSONObject(values.toString()))

    private fun readProfiles(context: Context): JSONArray {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, "[]") ?: "[]"
        return JSONArray(json)
    }

    private fun writeProfiles(context: Context, profiles: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, profiles.toString()).apply()
    }

    private fun findProfile(array: JSONArray, id: String): JSONObject? {
        val index = findProfileIndex(array, id)
        return if (index >= 0) array.optJSONObject(index) else null
    }

    private fun findProfileIndex(array: JSONArray, id: String): Int {
        for (index in 0 until array.length()) {
            if (array.optJSONObject(index)?.optString("id") == id) return index
        }
        return -1
    }
}
