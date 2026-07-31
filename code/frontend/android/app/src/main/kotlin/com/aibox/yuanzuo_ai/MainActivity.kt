package com.aibox.yuanzuo_ai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.util.Locale
import java.util.UUID
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.aibox.yuanzuo_ai/file_picker"
    private val pickRequestCode = 9104
    private val saveRequestCode = 9105
    private val directoryRequestCode = 9106
    private val pathSaveRequestCode = 9107
    private val imagePickRequestCode = 9108
    private var pendingResult: MethodChannel.Result? = null
    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveFilePath: String? = null
    private var pendingSaveFileName: String? = null
    private var pendingMultiple = false
    private var pendingMaxFiles = 1
    @Volatile
    private var directorySaveCancellationRequested = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        cleanupPickedFileCache()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                if (call.method == "cancelSaveFileToDirectory") {
                    directorySaveCancellationRequested = true
                    result.success(null)
                    return@setMethodCallHandler
                }
                if (pendingResult != null) {
                    result.error("FILE_OPERATION_IN_PROGRESS", "A file operation is already in progress", null)
                    return@setMethodCallHandler
                }
                if (call.method == "openFile") {
                    val filePath = call.argument<String>("filePath")
                    if (filePath.isNullOrBlank()) {
                        result.error("SOURCE_FILE_INVALID", "文件缓存不可用", null)
                        return@setMethodCallHandler
                    }
                    val fileName = call.argument<String>("fileName") ?: "document"
                    val requestedMediaType =
                        call.argument<String>("mediaType") ?: "application/octet-stream"
                    try {
                        val source = requireCachedSourceFile(filePath)
                        val sharedFile = copyToExternalViewerCache(source, fileName)
                        val contentUri = FileProvider.getUriForFile(
                            this,
                            "$packageName.file_provider",
                            sharedFile,
                        )
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                contentUri,
                                resolveViewMediaType(fileName, requestedMediaType),
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newUri(contentResolver, fileName, contentUri)
                        }
                        val handlers = packageManager.queryIntentActivities(
                            viewIntent,
                            PackageManager.MATCH_DEFAULT_ONLY,
                        )
                        if (handlers.isEmpty()) {
                            result.error(
                                "FILE_VIEWER_UNAVAILABLE",
                                "No application can open this file",
                                null,
                            )
                            return@setMethodCallHandler
                        }
                        startActivity(Intent.createChooser(viewIntent, "选择打开方式"))
                        result.success(true)
                    } catch (exception: ActivityNotFoundException) {
                        result.error(
                            "FILE_VIEWER_UNAVAILABLE",
                            "No application can open this file",
                            null,
                        )
                    } catch (exception: SecurityException) {
                        Log.e(
                            fileLogTag,
                            "External file open denied type=${exception.javaClass.simpleName}"
                        )
                        result.error(
                            "FILE_OPEN_DENIED",
                            "File access was denied",
                            mapOf("cause" to exception.javaClass.simpleName),
                        )
                    } catch (exception: IllegalArgumentException) {
                        result.error(
                            "SOURCE_FILE_INVALID",
                            "文件缓存不可用",
                            mapOf("cause" to exception.javaClass.simpleName),
                        )
                    } catch (exception: Exception) {
                        Log.e(
                            fileLogTag,
                            "External file open failed type=${exception.javaClass.simpleName}"
                        )
                        result.error(
                            "FILE_OPEN_FAILED",
                            "Unable to open file",
                            mapOf("cause" to exception.javaClass.simpleName),
                        )
                    }
                    return@setMethodCallHandler
                }
                if (call.method == "saveFile") {
                    val bytes = call.argument<ByteArray>("bytes")
                    if (bytes == null || bytes.isEmpty()) {
                        result.error("SAVE_EMPTY", "File content is empty", null)
                        return@setMethodCallHandler
                    }
                    val fileName = call.argument<String>("fileName") ?: "download"
                    val mediaType = call.argument<String>("mediaType") ?: "application/octet-stream"
                    pendingResult = result
                    pendingSaveBytes = bytes
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mediaType
                        putExtra(Intent.EXTRA_TITLE, fileName)
                    }
                    startActivityForResult(intent, saveRequestCode)
                    return@setMethodCallHandler
                }
                if (call.method == "saveFileToDirectory") {
                    val directoryUri = call.argument<String>("directoryUri")
                    val filePath = call.argument<String>("filePath")
                    if (directoryUri.isNullOrBlank() || filePath.isNullOrBlank()) {
                        result.error("SAVE_ARGUMENT_INVALID", "Directory and source file are required", null)
                        return@setMethodCallHandler
                    }
                    val fileName = call.argument<String>("fileName") ?: "download"
                    val mediaType = call.argument<String>("mediaType") ?: "application/octet-stream"
                    directorySaveCancellationRequested = false
                    pendingResult = result
                    Thread {
                        try {
                            val source = requireCachedSourceFile(filePath)
                            val saved = saveFileToDirectory(
                                Uri.parse(directoryUri),
                                source,
                                fileName,
                                mediaType,
                            )
                            runOnUiThread {
                                directorySaveCancellationRequested = false
                                pendingResult = null
                                result.success(saved)
                            }
                        } catch (exception: Exception) {
                            val errorCode = fileSaveErrorCode(exception)
                            Log.e(
                                fileLogTag,
                                "Directory save failed code=$errorCode type=${exception.javaClass.simpleName}"
                            )
                            runOnUiThread {
                                directorySaveCancellationRequested = false
                                pendingResult = null
                                result.error(
                                    errorCode,
                                    fileSaveErrorMessage(errorCode),
                                    mapOf("cause" to exception.javaClass.simpleName),
                                )
                            }
                        }
                    }.start()
                    return@setMethodCallHandler
                }
                if (call.method == "saveFileFromPath") {
                    val filePath = call.argument<String>("filePath")
                    if (filePath.isNullOrBlank()) {
                        result.error("SOURCE_FILE_INVALID", "下载缓存文件无效", null)
                        return@setMethodCallHandler
                    }
                    try {
                        requireCachedSourceFile(filePath)
                    } catch (exception: Exception) {
                        result.error(
                            "SOURCE_FILE_INVALID",
                            "下载缓存文件无效",
                            mapOf("cause" to exception.javaClass.simpleName),
                        )
                        return@setMethodCallHandler
                    }
                    val fileName = call.argument<String>("fileName") ?: "download"
                    val mediaType = call.argument<String>("mediaType") ?: "application/octet-stream"
                    pendingResult = result
                    pendingSaveFilePath = filePath
                    pendingSaveFileName = fileName
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mediaType
                        putExtra(Intent.EXTRA_TITLE, fileName)
                    }
                    startActivityForResult(intent, pathSaveRequestCode)
                    return@setMethodCallHandler
                }
                if (call.method == "pickDirectory") {
                    pendingResult = result
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    }
                    startActivityForResult(intent, directoryRequestCode)
                    return@setMethodCallHandler
                }
                if (call.method == "pickImages") {
                    pendingResult = result
                    pendingMultiple = true
                    pendingMaxFiles =
                        (call.argument<Int>("maxFiles") ?: 1).coerceIn(1, 10)
                    try {
                        val request = PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                        val intent = if (pendingMaxFiles == 1) {
                            ActivityResultContracts.PickVisualMedia()
                                .createIntent(this, request)
                        } else {
                            ActivityResultContracts.PickMultipleVisualMedia(pendingMaxFiles)
                                .createIntent(this, request)
                        }
                        startActivityForResult(intent, imagePickRequestCode)
                    } catch (exception: Exception) {
                        pendingResult = null
                        pendingMultiple = false
                        pendingMaxFiles = 1
                        Log.e(
                            fileLogTag,
                            "Photo picker launch failed type=${exception.javaClass.simpleName}"
                        )
                        result.error(
                            "IMAGE_PICKER_UNAVAILABLE",
                            "Unable to open the system photo picker",
                            mapOf("cause" to exception.javaClass.simpleName),
                        )
                    }
                    return@setMethodCallHandler
                }
                if (call.method != "pickFile" && call.method != "pickFiles") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                pendingResult = result
                pendingMultiple = call.method == "pickFiles"
                pendingMaxFiles = (call.argument<Int>("maxFiles") ?: 1).coerceIn(1, 10)
                val mimeTypes = call.argument<List<String>>("mimeTypes")?.toTypedArray()
                    ?: arrayOf("*/*")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
                    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, pendingMultiple)
                }
                startActivityForResult(intent, pickRequestCode)
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (
            requestCode != pickRequestCode
            && requestCode != saveRequestCode
            && requestCode != directoryRequestCode
            && requestCode != pathSaveRequestCode
            && requestCode != imagePickRequestCode
        ) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingResult
        pendingResult = null
        if (requestCode == imagePickRequestCode) {
            val maxFiles = pendingMaxFiles
            pendingMultiple = false
            pendingMaxFiles = 1
            if (resultCode != Activity.RESULT_OK || data == null) {
                result?.success(null)
                return
            }
            val uris = if (maxFiles == 1) {
                listOfNotNull(
                    ActivityResultContracts.PickVisualMedia()
                        .parseResult(resultCode, data)
                )
            } else {
                ActivityResultContracts.PickMultipleVisualMedia(maxFiles)
                    .parseResult(resultCode, data)
                    .take(maxFiles)
            }
            completePickedUris(result, uris, multiple = true)
            return
        }
        if (requestCode == directoryRequestCode) {
            val directoryUri = data?.data
            if (resultCode != Activity.RESULT_OK || directoryUri == null) {
                result?.success(null)
                return
            }
            val permissionFlags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            runCatching {
                contentResolver.takePersistableUriPermission(directoryUri, permissionFlags)
            }.onFailure { exception ->
                Log.w(
                    fileLogTag,
                    "Directory permission was not persisted type=${exception.javaClass.simpleName}"
                )
            }
            result?.success(directoryUri.toString())
            return
        }
        if (requestCode == pathSaveRequestCode) {
            val filePath = pendingSaveFilePath
            val requestedName = pendingSaveFileName
            pendingSaveFilePath = null
            pendingSaveFileName = null
            val outputUri = data?.data
            if (
                resultCode != Activity.RESULT_OK
                || outputUri == null
                || filePath.isNullOrBlank()
            ) {
                result?.success(null)
                return
            }
            Thread {
                try {
                    val source = requireCachedSourceFile(filePath)
                    val sizeBytes = writeFileToUri(source, outputUri, cancellable = false)
                    val savedName = queryDisplayName(outputUri)
                        ?: normalizeSavedFileName(requestedName ?: source.name)
                    runOnUiThread {
                        result?.success(savedFileResult(savedName, outputUri, sizeBytes))
                    }
                } catch (exception: Exception) {
                    val errorCode = fileSaveErrorCode(exception)
                    Log.e(
                        fileLogTag,
                        "Single file save failed code=$errorCode type=${exception.javaClass.simpleName}"
                    )
                    runOnUiThread {
                        result?.error(
                            errorCode,
                            fileSaveErrorMessage(errorCode),
                            mapOf("cause" to exception.javaClass.simpleName),
                        )
                    }
                }
            }.start()
            return
        }
        if (requestCode == saveRequestCode) {
            val bytes = pendingSaveBytes
            pendingSaveBytes = null
            if (resultCode != Activity.RESULT_OK || data?.data == null || bytes == null) {
                result?.success(false)
                return
            }
            try {
                contentResolver.openOutputStream(data.data!!)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("File destination is unavailable")
                result?.success(true)
            } catch (exception: Exception) {
                result?.error("FILE_SAVE_FAILED", exception.message, null)
            }
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            result?.success(null)
            return
        }

        val multiple = pendingMultiple
        val maxFiles = pendingMaxFiles
        pendingMultiple = false
        pendingMaxFiles = 1
        val uris = buildList {
            val clipData = data.clipData
            if (clipData != null) {
                for (index in 0 until minOf(clipData.itemCount, maxFiles)) {
                    add(clipData.getItemAt(index).uri)
                }
            } else if (data.data != null) {
                add(data.data!!)
            }
        }
        if (uris.isEmpty()) {
            result?.success(null)
            return
        }
        completePickedUris(result, uris, multiple)
    }

    private fun completePickedUris(
        result: MethodChannel.Result?,
        uris: List<Uri>,
        multiple: Boolean,
    ) {
        if (uris.isEmpty()) {
            result?.success(null)
            return
        }
        Thread {
            val copiedFiles = mutableListOf<File>()
            try {
                val files = uris.map { uri ->
                    var name = "unnamed-file"
                    contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            name = cursor.getString(
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            )
                        }
                    }
                    val directory = File(cacheDir, "picked-files").apply { mkdirs() }
                    val target = File(directory, UUID.randomUUID().toString())
                    copiedFiles.add(target)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("File content is unavailable")
                    mapOf(
                        "name" to name,
                        "mediaType" to (
                            contentResolver.getType(uri)
                                ?: "application/octet-stream"
                        ),
                        "path" to target.absolutePath,
                        "sizeBytes" to target.length(),
                    )
                }
                runOnUiThread {
                    result?.success(if (multiple) files else files.first())
                }
            } catch (exception: Exception) {
                copiedFiles.forEach { file -> runCatching { file.delete() } }
                runOnUiThread {
                    result?.error("FILE_READ_FAILED", exception.message, null)
                }
            }
        }.start()
    }

    private fun saveFileToDirectory(
        treeUri: Uri,
        source: File,
        requestedName: String,
        mediaType: String,
    ): Map<String, Any> {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val existingNames = mutableSetOf<String>()
        contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            while (nameColumn >= 0 && cursor.moveToNext()) {
                cursor.getString(nameColumn)?.let { name ->
                    existingNames.add(name.lowercase(Locale.ROOT))
                }
            }
        }

        val safeName = normalizeSavedFileName(requestedName)
        val actualName = uniqueFileName(safeName, existingNames)
        val actualMediaType = mediaType.ifBlank { "application/octet-stream" }
        val outputUri = DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            actualMediaType,
            actualName,
        ) ?: throw IllegalStateException("File destination is unavailable")
        try {
            val sizeBytes = writeFileToUri(source, outputUri, cancellable = true)
            val savedName = queryDisplayName(outputUri) ?: actualName
            return savedFileResult(savedName, outputUri, sizeBytes)
        } catch (exception: Exception) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, outputUri) }
            throw exception
        }
    }

    private fun writeFileToUri(
        source: File,
        outputUri: Uri,
        cancellable: Boolean,
    ): Long {
        var written = 0L
        source.inputStream().use { input ->
            contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (cancellable && directorySaveCancellationRequested) {
                        throw InterruptedIOException("File save was cancelled")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
                output.flush()
            } ?: throw FileNotFoundException("File destination cannot be written")
        }
        return written
    }

    private fun requireCachedSourceFile(filePath: String): File {
        val source = File(filePath)
        val cacheRoots = buildList {
            add(cacheDir)
            add(codeCacheDir)
            add(filesDir)
            externalCacheDirs.filterNotNull().forEach(::add)
        }
        val insideCache = cacheRoots.any { root -> isInsideDirectory(source, root) }
        if (!insideCache || !source.isFile) {
            Log.w(
                fileLogTag,
                "Source cache validation failed insideCache=$insideCache exists=${source.exists()} isFile=${source.isFile}"
            )
            throw IllegalArgumentException("Source file is outside the application cache")
        }
        return source
    }

    private fun isInsideDirectory(source: File, root: File): Boolean {
        val sourcePaths = normalizedPaths(source)
        val rootPaths = normalizedPaths(root)
        return sourcePaths.any { sourcePath ->
            rootPaths.any { rootPath ->
                sourcePath == rootPath
                    || sourcePath.startsWith(rootPath + File.separator)
            }
        }
    }

    private fun normalizedPaths(file: File): Set<String> {
        return buildSet {
            add(file.absoluteFile.normalize().path)
            runCatching { file.canonicalFile.path }.getOrNull()?.let(::add)
        }
    }

    private fun copyToExternalViewerCache(
        source: File,
        requestedName: String,
    ): File {
        val directory = File(cacheDir, externalViewerCacheDirectory).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("External viewer cache cannot be created")
            }
        }
        pruneExternalViewerCache(directory)
        val safeName = normalizeSavedFileName(requestedName)
        val sessionDirectory = File(directory, UUID.randomUUID().toString()).apply {
            if (!mkdirs()) {
                throw IllegalStateException("External viewer session cannot be created")
            }
        }
        val target = File(sessionDirectory, safeName)
        source.copyTo(target, overwrite = false)
        return target
    }

    private fun pruneExternalViewerCache(directory: File) {
        val expiry = System.currentTimeMillis() - externalViewerCacheLifetimeMillis
        directory.listFiles()
            ?.filter { file -> file.lastModified() < expiry }
            ?.forEach { file -> runCatching { file.deleteRecursively() } }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column < 0) null else cursor.getString(column)
        }
    }

    private fun resolveViewMediaType(
        fileName: String,
        requestedMediaType: String,
    ): String {
        val normalized = requestedMediaType.trim()
        if (
            normalized.isNotEmpty()
            && normalized != "application/octet-stream"
            && normalized != "*/*"
        ) {
            return normalized
        }
        val extension = fileName
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: normalized.ifBlank { "application/octet-stream" }
    }

    private fun savedFileResult(
        name: String,
        uri: Uri,
        sizeBytes: Long,
    ): Map<String, Any> {
        return mapOf(
            "name" to name,
            "uri" to uri.toString(),
            "sizeBytes" to sizeBytes,
        )
    }

    private fun fileSaveErrorCode(exception: Exception): String {
        return when (exception) {
            is InterruptedIOException -> "FILE_SAVE_CANCELLED"
            is SecurityException -> "DIRECTORY_PERMISSION_DENIED"
            is FileNotFoundException -> "DESTINATION_UNAVAILABLE"
            is IllegalArgumentException -> "SOURCE_FILE_INVALID"
            else -> "FILE_SAVE_FAILED"
        }
    }

    private fun fileSaveErrorMessage(code: String): String {
        return when (code) {
            "FILE_SAVE_CANCELLED" -> "文件保存已取消"
            "DIRECTORY_PERMISSION_DENIED" -> "无法写入所选文件夹，请改用系统单文件保存"
            "DESTINATION_UNAVAILABLE" -> "目标位置不可用，请选择其他文件夹"
            "SOURCE_FILE_INVALID" -> "下载缓存文件无效，请重新下载"
            else -> "文件写入失败，请选择其他保存位置"
        }
    }

    private fun normalizeSavedFileName(value: String): String {
        val leafName = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .trim()
            .ifEmpty { "download" }
        if (leafName.length <= 240) return leafName
        val extensionIndex = leafName.lastIndexOf('.')
        if (extensionIndex <= 0 || extensionIndex >= leafName.length - 1) {
            return leafName.take(240)
        }
        val extension = leafName.substring(extensionIndex).take(40)
        return leafName.substring(0, extensionIndex)
            .take(240 - extension.length) + extension
    }

    private fun uniqueFileName(name: String, existingNames: Set<String>): String {
        if (!existingNames.contains(name.lowercase(Locale.ROOT))) return name
        val extensionIndex = name.lastIndexOf('.')
        val baseName = if (extensionIndex > 0) name.substring(0, extensionIndex) else name
        val extension = if (extensionIndex > 0) name.substring(extensionIndex) else ""
        var index = 1
        while (true) {
            val suffix = "($index)"
            val trimmedBase = baseName.take((240 - extension.length - suffix.length).coerceAtLeast(1))
            val candidate = "$trimmedBase$suffix$extension"
            if (!existingNames.contains(candidate.lowercase(Locale.ROOT))) return candidate
            index++
        }
    }

    private fun cleanupPickedFileCache() {
        File(cacheDir, "picked-files").listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
        File(cacheDir, externalViewerCacheDirectory)
            .takeIf(File::exists)
            ?.let(::pruneExternalViewerCache)
    }

    companion object {
        private const val fileLogTag = "YuanzuoFile"
        private const val externalViewerCacheDirectory = "external-viewer"
        private const val externalViewerCacheLifetimeMillis = 24 * 60 * 60 * 1000L
    }
}
