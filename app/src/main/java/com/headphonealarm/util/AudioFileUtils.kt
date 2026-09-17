package com.headphonealarm.util

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 自定义音频文件（通过 SAF 选择）的辅助方法。
 */
object AudioFileUtils {

    /** 取得持久化读权限，Uri 在重启后依然可用 */
    fun persistPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** 查询文件名用于展示 */
    fun displayName(context: Context, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            } ?: uri.lastPathSegment.orEmpty().ifBlank { "自定义音频" }
        } catch (e: Exception) {
            uri.lastPathSegment.orEmpty().ifBlank { "自定义音频" }
        } finally {
            cursor?.close()
        }
    }
}
