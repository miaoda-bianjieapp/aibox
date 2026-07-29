package com.aibox.yuanzuo_ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.util.Locale
import java.util.UUID
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.aibox.yuanzuo_ai/file_picker"
    private val pickRequestCode = 9104
    private val saveRequestCode = 9105
    private val directoryRequestCode = 9106
    private var pendingResult: MethodChannel.Result? = null
    private var pendingSaveBytes: ByteArray? = null
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
                            val source = File(filePath).canonicalFile
                            val cacheRoot = cacheDir.canonicalFile
                            val insideCache = source.path == cacheRoot.path
                                || source.path.startsWith(cacheRoot.path + File.separator)
                            if (!insideCache || !source.isFile) {
                                throw IllegalArgumentException("Source file is outside the application cache")
                            }
                            val savedName = saveFileToDirectory(
                                Uri.parse(directoryUri),
                                source,
                                fileName,
                                mediaType,
                            )
                            runOnUiThread {
                                directorySaveCancellationRequested = false
                                pendingResult = null
                                result.success(savedName)
                            }
                        } catch (exception: Exception) {
                            runOnUiThread {
                                directorySaveCancellationRequested = false
                                pendingResult = null
                                result.error("FILE_SAVE_FAILED", exception.message, null)
                            }
                        }
                    }.start()
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
        ) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingResult
        pendingResult = null
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
            }
            result?.success(directoryUri.toString())
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
    ): String {
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
            source.inputStream().use { input ->
                contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (directorySaveCancellationRequested) {
                            throw InterruptedIOException("File save was cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                } ?: throw IllegalStateException("File destination cannot be written")
            }
        } catch (exception: Exception) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, outputUri) }
            throw exception
        }
        return actualName
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
    }
}
