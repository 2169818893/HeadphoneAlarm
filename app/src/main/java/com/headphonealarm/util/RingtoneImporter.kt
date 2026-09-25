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
    private const val MAX_RINGTONE_BYTES = 50L * 1024 * 1024

    data class Imported(val file: File, val displayName: String)

    fun import(context: Context, uri: Uri): Result<Imported> = runCatching {
        val displayName = AudioFileUtils.displayName(context, uri)
        val dir = File(context.filesDir, DIR_NAME)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建铃声保存目录" }
        val target = File.createTempFile("ringtone_", "_${sanitize(displayName)}", dir)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_RINGTONE_BYTES) { "铃声文件不能超过 50 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取所选文件，请换一个文件试试")
            require(target.length() > 0L) { "文件内容为空，无法作为铃声" }
            Imported(target, displayName)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** 只保留文件名安全字符，避免路径穿越与非法字符 */
    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_', '.')
        return cleaned.take(48).ifBlank { "ringtone" }
    }

    fun toUriString(file: File): String = Uri.fromFile(file).toString()

    /**
     * Backups include files/ringtones, but a restored file:// URI can still point to the old
     * device's app-data path. Only relocate paths that have our exact package/files/ringtones
     * layout and an importer-generated filename; never redirect arbitrary file/content URIs.
     */
    fun resolveOwnedUri(context: Context, pathOrUri: String): Uri? =
        runCatching { ownedFile(context, pathOrUri)?.let(Uri::fromFile) }.getOrNull()

    /**
     * 安全删除自定义铃声文件：仅当文件是本应用私有 ringtones 目录的**直接子文件**、
     * 且不在 [referencedUris]（仍被闹钟引用）中时才删除，杜绝路径穿越与误删共享文件。
     */
    fun deleteOwnedRingtone(context: Context, pathOrUri: String?, referencedUris: Set<String>) {
        if (pathOrUri.isNullOrBlank()) return
        runCatching {
            val file = ownedFile(context, pathOrUri) ?: return
            // Compare actual files, not URI spellings (file:///a/../b, encoded characters,
            // restored old paths, etc.). One alarm may still reference the same file.
            if (referencedUris.any { ownedFile(context, it) == file }) return
            file.delete()
        }
    }

    private fun ownedFile(context: Context, pathOrUri: String): File? {
        val uri = Uri.parse(pathOrUri)
        if (uri.scheme != null && uri.scheme != "file") return null
        if (!uri.authority.isNullOrEmpty()) return null
        val path = if (uri.scheme == "file") uri.path else pathOrUri
        if (path.isNullOrBlank()) return null
        val dir = File(context.filesDir, DIR_NAME).canonicalFile
        val original = File(path).canonicalFile
        if (original.parentFile == dir && original.isFile) return original

        // Only Android's standard credential-protected app-data roots are eligible for
        // relocation. A missing arbitrary path with the same basename must not gain ownership.
        val legacy = Regex("^/data/(?:data|user/[0-9]+)/" +
            Regex.escape(context.packageName) + "/files/ringtones/(ringtone_[^/]+)$")
            .matchEntire(path)?.groupValues?.get(1) ?: return null
        if (legacy == "." || legacy == "..") return null
        return File(dir, legacy).canonicalFile.takeIf { it.parentFile == dir && it.isFile }
    }
}
