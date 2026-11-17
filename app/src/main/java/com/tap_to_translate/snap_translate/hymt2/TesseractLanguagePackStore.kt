package com.tap_to_translate.snap_translate.hymt2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tesseract traineddata storage. The SAF folder is the persistent copy;
 * the app-private tessdata file is the working copy required by TessBaseAPI.
 * After reinstall the user selects the same folder again and packages are restored.
 */
object TesseractLanguagePackStore {
    private const val TAG = "TapTranslatePacks"
    private const val PREFS = "taptranslate_language_pack_store"
    private const val KEY_TREE_URI = "tesseract_tree_uri"
    private const val MIN_PACK_BYTES = 100L * 1024L
    private const val DOWNLOAD_BASE = "https://github.com/tesseract-ocr/tessdata/raw/main/"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class LanguagePack(val name: String, val code: String)
    data class PackStatus(val internal: Boolean, val shared: Boolean, val bytes: Long)
    private data class SharedItem(val uri: Uri, val size: Long)

    /** Hooked into ca/b2.l(), before the host checks whether traineddata exists. */
    @JvmStatic
    fun beforeLanguageCheck(languageName: String) {
        try {
            val context = currentApplicationContext() ?: return
            val code = codeForLanguage(languageName) ?: return
            val target = internalFile(context, code)
            if (isValidPack(target)) return
            val source = findSharedFile(context, code) ?: return
            copySharedToInternal(context, source, target)
            Log.i(TAG, "Restored Tesseract package " + code + " from shared storage")
        } catch (t: Throwable) {
            // Restore is opportunistic: it must never break the original downloader.
            Log.w(TAG, "Tesseract restore hook failed for " + languageName, t)
        }
    }

    /** Hooked into vh/a after the original download completed and passed size checks. */
    @JvmStatic
    fun persistDownloadedPack(code: String, absolutePath: String) {
        try {
            if (!validCode(code)) return
            val context = currentApplicationContext() ?: return
            if (treeUri(context) == null) return
            val source = File(absolutePath)
            if (!isValidPack(source)) return
            writeSharedFile(context, code, source)
            Log.i(TAG, "Saved Tesseract package " + code + " to shared storage")
        } catch (t: Throwable) {
            // A provider error must not fail an otherwise successful host download.
            Log.e(TAG, "Could not persist Tesseract package " + code, t)
        }
    }

    fun availableLanguages(): List<LanguagePack> {
        return try {
            val host = Class.forName("qh.p")
            @Suppress("UNCHECKED_CAST")
            val names = host.getField("c").get(null) as? Set<Any?> ?: emptySet()
            @Suppress("UNCHECKED_CAST")
            val map = host.getField("x").get(null) as? Map<Any?, Any?> ?: emptyMap()
            val resolver = host.getMethod("b", String::class.java, Map::class.java)
            val result = LinkedHashMap<String, LanguagePack>()
            for (raw in names) {
                val name = raw as? String ?: continue
                val code = resolver.invoke(null, name, map) as? String ?: continue
                if (!validCode(code)) continue
                if (!result.containsKey(code)) result[code] = LanguagePack(name, code)
            }
            result.values.sortedBy { it.name.lowercase(Locale.ROOT) }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read host Tesseract language catalogue", t)
            emptyList()
        }
    }

    fun statuses(context: Context): Map<String, PackStatus> {
        val shared = try { listSharedFiles(context) } catch (t: Throwable) {
            Log.w(TAG, "Could not list shared packages", t)
            emptyMap()
        }
        val local = tessdataDirectory(context).listFiles()
            ?.filter { isValidPack(it) }
            ?.associateBy { it.name.removeSuffix(".traineddata") }
            ?: emptyMap()
        val codes = LinkedHashSet<String>()
        codes.addAll(local.keys)
        codes.addAll(shared.keys)
        val result = LinkedHashMap<String, PackStatus>()
        for (code in codes) {
            val localFile = local[code]
            val sharedItem = shared[code]
            val sharedSize = sharedItem?.size?.takeIf { it > 0L } ?: 0L
            result[code] = PackStatus(
                localFile != null,
                sharedItem != null,
                maxOf(localFile?.length() ?: 0L, sharedSize)
            )
        }
        return result
    }

