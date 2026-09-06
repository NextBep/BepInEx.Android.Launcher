package com.bepinex.android.modpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.bepinex.android.BepInExLog
import com.bepinex.android.BepInExPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ModpackMeta(
    val name: String,
    val packageName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val modCount: Int = 0
)

data class ModpackExportProgress(
    val phase: String,
    val currentFile: String? = null,
    val completedFiles: Long = 0,
    val totalFiles: Long = 0,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (completedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            totalFiles > 0 -> (completedFiles.toDouble() / totalFiles).coerceIn(0.0, 1.0).toFloat()
            else -> 0f
        }
}

class ModpackManager {

    companion object {
        const val MODPACK_EXTENSION = "rhp"
        const val MODPACK_MIME_TYPE = "application/octet-stream"

        private val SUPPORTED_MODPACK_EXTENSIONS = setOf("rhp", "zip")

        fun isModpackFileName(fileName: String?): Boolean =
            fileName?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_MODPACK_EXTENSIONS

        fun isModFileName(fileName: String?): Boolean =
            fileName?.substringAfterLast('.', "")?.equals("dll", ignoreCase = true) == true
    }

    fun normalizeModpackName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

    private fun getModpacksDir(packageName: String): File =
        File(BepInExPaths.getGameRootDir(packageName), "modpacks")

    private fun getModpackDir(packageName: String, name: String): File =
        BepInExPaths.getModpackDir(packageName, name)

    private fun getModpackPluginsDir(packageName: String, name: String): File =
        File(getModpackDir(packageName, name), "plugins")

    private fun getModpackConfigDir(packageName: String, name: String): File =
        BepInExPaths.getModpackConfigDir(packageName, name)

    private fun getModpackLogsDir(packageName: String, name: String): File =
        BepInExPaths.getModpackLogsDir(packageName, name)

    private fun getMetaFile(packageName: String, name: String): File =
        File(getModpackDir(packageName, name), "modpack.json")

    // CRUD

