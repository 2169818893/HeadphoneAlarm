package com.headphonealarm.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 自定义铃声导入。
 *
 * 直接使用 SAF 返回的 `content://` URI 播放有三个坑：
 * 1. 授权可能失效（重启、清理、用户撤销）→ `setDataSource` 直接抛 IOException；
 * 2. 云盘文件（Drive 等）通常不可随机读取，MediaPlayer 需要可 seek 的 fd；
 * 3. 部分 Provider 返回的不是普通文件描述符。
 *
 * 因此选择后立即把内容复制到应用私有目录，播放时使用稳定的本地 `file://` 路径。
 */
object RingtoneImporter {

    private const val DIR_NAME = "ringtones"

    data class Imported(val file: File, val displayName: String)

    fun import(context: Context, uri: Uri): Result<Imported> = runCatching {
        val displayName = AudioFileUtils.displayName(context, uri)
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}_${sanitize(displayName)}")

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: error("无法读取所选文件，请换一个文件试试")

        if (target.length() <= 0L) {
            target.delete()
            error("文件内容为空，无法作为铃声")
        }
        Imported(target, displayName)
    }

    /** 只保留文件名安全字符，避免路径穿越与非法字符 */
    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_', '.')
        return cleaned.take(48).ifBlank { "ringtone" }
    }

    fun toUriString(file: File): String = Uri.fromFile(file).toString()

    fun delete(pathOrUri: String) {
        runCatching {
            val file = if (pathOrUri.startsWith("file://")) File(Uri.parse(pathOrUri).path!!) else File(pathOrUri)
            if (file.exists()) file.delete()
        }
    }
}
