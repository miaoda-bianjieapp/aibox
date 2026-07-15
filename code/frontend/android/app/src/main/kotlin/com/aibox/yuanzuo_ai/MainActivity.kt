package com.aibox.yuanzuo_ai

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.aibox.yuanzuo_ai/file_picker"
    private val pickRequestCode = 9104
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                if (call.method != "pickFile") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                if (pendingResult != null) {
                    result.error("PICK_IN_PROGRESS", "A file picker is already open", null)
                    return@setMethodCallHandler
                }
                pendingResult = result
                val mimeTypes = call.argument<List<String>>("mimeTypes")?.toTypedArray()
                    ?: arrayOf("*/*")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
                    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
                startActivityForResult(intent, pickRequestCode)
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != pickRequestCode) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingResult
        pendingResult = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            result?.success(null)
            return
        }

        val uri = data.data!!
        try {
            var name = "unnamed-file"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("File content is unavailable")
            result?.success(
                mapOf(
                    "name" to name,
                    "mediaType" to (contentResolver.getType(uri) ?: "application/octet-stream"),
                    "bytes" to bytes,
                )
            )
        } catch (exception: Exception) {
            result?.error("FILE_READ_FAILED", exception.message, null)
        }
    }
}