    fun hasSharedFolder(context: Context): Boolean = treeUri(context) != null

    fun sharedFolderLabel(context: Context): String {
        val uri = treeUri(context) ?: return "Общая папка не выбрана"
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val path = id.substringAfter(':', id)
            if (path.isBlank()) "Корень выбранного хранилища" else path
        } catch (_: Throwable) {
            "Выбранная папка"
        }
    }

    fun attachFolder(context: Context, uri: Uri, flags: Int, callback: (String) -> Unit) {
        io.execute {
            val message = try {
                val granted = flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(granted != 0) { "Проводник не выдал доступ к папке" }
                context.contentResolver.takePersistableUriPermission(uri, granted)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TREE_URI, uri.toString()).apply()
                syncFolder(context.applicationContext)
                "Папка подключена. Пакеты Tesseract синхронизированы."
            } catch (t: Throwable) {
                Log.e(TAG, "Could not attach shared folder", t)
                "Не удалось подключить папку: " + (t.message ?: t.javaClass.simpleName)
            }
            main.post { callback(message) }
        }
    }

    fun download(context: Context, pack: LanguagePack, callback: (String) -> Unit) {
        io.execute {
            val message = try {
                require(validCode(pack.code)) { "Недопустимый код языка" }
                check(treeUri(context) != null) {
                    "Сначала выбери общую папку для постоянного хранения."
                }
                val directory = tessdataDirectory(context)
                if (!directory.exists() && !directory.mkdirs()) error("Не удалось создать tessdata.")
                val target = internalFile(context, pack.code)
                val temporary = File(directory, pack.code + ".traineddata.download")
                temporary.delete()
                val connection = URL(DOWNLOAD_BASE + pack.code + ".traineddata")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 25000
                    connection.readTimeout = 90000
                    connection.setRequestProperty("User-Agent", "TapTranslate-LanguagePackManager")
                    connection.connect()
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        error("Сервер вернул HTTP " + connection.responseCode)
                    }
                    val expected = connection.contentLengthLong
                    val copied = connection.inputStream.use { input ->
                        FileOutputStream(temporary, false).use { output ->
                            val count = copyStream(input, output)
                            output.fd.sync()
                            count
                        }
                    }
                    require(copied >= MIN_PACK_BYTES) { "Получен слишком маленький файл: " + copied + " байт." }
                    if (expected > 0L && copied != expected) {
                        error("Размер не совпал: получено " + copied + " из " + expected + " байт.")
                    }
                    replaceFile(temporary, target)
                    writeSharedFile(context, pack.code, target)
                    "Пакет " + pack.name + " загружен и сохранён в общей папке (" + formatBytes(copied) + ")."
                } finally {
                    connection.disconnect()
                    temporary.delete()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Tesseract download failed: " + pack.code, t)
                "Ошибка загрузки " + pack.name + ": " + (t.message ?: t.javaClass.simpleName)
            }
            main.post { callback(message) }
        }
    }

    fun restoreFromShared(context: Context, code: String, callback: (String) -> Unit) {
        io.execute {
            val message = try {
                require(validCode(code)) { "Недопустимый код языка" }
                val source = findSharedFile(context, code) ?: error("Пакет " + code + " не найден.")
                copySharedToInternal(context, source, internalFile(context, code))
                "Пакет " + code + " восстановлен в рабочую папку."
            } catch (t: Throwable) {
                Log.e(TAG, "Tesseract restore failed: " + code, t)
                "Ошибка восстановления " + code + ": " + (t.message ?: t.javaClass.simpleName)
            }
            main.post { callback(message) }
        }
    }

    fun saveLocalToShared(context: Context, code: String, callback: (String) -> Unit) {
        io.execute {
            val message = try {
                require(validCode(code)) { "Недопустимый код языка" }
                val local = internalFile(context, code)
                check(isValidPack(local)) { "Локальный пакет не найден." }
                writeSharedFile(context, code, local)
                "Пакет " + code + " сохранён в общей папке."
            } catch (t: Throwable) {
                Log.e(TAG, "Tesseract save failed: " + code, t)
                "Ошибка сохранения " + code + ": " + (t.message ?: t.javaClass.simpleName)
            }
            main.post { callback(message) }
        }
    }

    fun delete(context: Context, code: String, callback: (String) -> Unit) {
        io.execute {
            val message = try {
                require(validCode(code)) { "Недопустимый код языка" }
                val local = internalFile(context, code)
                if (local.exists() && !local.delete()) error("Не удалось удалить рабочую копию.")
                val shared = findSharedFile(context, code)
                if (shared != null &&
                    !DocumentsContract.deleteDocument(context.contentResolver, shared)
                ) error("Не удалось удалить постоянную копию.")
                "Пакет " + code + " удалён из рабочей и общей папок."
            } catch (t: Throwable) {
                Log.e(TAG, "Tesseract delete failed: " + code, t)
                "Ошибка удаления " + code + ": " + (t.message ?: t.javaClass.simpleName)
            }
            main.post { callback(message) }
        }
    }

    private fun syncFolder(context: Context) {
        val shared = listSharedFiles(context)
        for ((code, item) in shared) {
            val local = internalFile(context, code)
            if (!isValidPack(local) || (item.size > 0L && local.length() != item.size)) {
                copySharedToInternal(context, item.uri, local)
            }
        }
        // Preserve host-downloaded files that existed before the folder was selected.
        val localFiles = tessdataDirectory(context).listFiles() ?: emptyArray()
        for (file in localFiles) {
            if (!isValidPack(file)) continue
            val code = file.name.removeSuffix(".traineddata")
            if (validCode(code) && !shared.containsKey(code)) writeSharedFile(context, code, file)
        }
    }

    private fun copySharedToInternal(context: Context, source: Uri, target: File) {
        val parent = target.parentFile ?: error("Нет каталога рабочей копии.")
        if (!parent.exists() && !parent.mkdirs()) error("Не удалось создать tessdata.")
        val temporary = File(parent, target.name + ".restore")
        temporary.delete()
        val input = context.contentResolver.openInputStream(source)
            ?: error("Не удалось прочитать пакет в общей папке.")
        val copied = input.use { stream ->
            FileOutputStream(temporary, false).use { output ->
                val count = copyStream(stream, output)
                output.fd.sync()
                count
            }
        }
        require(copied >= MIN_PACK_BYTES) { "Пакет в общей папке неполный или повреждён." }
        replaceFile(temporary, target)
    }

    private fun writeSharedFile(context: Context, code: String, source: File) {
        val tree = treeUri(context) ?: error("Общая папка не выбрана.")
        require(validCode(code) && isValidPack(source)) { "Локальный пакет отсутствует или повреждён." }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val tempName = code + ".traineddata.tmp"
        findChild(context, tree, tempName)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it)
        }
        val tempUri = DocumentsContract.createDocument(
            context.contentResolver, root, "application/octet-stream", tempName
        ) ?: error("Проводник не разрешил создать временный файл.")
        try {
            val copied = FileInputStream(source).use { input ->
                val output = context.contentResolver.openOutputStream(tempUri, "wt")
                    ?: error("Не удалось записать файл в общую папку.")
                output.use { copyStream(input, it) }
            }
            require(copied == source.length()) { "Копирование в общую папку неполное." }

            val old = findChild(context, tree, code + ".traineddata")
            if (old != null && !DocumentsContract.deleteDocument(context.contentResolver, old)) {
                error("Не удалось заменить старую общую копию.")
            }

            val renamed = try {
                DocumentsContract.renameDocument(context.contentResolver, tempUri, code + ".traineddata")
            } catch (t: Throwable) {
                Log.w(TAG, "SAF provider does not support rename; use copy fallback", t)
                null
            }
            if (renamed == null) {
                val finalUri = DocumentsContract.createDocument(
                    context.contentResolver, root, "application/octet-stream", code + ".traineddata"
                ) ?: error("Проводник не поддерживает сохранение итогового файла.")
                val finalCopied = context.contentResolver.openInputStream(tempUri)?.use { input ->
                    val output = context.contentResolver.openOutputStream(finalUri, "wt")
                        ?: error("Не удалось записать итоговый файл.")
                    output.use { copyStream(input, it) }
                } ?: error("Не удалось прочитать временный файл.")
                if (finalCopied != source.length()) {
                    DocumentsContract.deleteDocument(context.contentResolver, finalUri)
                    error("Итоговая копия неполная.")
                }
                DocumentsContract.deleteDocument(context.contentResolver, tempUri)
            }
        } catch (t: Throwable) {
            try { DocumentsContract.deleteDocument(context.contentResolver, tempUri) } catch (_: Throwable) { }
            throw t
        }
    }

    private fun listSharedFiles(context: Context): Map<String, SharedItem> {
        val tree = treeUri(context) ?: return emptyMap()
        val id = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = LinkedHashMap<String, SharedItem>()
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (mimeCol >= 0 && cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                if (!name.endsWith(".traineddata")) continue
                val code = name.removeSuffix(".traineddata")
                if (!validCode(code) || idCol < 0) continue
                val idValue = cursor.getString(idCol) ?: continue
                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                result[code] = SharedItem(
                    DocumentsContract.buildDocumentUriUsingTree(tree, idValue), size
                )
            }
        }
        return result
    }

    private fun findSharedFile(context: Context, code: String): Uri? = listSharedFiles(context)[code]?.uri

    private fun findChild(context: Context, tree: Uri, name: String): Uri? {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) return null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == name) {
                    val idValue = cursor.getString(idCol) ?: return null
                    return DocumentsContract.buildDocumentUriUsingTree(tree, idValue)
                }
            }
        }
        return null
    }

    private fun treeUri(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        return try { Uri.parse(value) } catch (_: Throwable) { null }
    }

    private fun internalFile(context: Context, code: String): File =
        File(tessdataDirectory(context), code + ".traineddata")

    private fun tessdataDirectory(context: Context): File {
        val base = try {
            val hostPrefs = Class.forName("qh.n")
            hostPrefs.getMethod("a", Any::class.java, String::class.java)
                .invoke(null, "", "filePath") as? String
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resolve host filePath; use filesDir", t)
            null
        }
        val root = if (base.isNullOrBlank()) context.filesDir else File(base)
        return File(root, "tessdata")
    }

    private fun codeForLanguage(languageName: String): String? {
        val host = Class.forName("qh.p")
        @Suppress("UNCHECKED_CAST")
        val map = host.getField("x").get(null) as? Map<Any?, Any?> ?: return null
        val resolver = host.getMethod("b", String::class.java, Map::class.java)
        val code = resolver.invoke(null, languageName, map) as? String ?: return null
        return code.takeIf { validCode(it) }
    }

    private fun validCode(code: String): Boolean = code.matches(Regex("[a-z0-9_]{2,16}"))
    private fun isValidPack(file: File): Boolean =
        file.isFile && file.name.endsWith(".traineddata") && file.length() >= MIN_PACK_BYTES

    private fun replaceFile(source: File, destination: File) {
        if (destination.exists() && !destination.delete()) {
            source.delete()
            error("Не удалось заменить " + destination.name + ".")
        }
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun currentApplicationContext(): Context? {
        return try {
            val thread = Class.forName("android.app.ActivityThread")
            val application = thread.getDeclaredMethod("currentApplication")
                .invoke(null) as? android.app.Application
            application?.applicationContext
        } catch (t: Throwable) {
            Log.w(TAG, "Application context is unavailable", t)
            null
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) {
                output.write(buffer, 0, count)
                total += count.toLong()
            }
        }
        output.flush()
        return total
    }

    private fun formatBytes(value: Long): String =
        if (value >= 1024L * 1024L) {
            String.format(Locale.US, "%.1f МБ", value / (1024.0 * 1024.0))
        } else {
            value.toString() + " Б"
        }
}