    fun listModpacks(packageName: String): List<ModpackMeta> {
        val dir = getModpacksDir(packageName)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readMeta(packageName, it.name) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun createModpack(packageName: String, name: String): ModpackMeta? {
        val safeName = normalizeModpackName(name)
        if (safeName.isEmpty()) return null

        val modpackDir = getModpackDir(packageName, safeName)
        if (modpackDir.exists()) return null

        return try {
            modpackDir.mkdirs()
            getModpackPluginsDir(packageName, safeName).mkdirs()
            getModpackConfigDir(packageName, safeName).mkdirs()
            getModpackLogsDir(packageName, safeName).mkdirs()

            val meta = ModpackMeta(name = safeName, packageName = packageName)
            writeMeta(meta)
            BepInExLog.i("Created modpack: $safeName")
            meta
        } catch (e: Exception) {
            BepInExLog.e("Failed to create modpack: $safeName", e)
            null
        }
    }

    fun deleteModpack(packageName: String, name: String): Boolean {
        val dir = getModpackDir(packageName, name)
        return if (dir.exists()) {
            dir.deleteRecursively().also {
                BepInExLog.i("Deleted modpack: $name")
            }
        } else false
    }

    fun renameModpack(packageName: String, oldName: String, newName: String): Boolean {
        val safeNewName = normalizeModpackName(newName)
        if (safeNewName.isEmpty()) return false
        if (safeNewName == oldName) return true

        val oldDir = getModpackDir(packageName, oldName)
        val newDir = getModpackDir(packageName, safeNewName)
        if (!oldDir.exists() || newDir.exists()) return false

        if (!oldDir.renameTo(newDir)) return false

        try {
            val meta = readMeta(packageName, safeNewName)
                ?: ModpackMeta(name = safeNewName, packageName = packageName)
            writeMeta(meta.copy(name = safeNewName, packageName = packageName))
        } catch (e: Exception) {
            BepInExLog.e("Renamed modpack but failed to update metadata: $safeNewName", e)
        }

        BepInExLog.i("Renamed modpack: $oldName -> $safeNewName")
        return true
    }

    // Mod management

    private fun resolveFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun syncModpackMeta(packageName: String, modpackName: String) {
        val current = readMeta(packageName, modpackName) ?: return
        val real = getModCount(packageName, modpackName)
        if (current.modCount != real) {
            writeMeta(current.copy(modCount = real))
        }
    }

    fun listMods(packageName: String, modpackName: String): List<File> {
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        return if (pluginsDir.isDirectory) {
            pluginsDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
                .toList()
        } else {
            emptyList()
        }
    }

    fun listConfigs(packageName: String, modpackName: String): List<File> {
        val configDir = getModpackConfigDir(packageName, modpackName)
        return configDir.listFiles()?.filter { it.isFile && it.extension == "cfg" } ?: emptyList()
    }

    fun addMod(packageName: String, modpackName: String, sourceFile: File): File? {
        if (!isModFileName(sourceFile.name)) {
            BepInExLog.w("Rejected mod with unsupported extension: ${sourceFile.name}")
            return null
        }
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        pluginsDir.mkdirs()
        val dest = File(pluginsDir, sourceFile.name)
        return try {
            sourceFile.copyTo(dest, overwrite = true).also {
                syncModpackMeta(packageName, modpackName)
                BepInExLog.i("Added mod: ${sourceFile.name}  -> $modpackName")
            }
        } catch (e: Exception) {
            BepInExLog.e("Failed to add mod", e)
            null
        }
    }

    fun addModFromUri(context: Context, packageName: String, modpackName: String, uri: Uri): File? {
        val fileName = resolveFileName(context, uri)
        if (!isModFileName(fileName)) {
            BepInExLog.w("Rejected mod URI with unsupported extension: $fileName")
            return null
        }
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        pluginsDir.mkdirs()
        val dest = File(pluginsDir, fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            syncModpackMeta(packageName, modpackName)
            BepInExLog.i("Added mod from URI: $fileName  -> $modpackName")
            dest
        } catch (e: Exception) {
            BepInExLog.e("Failed to add mod from URI", e)
            null
        }
    }

    fun removeMod(file: File): Boolean {
        return file.delete().also {
            if (it) {
                val pluginsDir = generateSequence(file.parentFile) { it.parentFile }
                    .firstOrNull { it.name == "plugins" }
                val modpackDir = pluginsDir?.parentFile
                val modpacksDir = modpackDir?.parentFile
                val gameRootDir = modpacksDir?.parentFile
                val pkg = gameRootDir?.name ?: ""
                val modpackName = modpackDir?.name ?: ""
                if (pkg.isNotEmpty() && modpackName.isNotEmpty()) {
                    syncModpackMeta(pkg, modpackName)
                }
                BepInExLog.i("Removed mod: ${file.name}")
            }
        }
    }

    fun getModCount(packageName: String, modpackName: String): Int =
        listMods(packageName, modpackName).size

    // Activate / Apply

    fun applyModpack(packageName: String, modpackName: String): Boolean {
        return try {
            restoreRuntimeState(packageName, modpackName)
            BepInExLog.i("Applied modpack: $modpackName  -> active")
            true
        } catch (e: Exception) {
            BepInExLog.e("Failed to apply modpack", e)
            false
        }
    }

    fun clearActiveMods(packageName: String) {
        restoreRuntimeState(packageName, null)
        BepInExLog.i("Cleared active mods (vanilla mode)")
    }

    fun persistRuntimeState(packageName: String, modpackName: String?) {
        val destRoot = stateRoot(packageName, modpackName)
        destRoot.mkdirs()
        copyDirContents(BepInExPaths.getConfigDir(packageName), File(destRoot, "config"))
        copyRuntimeLogs(packageName, File(destRoot, "logs"))
        BepInExLog.i("Persisted runtime cfg/logs -> ${destRoot.absolutePath}")
    }

    fun restoreRuntimeState(packageName: String, modpackName: String?) {
        val srcRoot = stateRoot(packageName, modpackName)
        val bepInExDir = BepInExPaths.getBepInExDir(packageName)
        val pluginsDir = BepInExPaths.getPluginsDir(packageName)
        val configDir = BepInExPaths.getConfigDir(packageName)
        val logsDir = BepInExPaths.getLogsDir(packageName)
        val logFile = BepInExPaths.getLogFile(packageName)

        replaceDir(logsDir)
        if (logFile.exists()) logFile.delete()

        if (!modpackName.isNullOrEmpty()) {
            syncDirContents(getModpackPluginsDir(packageName, modpackName), pluginsDir)
            copyModpackRootContents(srcRoot, bepInExDir)
        } else {
            replaceDir(pluginsDir)
        }
        syncDirContents(File(srcRoot, "config"), configDir)
        BepInExLog.i("Restored runtime cfg from ${srcRoot.absolutePath}")
    }

    private fun stateRoot(packageName: String, modpackName: String?): File =
        if (modpackName.isNullOrEmpty()) {
            BepInExPaths.getVanillaStateDir(packageName)
        } else {
            getModpackDir(packageName, modpackName)
        }

    private fun copyRuntimeLogs(packageName: String, destLogs: File) {
        destLogs.mkdirs()
        BepInExPaths.getLogFile(packageName).takeIf { it.isFile }?.copyTo(
            File(destLogs, "LogOutput.log"), overwrite = true
        )
        copyDirContents(BepInExPaths.getLogsDir(packageName), destLogs)
    }

    private fun replaceDir(dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun copyDirContents(source: File, dest: File) {
        dest.mkdirs()
        if (!source.isDirectory) return
        source.listFiles()?.forEach { child ->
            val target = File(dest, child.name)
            if (child.isDirectory) {
                child.copyRecursively(target, overwrite = true)
            } else {
                child.copyTo(target, overwrite = true)
            }
        }
    }

    private fun syncDirContents(source: File, dest: File) {
        if (!source.isDirectory) {
            replaceDir(dest)
            return
        }

        if (dest.exists() && !dest.isDirectory) dest.delete()
        dest.mkdirs()

        val sourceChildren = source.listFiles()?.associateBy { it.name }.orEmpty()
        dest.listFiles()
            ?.filter { it.name !in sourceChildren }
            ?.forEach { it.deleteRecursively() }

        sourceChildren.values.forEach { child ->
            syncEntry(child, File(dest, child.name))
        }
    }

    private fun syncEntry(source: File, target: File) {
        if (source.isDirectory) {
            syncDirContents(source, target)
            return
        }

        if (target.isFile && filesHaveSameContent(source, target)) return
        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun filesHaveSameContent(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false

        first.inputStream().buffered().use { firstInput ->
            second.inputStream().buffered().use { secondInput ->
                val firstBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val secondBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val firstRead = firstInput.read(firstBuffer)
                    val secondRead = secondInput.read(secondBuffer)
                    if (firstRead != secondRead) return false
                    if (firstRead == -1) return true
                    for (index in 0 until firstRead) {
                        if (firstBuffer[index] != secondBuffer[index]) return false
                    }
                }
            }
        }
    }

    private fun copyModpackRootContents(source: File, bepInExDir: File) {
        if (!source.isDirectory) return

        val runtimeOwned = setOf("modpack.json", "plugins", "config", "logs")
        source.listFiles()
            ?.filter { it.name !in runtimeOwned }
            ?.forEach { child ->
                val target = File(bepInExDir, child.name)
                syncEntry(child, target)
                BepInExLog.i("Restored modpack entry: ${child.name} -> ${target.absolutePath}")
            }
    }

    // Export / Import

    suspend fun exportModpack(
        packageName: String,
        modpackName: String,
        outputFile: File,
        onProgress: suspend (ModpackExportProgress) -> Unit = {}
    ): Boolean {
        val modpackDir = getModpackDir(packageName, modpackName)
        if (!modpackDir.exists()) return false

        val tempFile = File(outputFile.parentFile, ".${outputFile.name}.part")
        return try {
            tempFile.delete()
            var totalFiles = 0L
            var totalBytes = 0L
            for (file in modpackDir.walkTopDown()) {
                currentCoroutineContext().ensureActive()
                if (isExportableFile(modpackDir, file)) {
                    totalFiles++
                    totalBytes += file.length()
                }
            }
            onProgress(ModpackExportProgress("preparing", totalFiles = totalFiles, totalBytes = totalBytes))

            var completedFiles = 0L
            var completedBytes = 0L
            var lastProgressNanos = 0L
            suspend fun reportProgress(force: Boolean = false, currentFile: String? = null) {
                val now = System.nanoTime()
                if (force || now - lastProgressNanos >= 100_000_000L) {
                    lastProgressNanos = now
                    onProgress(
                        ModpackExportProgress(
                            phase = "exporting",
                            currentFile = currentFile,
                            completedFiles = completedFiles,
                            totalFiles = totalFiles,
                            completedBytes = completedBytes,
                            totalBytes = totalBytes
                        )
                    )
                }
            }

            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile)))
            try {
                for (file in modpackDir.walkTopDown()) {
                    currentCoroutineContext().ensureActive()
                    if (!isExportableFile(modpackDir, file)) continue
                    val entryName = "$modpackName/${file.relativeTo(modpackDir).path.replace('\\', '/')}"
                    zos.putNextEntry(ZipEntry(entryName))
                    try {
                        val input = BufferedInputStream(file.inputStream())
                        try {
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                            var read: Int
                            do {
                                currentCoroutineContext().ensureActive()
                                read = input.read(buffer)
                                if (read > 0) {
                                    zos.write(buffer, 0, read)
                                    completedBytes += read
                                    reportProgress(currentFile = file.name)
                                }
                            } while (read >= 0)
                        } finally {
                            input.close()
                        }
                    } finally {
                        zos.closeEntry()
                    }
                    completedFiles++
                    reportProgress(force = true, currentFile = file.name)
                }
            } finally {
                zos.close()
            }

            currentCoroutineContext().ensureActive()
            if (outputFile.exists() && !outputFile.delete()) {
                throw java.io.IOException("Unable to replace existing export: ${outputFile.absolutePath}")
            }
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = false)
                tempFile.delete()
            }
            onProgress(
                ModpackExportProgress(
                    phase = "complete",
                    completedFiles = totalFiles,
                    totalFiles = totalFiles,
                    completedBytes = totalBytes,
                    totalBytes = totalBytes
                )
            )
            BepInExLog.i("Exported modpack: $modpackName  -> ${outputFile.absolutePath}")
            true
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            BepInExLog.e("Failed to export modpack", e)
            false
        }
    }

    private fun isExportableFile(modpackDir: File, file: File): Boolean {
        if (!file.isFile) return false
        val relativePath = file.relativeTo(modpackDir).invariantSeparatorsPath
        return !relativePath.substringBefore('/').equals("logs", ignoreCase = true)
    }

    suspend fun importModpack(
        packageName: String,
        uri: Uri,
        context: Context,
        archiveName: String? = null
    ): ModpackMeta? {
        if (!isModpackFileName(archiveName)) {
            BepInExLog.w("Rejected modpack import with unsupported extension: $archiveName")
            return null
        }
        val requestedName = normalizeModpackName(
            archiveName!!.substringBeforeLast('.')
        )
        val resolvedName = requestedName.ifEmpty { "imported_${System.currentTimeMillis()}" }
        val modpackDir = getModpackDir(packageName, resolvedName)
        val stagingDir = File(
            modpackDir.parentFile,
            ".${resolvedName}.import-${System.currentTimeMillis()}"
        )

        return try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val stagingRoot = stagingDir.canonicalFile.toPath()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Unable to open modpack archive")
            try {
                val zis = ZipInputStream(BufferedInputStream(input))
                try {
                    var entry = zis.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        val normalizedEntryName = entry.name.replace('\\', '/').trimStart('/')
                        if (normalizedEntryName.isNotEmpty()) {
                            val entryFile = File(stagingDir, normalizedEntryName).canonicalFile
                            if (!entryFile.toPath().startsWith(stagingRoot)) {
                                throw java.io.IOException("Unsafe archive entry: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                entryFile.mkdirs()
                            } else {
                                entryFile.parentFile?.mkdirs()
                                val output = BufferedOutputStream(FileOutputStream(entryFile))
                                try {
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                                    var read: Int
                                    do {
                                        currentCoroutineContext().ensureActive()
                                        read = zis.read(buffer)
                                        if (read > 0) output.write(buffer, 0, read)
                                    } while (read >= 0)
                                } finally {
                                    output.close()
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                } finally {
                    zis.close()
                }
            } finally {
                input.close()
            }

            currentCoroutineContext().ensureActive()
            modpackDir.parentFile?.mkdirs()
            val children = stagingDir.listFiles().orEmpty()
            val sourceDir = if (children.size == 1 && children[0].isDirectory) children[0] else stagingDir
            val metadataFile = sourceDir.walkTopDown().firstOrNull { file ->
                file.isFile && file.name.equals("modpack.json", ignoreCase = true)
            } ?: throw java.io.IOException("Modpack archive is missing modpack.json")
            JSONObject(metadataFile.readText())
            val contentRoot = metadataFile.parentFile
                ?: throw java.io.IOException("Invalid modpack metadata location")

            if (modpackDir.exists()) modpackDir.deleteRecursively()
            if (!contentRoot.renameTo(modpackDir)) {
                contentRoot.copyRecursively(modpackDir, overwrite = true)
            }
            stagingDir.deleteRecursively()

            getModpackPluginsDir(packageName, resolvedName).mkdirs()
            getModpackConfigDir(packageName, resolvedName).mkdirs()
            getModpackLogsDir(packageName, resolvedName).mkdirs()

            val meta = ModpackMeta(
                name = resolvedName,
                packageName = packageName,
                modCount = getModCount(packageName, resolvedName)
            )
            writeMeta(meta)
            BepInExLog.i("Imported modpack: $resolvedName")
            meta
        } catch (e: CancellationException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            BepInExLog.e("Failed to import modpack", e)
            null
        }
    }

    // Metadata persistence

    private fun readMeta(packageName: String, name: String): ModpackMeta? {
        val file = getMetaFile(packageName, name)
        val actualModCount = getModCount(packageName, name)
        if (!file.exists()) {
            return ModpackMeta(
                name = name,
                packageName = packageName,
                modCount = actualModCount
            )
        }
        return try {
            val json = JSONObject(file.readText())
            ModpackMeta(
                name = name,
                packageName = packageName,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                modCount = actualModCount
            )
        } catch (e: Exception) {
            ModpackMeta(name = name, packageName = packageName,
                modCount = actualModCount)
        }
    }

    private fun writeMeta(meta: ModpackMeta) {
        val json = JSONObject().apply {
            put("name", meta.name)
            put("packageName", meta.packageName)
            put("createdAt", meta.createdAt)
            put("modCount", meta.modCount)
        }
        getMetaFile(meta.packageName, meta.name).writeText(json.toString(2))
    }

    // Icon

    fun getModpackIconFile(packageName: String, modpackName: String): File? {
        val dir = getModpackDir(packageName, modpackName)
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("icon.") &&
                it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp")
        }
    }

    fun saveModpackIcon(packageName: String, modpackName: String, bitmap: Bitmap, extension: String = "png") {
        getModpackIconFile(packageName, modpackName)?.delete()
        val file = File(getModpackDir(packageName, modpackName), "icon.$extension")
        file.outputStream().use { out ->
            val format = when (extension.lowercase()) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.PNG
            }
            bitmap.compress(format, 90, out)
        }
    }

    fun deleteModpackIcon(packageName: String, modpackName: String) {
        getModpackIconFile(packageName, modpackName)?.delete()
    }

    fun hasModpackIcon(packageName: String, modpackName: String): Boolean =
        getModpackIconFile(packageName, modpackName) != null
}
