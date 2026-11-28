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
import com.tap_to_translate.snap_translate.hymt2.ocr.RtDetrLiveBridge
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.Collections
import java.util.WeakHashMap
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
    private const val KEY_JPEG_QUALITY = "screenshot_jpeg_quality"
    private const val DEFAULT_JPEG_QUALITY = 92

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TapTranslateScreenshot").apply { isDaemon = true }
    }

    private data class PendingCapture(
        val context: Context,
        val treeUri: Uri,
        val format: String,
        val jpegQuality: Int,
        val overlay: WeakReference<View>?,
        var timeout: Runnable? = null,
        @Volatile var timedOut: Boolean = false
    )

    // Weak service keys: a pending capture must not keep a stopped service alive.
    private val pendingCaptures = Collections.synchronizedMap(
        WeakHashMap<Any, PendingCapture>()
    )
    @Volatile private var activeOverlayRef: WeakReference<View>? = null

    /** Remember the current translation window without keeping it alive. */
    @JvmStatic
    fun registerOverlay(resultOverlay: Any?) {
        val view = resultOverlay as? View ?: return
        activeOverlayRef = WeakReference(view)
    }

    private fun isOverlayVisible(view: View): Boolean =
        view.isAttachedToWindow && view.visibility == View.VISIBLE

    /** Entry point used by the native result-overlay click listener. */
    @JvmStatic
    fun captureFromOverlay(resultOverlay: Any) {
        val root = resultOverlay as? View
        if (root == null) {
            toast(null, "Не удалось получить окно перевода")
            return
        }

        try {
            registerOverlay(resultOverlay)
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
            val jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY).coerceIn(50, 100)
            val treeUri = Uri.parse(treeValue)
            ioExecutor.execute {
                saveToTree(root.context.applicationContext, output, treeUri, format, jpegQuality)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Screenshot capture failed", t)
            toast(root.context, "Не удалось подготовить скриншот: " + (t.message ?: "ошибка"))
        }
    }


    /**
     * Entry point for the independent floating screenshot button.
     *
     * The host keeps its latest ImageReader bitmap in xh.c.j, but that bitmap
     * is only populated after a capture has been requested. Request a fresh
     * MediaProjection frame here; the xh.a callback is patched to hand that
     * frame to consumePendingFrame() before the host starts OCR.
     */
    @JvmStatic
    fun captureFromService(service: Any) {
        val context = service as? Context
        if (context == null) {
            toast(null, "Не удалось получить службу перевода")
            return
        }

        try {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val treeValue = prefs.getString(KEY_TREE_URI, null)
            if (treeValue.isNullOrBlank()) {
                toast(context, "Сначала выберите папку для скриншотов в настройках")
                return
            }

            val capture = resolveCaptureController(service)
                ?: throw IllegalStateException("Не удалось получить контроллер захвата экрана")

            synchronized(pendingCaptures) {
                val previous = pendingCaptures[service]
                if (previous != null && !previous.timedOut) {
                    toast(context, "Предыдущий снимок ещё готовится")
                    return
                }
                previous?.timeout?.let { mainHandler.removeCallbacks(it) }
                if (previous != null) pendingCaptures.remove(service)
            }

            val activeOverlay = activeOverlayRef?.get()
                ?.takeIf { isOverlayVisible(it) }
                ?.let { WeakReference(it) }
            val request = PendingCapture(
                appContext,
                Uri.parse(treeValue),
                prefs.getString(KEY_FORMAT, FORMAT_PNG) ?: FORMAT_PNG,
                prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY).coerceIn(50, 100),
                activeOverlay
            )
            synchronized(pendingCaptures) {
                pendingCaptures[service] = request
            }

            val timeout = Runnable {
                val stillPending = synchronized(pendingCaptures) {
                    pendingCaptures[service] === request
                }
                if (stillPending) {
                    request.timedOut = true
                    Log.w(TAG, "Timed out waiting for the independent MediaProjection frame")
                    toast(appContext, "Кадр экрана пока не получен. Повторите снимок")
                }
            }
            request.timeout = timeout
            mainHandler.postDelayed(timeout, 60_000L)

            try {
                requestFreshFrame(capture)
                toast(context, "Создаю снимок экрана…")
            } catch (t: Throwable) {
                mainHandler.removeCallbacks(timeout)
                synchronized(pendingCaptures) {
                    if (pendingCaptures[service] === request) pendingCaptures.remove(service)
                }
                throw t
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Floating screenshot request failed", t)
            toast(context, "Не удалось запросить снимок: " + (t.message ?: "ошибка"))
        }
    }

    /**
     * Called by the host's ImageReader callback before it dispatches the frame
     * to vh.j/OCR. Returning true consumes the frame as a source-only screenshot.
     */
    @JvmStatic
    fun consumePendingFrame(service: Any?, source: Bitmap?): Boolean {
        if (service == null || source == null || source.isRecycled) return false

        val request = synchronized(pendingCaptures) {
            pendingCaptures.remove(service)
        } ?: return false
        request.timeout?.let { mainHandler.removeCallbacks(it) }

        // ImageReader reuses its bitmap. Take an immutable snapshot before returning
        // from the host callback, then draw the overlay on the main thread.
        val snapshot = try {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Не удалось скопировать исходный кадр")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not snapshot source frame", t)
            toast(request.context, "Не удалось подготовить скриншот: " + (t.message ?: "ошибка"))
            return true
        }

        val finishCapture = Runnable {
            var output: Bitmap? = null
            try {
                val overlay = request.overlay?.get()
                val textLayer = overlay
                    ?.takeIf { isOverlayVisible(it) }
                    ?.let { readField(it, "n") as? View }
                    ?.takeIf {
                        it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
                    }

                output = composeFrame(snapshot, textLayer)
                val prepared = output
                    ?: throw IOException("Не удалось собрать изображение")
                ioExecutor.execute {
                    saveToTree(
                        request.context,
                        prepared,
                        request.treeUri,
                        request.format,
                        request.jpegQuality
                    )
                }
                output = null // ownership transferred to the save task
            } catch (t: Throwable) {
                Log.e(TAG, "Could not prepare screenshot frame", t)
                output?.let { if (!it.isRecycled) it.recycle() }
                toast(request.context, "Не удалось подготовить скриншот: " + (t.message ?: "ошибка"))
            } finally {
                if (!snapshot.isRecycled) snapshot.recycle()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            finishCapture.run()
        } else {
            mainHandler.post(finishCapture)
        }
        // Consume only this explicitly requested frame; do not start OCR/translation.
        return true
    }

    private fun resolveCaptureController(service: Any): Any? {
        readField(service, "c")?.let { return it }
        return try {
            val hostClass = Class.forName(
                "com.tap_to_translate.snap_translate.domain.main.service.ScreenTranslateService"
            )
            val factory = hostClass.getDeclaredMethod("e", hostClass).apply {
                isAccessible = true
            }
            factory.invoke(null, service)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not initialize the host capture controller", t)
            null
        }
    }

    private fun requestFreshFrame(capture: Any) {
        val cropRectClass = Class.forName("com.google.android.gms.internal.ads.mk0")
        val method = capture.javaClass.getDeclaredMethod("d", cropRectClass).apply {
            isAccessible = true
        }
        // null retains the host's current capture bounds; no new crop is applied.
        method.invoke(capture, null)
    }

    /** Resume a pending source-only screenshot after Android grants projection access. */
    @JvmStatic
    fun onProjectionPermissionReady(service: Any?) {
        if (service == null) return
        val request = synchronized(pendingCaptures) { pendingCaptures[service] } ?: return
        try {
            val capture = resolveCaptureController(service)
                ?: throw IllegalStateException("Контроллер захвата экрана недоступен")
            requestFreshFrame(capture)
            Log.i(TAG, "Resumed pending source-only screenshot after projection permission")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not resume screenshot after projection permission", t)
            toast(request.context, "Не удалось продолжить снимок: " + (t.message ?: "ошибка"))
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

    private fun composeFrame(source: Bitmap, textLayer: View?): Bitmap {
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

            // Draw the translated text only when the host currently has a
            // visible, measured translation layer. Otherwise keep the source
            // image untouched (the floating button also works before OCR).
            if (textLayer != null) {
                val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val overlayCanvas = Canvas(overlay)
                    val position = IntArray(2)
                    textLayer.getLocationOnScreen(position)
                    overlayCanvas.save()
                    overlayCanvas.translate(position[0].toFloat(), position[1].toFloat())
                    textLayer.draw(overlayCanvas)
                    overlayCanvas.restore()

                    val clipped = RtDetrLiveBridge.clipBubbleOverlay(overlay, 0, 0)
                    Log.i(TAG, "Screenshot translation layer rendered; BubblePop masks applied=$clipped")
                    canvas.drawBitmap(overlay, 0f, 0f, bitmapPaint)
                } finally {
                    if (!overlay.isRecycled) overlay.recycle()
                }
            }

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

    private fun saveToTree(
        context: Context,
        bitmap: Bitmap,
        treeUri: Uri,
        format: String,
        jpegQuality: Int
    ) {
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
                    if (isJpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)
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
