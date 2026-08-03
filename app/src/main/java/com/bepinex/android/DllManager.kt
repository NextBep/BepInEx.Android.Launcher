package com.bepinex.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Manages mod .dll files in per-game BepInEx/plugins/ directories.
 */
class DllManager(private val context: Context) {

    fun getPluginsDir(packageName: String): File =
        BepInExPaths.getPluginsDir(packageName).also { it.mkdirs() }

    fun getInstalledDlls(packageName: String): List<File> {
        val dir = getPluginsDir(packageName)
        return dir.listFiles()
            ?.filter { it.extension.equals("dll", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun importDll(packageName: String, uri: Uri): Result<File> = runCatching {
        val fileName = getFileName(uri) ?: "mod_${System.currentTimeMillis()}.dll"
        if (!fileName.endsWith(".dll", ignoreCase = true)) {
            throw IOException("Only .dll files are supported")
        }

        val pluginsDir = getPluginsDir(packageName)
        val destFile = File(pluginsDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Cannot open file")

        BepInExLog.i("DLL imported: ${destFile.name} (${destFile.length()} bytes)")
        destFile
    }

    fun deleteDll(file: File): Boolean {
        val result = file.delete()
        if (result) BepInExLog.i("DLL deleted: ${file.name}")
        return result
    }

    private fun getFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
