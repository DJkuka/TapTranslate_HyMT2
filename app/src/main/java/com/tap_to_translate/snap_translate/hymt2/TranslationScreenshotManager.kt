package com.tap_to_translate.snap_translate.hymt2

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
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
            val service = findScreenTranslateService(resultOverlay)
            if (service == null) {
                toast(root.context, "Не удалось получить службу перевода")
                return
            }
            // Use the same fresh-frame path as the independent camera button.
            // This lets us hide the editor toolbar and floating controls before
            // MediaProjection captures the page.
            captureFromService(service)
        } catch (t: Throwable) {
            Log.e(TAG, "Screenshot capture from editor failed", t)
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
                    restoreCaptureUi(request)
                    Log.w(TAG, "Timed out waiting for the independent MediaProjection frame")
                    toast(appContext, "Кадр экрана пока не получен. Повторите снимок")
                }
            }
            request.timeout = timeout
            mainHandler.postDelayed(timeout, 60_000L)

            try {
                activeOverlay?.get()?.let {
                    TranslationRegionEditor.setCaptureUiHidden(it, true)
                }
                FloatingScreenshotButton.onCaptureStarted(context)
                // Allow the hidden toolbar and host floating icon to leave the
                // composed display frame before requesting the next projection frame.
                mainHandler.postDelayed({
                    val stillPending = synchronized(pendingCaptures) {
                        pendingCaptures[service] === request && !request.timedOut
                    }
                    if (!stillPending) return@postDelayed
                    try {
                        requestFreshFrame(capture)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Could not request clean screenshot frame", t)
                        mainHandler.removeCallbacks(timeout)
                        synchronized(pendingCaptures) {
                            if (pendingCaptures[service] === request) pendingCaptures.remove(service)
                        }
                        restoreCaptureUi(request)
                        toast(context, "Не удалось запросить снимок: " + (t.message ?: "ошибка"))
                    }
                }, 120L)
                toast(context, "Создаю снимок экрана…")
            } catch (t: Throwable) {
                restoreCaptureUi(request)
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
            restoreCaptureUi(request)
            toast(request.context, "Не удалось подготовить скриншот: " + (t.message ?: "ошибка"))
            return true
        }

        restoreCaptureUi(request)
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

                output = composeFrame(overlay?.context ?: request.context, snapshot, textLayer, overlay)
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

    private fun restoreCaptureUi(request: PendingCapture) {
        try {
            request.overlay?.get()?.let {
                TranslationRegionEditor.setCaptureUiHidden(it, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not restore editor toolbar after screenshot", t)
        } finally {
            FloatingScreenshotButton.onCaptureFinished(request.context)
        }
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
            FloatingScreenshotButton.onCaptureStarted(request.context)
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

    /**
     * Returns the visible system-bar insets in the same pixel coordinate space
     * as the MediaProjection frame. Insets are read at capture time so rotation
     * and the current navigation mode are respected.
     *
     * Android 11+ exposes status/navigation insets separately. Older versions
     * keep the previous full-frame behavior instead of guessing bar dimensions.
     */
    private fun resolveSystemBarCrop(
        context: Context,
        overlayRoot: View?,
        frameWidth: Int,
        frameHeight: Int
    ): Rect {
        val fullFrame = Rect(0, 0, frameWidth, frameHeight)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fullFrame

        return try {
            // Use the context and WindowInsets of the actual attached overlay.
            // applicationContext has no window of its own and can report zero
            // insets even while the display contains system bars.
            val windowContext = overlayRoot?.context ?: context
            val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return fullFrame
            val metrics = windowManager.currentWindowMetrics
            val systemBars = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            val metricWindowInsets = metrics.windowInsets
            val metricVisible = metricWindowInsets.getInsets(systemBars)
            val metricStable = metricWindowInsets.getInsetsIgnoringVisibility(systemBars)

            val rootInsets = overlayRoot?.rootWindowInsets
            val rootVisible = rootInsets?.getInsets(systemBars)
            val rootStable = rootInsets?.getInsetsIgnoringVisibility(systemBars)

            // Include stable insets as well as visible ones. Transient/immersive
            // bar visibility must not make a reserved black system strip survive.
            var leftInset = maxOf(
                metricVisible.left, metricStable.left,
                rootVisible?.left ?: 0, rootStable?.left ?: 0
            )
            var topInset = maxOf(
                metricVisible.top, metricStable.top,
                rootVisible?.top ?: 0, rootStable?.top ?: 0
            )
            var rightInset = maxOf(
                metricVisible.right, metricStable.right,
                rootVisible?.right ?: 0, rootStable?.right ?: 0
            )
            var bottomInset = maxOf(
                metricVisible.bottom, metricStable.bottom,
                rootVisible?.bottom ?: 0, rootStable?.bottom ?: 0
            )

            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (overlayRoot?.display ?: windowManager.defaultDisplay).getRealMetrics(displayMetrics)

            val referenceWidth = displayMetrics.widthPixels
                .takeIf { it > 0 } ?: metrics.bounds.width()
            val referenceHeight = displayMetrics.heightPixels
                .takeIf { it > 0 } ?: metrics.bounds.height()
            if (referenceWidth <= 0 || referenceHeight <= 0) return fullFrame

            // A few vendor overlay windows return empty WindowInsets. Use the
            // framework's own system-bar dimensions as a guarded fallback.
            var resourceFallback = false
            if (leftInset == 0 && topInset == 0 && rightInset == 0 && bottomInset == 0) {
                val systemResources = android.content.res.Resources.getSystem()
                fun systemDimension(name: String): Int {
                    val id = systemResources.getIdentifier(name, "dimen", "android")
                    return if (id != 0) {
                        try { systemResources.getDimensionPixelSize(id).coerceAtLeast(0) }
                        catch (_: Throwable) { 0 }
                    } else 0
                }

                topInset = systemDimension("status_bar_height")
                val navBottom = systemDimension("navigation_bar_height")
                val navLandscape = systemDimension("navigation_bar_height_landscape")
                bottomInset = maxOf(navBottom, navLandscape)
                // Some landscape devices put the navigation bar on the right edge.
                val navWidth = systemDimension("navigation_bar_width")
                if (referenceWidth > referenceHeight && navBottom == 0 && navLandscape == 0 && navWidth > 0) {
                    rightInset = navWidth
                }
                resourceFallback = topInset > 0 || bottomInset > 0 || rightInset > 0
            }

            if (leftInset == 0 && topInset == 0 && rightInset == 0 && bottomInset == 0) {
                Log.w(
                    TAG,
                    "No system-bar insets available; keeping full frame " +
                        "frame=${frameWidth}x${frameHeight} display=${referenceWidth}x${referenceHeight}"
                )
                return fullFrame
            }

            val scaleX = frameWidth.toFloat() / referenceWidth.toFloat()
            val scaleY = frameHeight.toFloat() / referenceHeight.toFloat()
            val left = (leftInset * scaleX).toInt().coerceIn(0, frameWidth)
            val top = (topInset * scaleY).toInt().coerceIn(0, frameHeight)
            val right = (rightInset * scaleX).toInt().coerceIn(0, frameWidth)
            val bottom = (bottomInset * scaleY).toInt().coerceIn(0, frameHeight)

            val crop = Rect(
                left,
                top,
                (frameWidth - right).coerceAtLeast(left),
                (frameHeight - bottom).coerceAtLeast(top)
            )
            if (crop.width() < 1 || crop.height() < 1) {
                Log.w(TAG, "System-bar crop would remove the full frame; keeping original screenshot")
                return fullFrame
            }

            Log.i(
                TAG,
                "Cropping system bars: left=$left top=$top right=$right bottom=$bottom " +
                    "fallback=$resourceFallback frame=${frameWidth}x${frameHeight} " +
                    "display=${referenceWidth}x${referenceHeight}"
            )
            crop
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resolve system-bar insets; keeping full screenshot frame", t)
            fullFrame
        }
    }

    private fun composeFrame(context: Context, source: Bitmap, textLayer: View?, overlayRoot: View?): Bitmap {
        // ImageReader may reuse the host bitmap while the next frame is arriving.
        // Work from an immutable snapshot to avoid a partially updated screenshot.
        val sourceCopy = source.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IOException("Не удалось скопировать исходный кадр")
        try {
            val width = readHostDimension("a", sourceCopy.width).coerceIn(1, sourceCopy.width)
            val height = readHostDimension("b", sourceCopy.height).coerceIn(1, sourceCopy.height)

            val cropRect = resolveSystemBarCrop(context, overlayRoot, width, height)
            val outputWidth = cropRect.width()
            val outputHeight = cropRect.height()
            val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val destinationRect = Rect(0, 0, outputWidth, outputHeight)
            val canvas = Canvas(output)
            val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                sourceCopy,
                cropRect,
                destinationRect,
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
                    // Apply the exact same crop as the source frame. Drawing the
                    // overlay in full-frame coordinates first prevents translated
                    // text from shifting when status/navigation bars are removed.
                    canvas.drawBitmap(overlay, cropRect, destinationRect, bitmapPaint)
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
