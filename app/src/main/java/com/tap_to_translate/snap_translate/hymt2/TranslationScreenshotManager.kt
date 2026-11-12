package com.tap_to_translate.snap_translate.hymt2

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Creates a composite of the last MediaProjection frame and the host's
 * translated-text layer. The toolbar is a sibling of that layer and is
 * deliberately not drawn into the exported image.
 */
object TranslationScreenshotManager {
    private const val TAG = "TapTranslateScreenshot"
    private const val PREFS = "taptranslate_screenshot_settings"
    private const val KEY_TREE_URI = "screenshot_tree_uri"
    private const val KEY_FORMAT = "screenshot_format"
    private const val FORMAT_PNG = "png"
    private const val FORMAT_JPEG = "jpeg"
    private const val JPEG_QUALITY = 92

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TapTranslateScreenshot").apply { isDaemon = true }
    }

    /** Entry point used by the native result-overlay click listener. */
    @JvmStatic
    fun captureFromOverlay(resultOverlay: Any) {
        val root = resultOverlay as? View
        if (root == null) {
            toast(null, "Не удалось получить окно перевода")
            return
        }

        try {
            val prefs = root.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val treeValue = prefs.getString(KEY_TREE_URI, null)
            if (treeValue.isNullOrBlank()) {
                toast(root.context, "Сначала выберите папку для скриншотов в настройках")
                return
            }

            val service = findScreenTranslateService(resultOverlay)
            if (service == null) {
                toast(root.context, "Не удалось получить службу перевода")
                return
            }
            val capture = readField(service, "c")
            val source = readField(capture, "j") as? Bitmap
            if (source == null || source.isRecycled) {
                toast(root.context, "Исходный кадр ещё не готов. Повторите снимок")
                return
            }

            // n is the transparent full-screen translation layer. The top
            // toolbar is its sibling, so it is omitted by design.
            val textLayer = readField(resultOverlay, "n") as? View
            if (textLayer == null || textLayer.visibility != View.VISIBLE ||
                textLayer.width <= 0 || textLayer.height <= 0) {
                toast(root.context, "Слой перевода сейчас недоступен")
                return
            }

            val output = composeFrame(source, textLayer)
            val format = prefs.getString(KEY_FORMAT, FORMAT_PNG) ?: FORMAT_PNG
            val treeUri = Uri.parse(treeValue)
            ioExecutor.execute {
                saveToTree(root.context.applicationContext, output, treeUri, format)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Screenshot capture failed", t)
            toast(root.context, "Не удалось подготовить скриншот: " + (t.message ?: "ошибка"))
        }
    }

    private fun findScreenTranslateService(root: Any): Any? {
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>()
        )

        fun visit(value: Any?, depth: Int): Any? {
            if (value == null || depth > 5 || !visited.add(value)) return null
            if (value.javaClass.name ==
                "com.tap_to_translate.snap_translate.domain.main.service.ScreenTranslateService"
            ) return value

            for (field in value.javaClass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val fieldType = field.type
                if (fieldType.isPrimitive ||
                    fieldType == String::class.java ||
                    android.view.View::class.java.isAssignableFrom(fieldType) ||
                    Context::class.java.isAssignableFrom(fieldType) ||
                    Bitmap::class.java.isAssignableFrom(fieldType)
                ) continue

                try {
                    field.isAccessible = true
                    val nested = field.get(value)
                    val found = visit(nested, depth + 1)
                    if (found != null) return found
                } catch (_: Throwable) {
                    // Continue scanning other captured callback fields.
                }
            }
            return null
        }

        return visit(root, 0)
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        return try {
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
        } catch (_: Throwable) {
            try {
                instance.javaClass.getField(name).get(instance)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun composeFrame(source: Bitmap, textLayer: View): Bitmap {
        // ImageReader may reuse the host bitmap while the next frame is arriving.
        // Work from an immutable snapshot to avoid a partially updated screenshot.
        val sourceCopy = source.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IOException("Не удалось скопировать исходный кадр")
        try {
            val width = readHostDimension("a", sourceCopy.width).coerceIn(1, sourceCopy.width)
            val height = readHostDimension("b", sourceCopy.height).coerceIn(1, sourceCopy.height)

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                sourceCopy,
                android.graphics.Rect(0, 0, width, height),
                android.graphics.Rect(0, 0, width, height),
                bitmapPaint
            )

            val position = IntArray(2)
            textLayer.getLocationOnScreen(position)
            canvas.save()
            canvas.translate(position[0].toFloat(), position[1].toFloat())
            textLayer.draw(canvas)
            canvas.restore()

            return output
        } finally {
            if (!sourceCopy.isRecycled) sourceCopy.recycle()
        }
    }

    private fun readHostDimension(fieldName: String, fallback: Int): Int {
        return try {
            val owner = Class.forName("ca.g2")
            owner.getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Using bitmap dimension fallback for ca.g2." + fieldName, t)
            fallback
        }
    }

    private fun saveToTree(context: Context, bitmap: Bitmap, treeUri: Uri, format: String) {
        var created: Uri? = null
        try {
            synchronized(this) {
                val resolver = context.contentResolver
                val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
                val isJpeg = format.equals(FORMAT_JPEG, ignoreCase = true)
                val extension = if (isJpeg) "jpg" else "png"
                val mimeType = if (isJpeg) "image/jpeg" else "image/png"
                val number = nextFreeNumber(resolver, childrenUri)
                val fileName = String.format(Locale.US, "%03d.%s", number, extension)

                created = DocumentsContract.createDocument(
                    resolver, parentUri, mimeType, fileName
                ) ?: throw IOException("Провайдер папки не создал файл " + fileName)

                val stream = resolver.openOutputStream(created!!, "w")
                    ?: throw IOException("Не удалось открыть файл " + fileName)
                val compressed = stream.use {
                    if (isJpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                    else bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                if (!compressed) throw IOException("Не удалось записать изображение")

                toast(context, "Скриншот сохранён: " + fileName)
                Log.i(TAG, "Saved " + fileName + " to " + treeUri)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not save screenshot", t)
            try {
                created?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            } catch (_: Throwable) {
            }
            toast(context, "Ошибка сохранения скриншота: " + (t.message ?: "проверьте папку"))
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun nextFreeNumber(
        resolver: ContentResolver,
        childrenUri: Uri
    ): Long {
        val used = HashSet<Long>()
        val pattern = Regex("^(\\d+)\\.(png|jpe?g)$", RegexOption.IGNORE_CASE)
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (column >= 0) {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(column) ?: continue
                    val match = pattern.matchEntire(name) ?: continue
                    match.groupValues[1].toLongOrNull()?.let { used.add(it) }
                }
            }
        } ?: throw IOException("Не удалось прочитать список файлов в выбранной папке")

        val highestUsed = used.maxOrNull() ?: 0L
        if (highestUsed >= Int.MAX_VALUE) throw IOException("Слишком много скриншотов")
        return highestUsed + 1L
    }

    private fun toast(context: Context?, message: String) {
        if (context == null) {
            Log.w(TAG, message)
            return
        }
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
