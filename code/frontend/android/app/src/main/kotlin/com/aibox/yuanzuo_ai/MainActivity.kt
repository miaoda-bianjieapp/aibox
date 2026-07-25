package com.aibox.yuanzuo_ai

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.aibox.yuanzuo_ai/file_picker"
    private val pickRequestCode = 9104
    private val saveRequestCode = 9105
    private var pendingResult: MethodChannel.Result? = null
    private var pendingSaveBytes: ByteArray? = null
    private var pendingMultiple = false
    private var pendingMaxFiles = 1

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        cleanupPickedFileCache()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                if (pendingResult != null) {
                    result.error("PICK_IN_PROGRESS", "A file picker is already open", null)
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
        if (requestCode != pickRequestCode && requestCode != saveRequestCode) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingResult
        pendingResult = null
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

    private fun cleanupPickedFileCache() {
        File(cacheDir, "picked-files").listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
    }
}
