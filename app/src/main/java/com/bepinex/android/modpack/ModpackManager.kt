package com.bepinex.android.modpack

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import com.bepinex.android.BepInExLog
import com.bepinex.android.BepInExPaths
import com.bepinex.android.FileExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Per-mod extras stored in modpack.json.
 *
 * Default-only entries (file-name display, enabled, no category) are omitted.
 */
data class StoredModInfo(
    val displayName: String? = null,
    val enabled: Boolean = true,
    val category: String? = null
) {
    fun isDefault(fileName: String): Boolean {
        val name = displayName?.trim().orEmpty()
        return enabled &&
            category.isNullOrBlank() &&
            (name.isEmpty() || name == fileName)
    }
}

/**
 * Metadata for a modpack.
 *
 * [mods] maps a plugin-relative DLL path to optional display name, enabled
 * flag, and category. Older files used dllNames/disabledDlls/dllCategories.
 */
data class ModpackMeta(
    val name: String,
    val packageName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val modCount: Int = 0,
    val createShortcut: Boolean = false,
    val gameVersion: String = "",
    val mods: Map<String, StoredModInfo> = emptyMap()
) {
    val disabledDlls: Set<String>
        get() = mods.filterValues { !it.enabled }.keys

    val enabledModCount: Int
        get() = (modCount - disabledDlls.size).coerceAtLeast(0)
}

/** A plugin DLL inside a modpack, with its saved display name and load switch. */
data class ModpackMod(
    val file: File,
    val relativePath: String,
    val displayName: String,
    val enabled: Boolean = true,
    val category: String? = null
)

data class PeekedModpackInfo(
    val name: String,
    val gameVersion: String
)

data class ModImportResult(
    val importedMods: Int,
    val importedConfigs: Int
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

/**
 * Manages modpack CRUD operations on the file system.
 */
class ModpackManager {

    companion object {
        const val MODPACK_EXTENSION = "bal"
        const val MODPACK_MIME_TYPE = "application/octet-stream"

        private val SUPPORTED_MODPACK_EXTENSIONS = setOf("bal", "zip")
        private const val MODS_KEY = "mods"
        private const val GAME_VERSION_KEY = "gameVersion"
        private const val DLL_NAMES_KEY = "dllNames"
        private const val DISABLED_DLLS_KEY = "disabledDlls"
        private const val DLL_CATEGORIES_KEY = "dllCategories"

        fun isGameVersionCompatible(required: String?, actual: String?): Boolean {
            val tokens = parseGameVersionTokens(required)
            if (tokens.isEmpty()) return true
            val current = normalizeGameVersion(actual)
            if (current.isEmpty()) return true
            return tokens.any { token ->
                token.equals(current, ignoreCase = true)
            }
        }

        private fun parseGameVersionTokens(value: String?): List<String> =
            value.orEmpty()
                .split(',', ';', '/', '|', '、')
                .map(::normalizeGameVersion)
                .filter { it.isNotEmpty() }

        private fun normalizeGameVersion(value: String?): String =
            value.orEmpty()
                .substringBefore('(')
                .substringBefore(' ')
                .trim()
        private val EXTRA_DOWNLOAD_RELATIVE_PATHS = listOf(
            "Download",
            "Downloads",
            "download",
            "Documents",
            "Download/WeiXin",
            "Download/WeChat",
            "Download/Browser",
            "Download/Telegram",
            "Download/QQ",
            "Download/Chrome",
            "Download/UCDownloads",
            "tencent/QQfile_recv",
            "tencent/MicroMsg/Download",
            "qqfile_recv"
        )

        fun isModpackFileName(fileName: String?): Boolean =
            fileName?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_MODPACK_EXTENSIONS

        fun isModFileName(fileName: String?): Boolean =
            fileName?.substringAfterLast('.', "")?.equals("dll", ignoreCase = true) == true

        private val runtimeMutex = Mutex()

        @Volatile
        var runtimeSwitchInProgress: Boolean = false
            internal set
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

    fun createModpack(
        packageName: String,
        name: String,
        gameVersion: String = ""
    ): ModpackMeta? {
        val safeName = normalizeModpackName(name)
        if (safeName.isEmpty()) return null

        val modpackDir = getModpackDir(packageName, safeName)
        if (modpackDir.exists()) return null // already exists

        return try {
            modpackDir.mkdirs()
            getModpackPluginsDir(packageName, safeName).mkdirs()
            getModpackConfigDir(packageName, safeName).mkdirs()
            getModpackLogsDir(packageName, safeName).mkdirs()

            val meta = ModpackMeta(
                name = safeName,
                packageName = packageName,
                gameVersion = gameVersion.trim()
            )
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

        // Metadata is repaired on a best-effort basis after the directory rename.
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

    /** Resolve the display name from a content URI */
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

    /** Re-sync modpack.json modCount and DLL display-name mappings. */
    private fun syncModpackMeta(packageName: String, modpackName: String) {
        readMeta(packageName, modpackName)
    }

    fun listMods(packageName: String, modpackName: String): List<File> {
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        return if (pluginsDir.isDirectory) {
            pluginsDir.walkTopDown()
                // Android paths are case-sensitive and File.extension preserves
                // the original case. Treat .dll/.DLL/.Dll as the same mod type.
                .filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
                .toList()
        } else {
            emptyList()
        }
    }

    fun listModEntries(packageName: String, modpackName: String): List<ModpackMod> {
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        val stored = readMeta(packageName, modpackName)?.mods.orEmpty()
        return listMods(packageName, modpackName).map { file ->
            val relativePath = dllRelativePath(pluginsDir, file)
            val info = lookupModInfo(stored, relativePath, file.name)
            ModpackMod(
                file = file,
                relativePath = relativePath,
                displayName = info?.displayName?.takeIf { it.isNotBlank() } ?: file.name,
                enabled = info?.enabled ?: true,
                category = info?.category?.takeIf { it.isNotBlank() }
            )
        }
    }

    fun setDllDisplayName(
        packageName: String,
        modpackName: String,
        relativePath: String,
        displayName: String
    ): Boolean {
        val name = displayName.trim()
        if (name.isEmpty()) return false
        val current = readMeta(packageName, modpackName) ?: return false
        val fileName = normalizeDllKey(relativePath).substringAfterLast('/')
        writeMeta(
            updateModInfo(current, packageName, modpackName, relativePath) { info ->
                info.copy(displayName = name.takeUnless { it == fileName })
            }
        )
        return true
    }

    fun setDllCategory(
        packageName: String,
        modpackName: String,
        relativePath: String,
        category: String?
    ): Boolean {
        val current = readMeta(packageName, modpackName) ?: return false
        val name = category?.trim().orEmpty()
        writeMeta(
            updateModInfo(current, packageName, modpackName, relativePath) { info ->
                info.copy(category = name.takeIf { it.isNotEmpty() })
            }
        )
        return true
    }

    fun setDllEnabled(
        packageName: String,
        modpackName: String,
        relativePath: String,
        enabled: Boolean
    ): Boolean {
        val current = readMeta(packageName, modpackName) ?: return false
        writeMeta(
            updateModInfo(current, packageName, modpackName, relativePath) { info ->
                info.copy(enabled = enabled)
            }
        )
        return true
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
        // Resolve real file name from content URI (lastPathSegment is just a numeric ID)
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

    /**
     * Copy selected plugins from [sourceModpack] into [destModpack], including
     * matching config files and extra files that sit beside the DLL.
     */
    fun importModsFromModpack(
        packageName: String,
        sourceModpack: String,
        destModpack: String,
        relativePaths: Collection<String>
    ): ModImportResult {
        if (sourceModpack == destModpack) return ModImportResult(0, 0)
        val selectedKeys = relativePaths.map(::normalizeDllKey).filter { it.isNotEmpty() }.toSet()
        if (selectedKeys.isEmpty()) return ModImportResult(0, 0)

        val sourcePlugins = getModpackPluginsDir(packageName, sourceModpack)
        val destPlugins = getModpackPluginsDir(packageName, destModpack)
        destPlugins.mkdirs()
        getModpackConfigDir(packageName, destModpack).mkdirs()

        val selectedMods = listModEntries(packageName, sourceModpack)
            .filter { normalizeDllKey(it.relativePath) in selectedKeys }

        var importedMods = 0
        selectedMods.forEach { mod ->
            try {
                copyFilePreserve(mod.file, File(destPlugins, mod.relativePath))
                copyPluginExtras(sourcePlugins, destPlugins, mod, selectedKeys)
                importedMods++
            } catch (e: Exception) {
                BepInExLog.e("Failed to import mod ${mod.relativePath}", e)
            }
        }

        val importedConfigs = copyMatchingConfigs(
            packageName,
            sourceModpack,
            destModpack,
            selectedMods
        )

        val destMeta = readMeta(packageName, destModpack)
            ?: ModpackMeta(name = destModpack, packageName = packageName)
        val mods = syncedMods(packageName, destModpack, destMeta.mods).toMutableMap()
        selectedMods.forEach { mod ->
            val key = normalizeDllKey(mod.relativePath)
            val fileName = key.substringAfterLast('/')
            val info = StoredModInfo(
                displayName = mod.displayName.takeIf { it.isNotBlank() && it != fileName },
                enabled = mod.enabled,
                category = mod.category?.trim()?.takeIf { it.isNotEmpty() }
            )
            if (info.isDefault(fileName)) {
                mods.remove(key)
                mods.remove(fileName)
            } else {
                mods.remove(fileName)
                mods[key] = info
            }
        }
        writeMeta(
            destMeta.copy(
                modCount = getModCount(packageName, destModpack),
                mods = mods
            )
        )
        BepInExLog.i(
            "Imported $importedMods mod(s) and $importedConfigs config(s) from $sourceModpack -> $destModpack"
        )
        return ModImportResult(importedMods, importedConfigs)
    }

    private fun copyFilePreserve(source: File, dest: File) {
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
    }

    private fun copyPluginExtras(
        sourcePlugins: File,
        destPlugins: File,
        mod: ModpackMod,
        selectedKeys: Set<String>
    ) {
        val parentRel = mod.relativePath.substringBeforeLast('/', "")
        if (parentRel.isNotEmpty()) {
            val srcDir = File(sourcePlugins, parentRel)
            if (!srcDir.isDirectory) return
            srcDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = normalizeDllKey(file.relativeTo(sourcePlugins).invariantSeparatorsPath)
                if (file.extension.equals("dll", ignoreCase = true) && rel !in selectedKeys) return@forEach
                copyFilePreserve(file, File(destPlugins, rel))
            }
            return
        }

        val stem = mod.file.nameWithoutExtension
        sourcePlugins.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.extension.equals("dll", ignoreCase = true)) return@forEach
            if (!file.nameWithoutExtension.equals(stem, ignoreCase = true)) return@forEach
            copyFilePreserve(file, File(destPlugins, file.name))
        }
    }

    private fun copyMatchingConfigs(
        packageName: String,
        sourceModpack: String,
        destModpack: String,
        selectedMods: List<ModpackMod>
    ): Int {
        val srcConfig = getModpackConfigDir(packageName, sourceModpack)
        val destConfig = getModpackConfigDir(packageName, destModpack)
        if (!srcConfig.isDirectory || selectedMods.isEmpty()) return 0

        val copied = linkedSetOf<String>()
        srcConfig.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = normalizeDllKey(file.relativeTo(srcConfig).invariantSeparatorsPath)
            if (rel in copied) return@forEach
            if (selectedMods.none { configMatchesMod(rel, it) }) return@forEach
            copyFilePreserve(file, File(destConfig, rel))
            copied += rel
        }
        return copied.size
    }

    private fun configMatchesMod(relativePath: String, mod: ModpackMod): Boolean {
        val cfgPath = normalizeDllKey(relativePath).lowercase()
        val cfgName = cfgPath.substringAfterLast('/').substringBeforeLast('.')
        val dllStem = mod.file.nameWithoutExtension.lowercase()
        val dllFolder = mod.relativePath.substringBeforeLast('/', "").lowercase()
        if (dllStem.isNotEmpty()) {
            if (cfgName == dllStem) return true
            if (cfgName.substringAfterLast('.') == dllStem) return true
        }
        if (dllFolder.isNotEmpty()) {
            if (cfgName == dllFolder) return true
            if (cfgName.substringAfterLast('.') == dllFolder) return true
            if (cfgPath.startsWith("$dllFolder/")) return true
        }
        return false
    }

    fun removeMod(file: File): Boolean {
        return file.delete().also {
            if (it) {
                // Infer packageName and modpackName from path at any plugin depth.
                // Path: .../modpacks/{modpackName}/plugins/{file}
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

    /**
     * Apply the modpack contents to the active BepInEx directory.
     * Runtime writes stay in BepInEx/; persistRuntimeState() copies them back later.
     */
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

    /** Clear active mods (vanilla mode) */
    fun clearActiveMods(packageName: String) {
        restoreRuntimeState(packageName, null)
        BepInExLog.i("Cleared active mods (vanilla mode)")
    }

    /**
     * Persist [from] then restore [to] under a lock so UI switches and
     * shortcut launches cannot interleave copies.
     */
    suspend fun switchRuntime(packageName: String, from: String?, to: String?): Boolean {
        return runtimeMutex.withLock {
            runtimeSwitchInProgress = true
            try {
                persistRuntimeState(packageName, from)
                if (to.isNullOrEmpty()) {
                    clearActiveMods(packageName)
                    true
                } else {
                    applyModpack(packageName, to)
                }
            } finally {
                runtimeSwitchInProgress = false
            }
        }
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
            // plugins/config/logs use their dedicated runtime-state handling.
            val pluginsSource = getModpackPluginsDir(packageName, modpackName)
            val disabledDlls = readMeta(packageName, modpackName)?.disabledDlls.orEmpty()
            if (disabledDlls.isNotEmpty()) {
                BepInExLog.i("Skipping ${disabledDlls.size} disabled plugin(s) for $modpackName")
            }
            syncDirContents(
                source = pluginsSource,
                dest = pluginsDir,
                excludedRelativePaths = disabledDlls,
                relativeRoot = pluginsSource
            )
            copyModpackRootContents(srcRoot, bepInExDir)
        } else {
            // Vanilla state never owns plugins, so make sure no active mod is left.
            replaceDir(pluginsDir)
        }
        syncDirContents(File(srcRoot, "config"), configDir)
        // Do NOT restore LogOutput.log from modpack — let each session start fresh.
        // persistRuntimeState() will save the latest logs when the session ends.
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

    /**
     * Mirror [source] into [dest], keeping identical files and updating changed ones.
     * Files whose path relative to [relativeRoot] is in [excludedRelativePaths] are
     * treated as absent, so leftover copies in [dest] are removed.
     */
    private fun syncDirContents(
        source: File,
        dest: File,
        excludedRelativePaths: Set<String> = emptySet(),
        relativeRoot: File = source
    ) {
        if (!source.isDirectory) {
            replaceDir(dest)
            return
        }

        if (dest.exists() && !dest.isDirectory) dest.delete()
        dest.mkdirs()

        val sourceChildren = source.listFiles()
            ?.filterNot { child ->
                child.isFile &&
                    excludedRelativePaths.isNotEmpty() &&
                    normalizeDllKey(child.relativeTo(relativeRoot).invariantSeparatorsPath) in
                        excludedRelativePaths
            }
            ?.associateBy { it.name }
            .orEmpty()
        dest.listFiles()
            ?.filter { it.name !in sourceChildren }
            ?.forEach { it.deleteRecursively() }

        sourceChildren.values.forEach { child ->
            syncEntry(child, File(dest, child.name), excludedRelativePaths, relativeRoot)
        }
    }

    private fun syncEntry(
        source: File,
        target: File,
        excludedRelativePaths: Set<String> = emptySet(),
        relativeRoot: File = source
    ) {
        if (source.isDirectory) {
            syncDirContents(source, target, excludedRelativePaths, relativeRoot)
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

    /**
     * Copy a modpack's BepInEx-root contents, excluding launcher metadata.
     */
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

    fun scanDownloadModpacks(context: Context): List<File> {
        return scanDownloadCandidates(
            context = context,
            extension = MODPACK_EXTENSION,
            downloadsOnlyMediaStore = false
        )
            .filter { containsModpackMetadata(it) }
            .let(::dedupeScanFiles)
    }

    fun scanDownloadDlls(context: Context): List<File> {
        return scanDownloadCandidates(
            context = context,
            extension = "dll",
            downloadsOnlyMediaStore = true,
            extraFilter = { !isLauncherManagedPath(it) }
        )
            .let(::dedupeScanFiles)
            .groupBy { it.name.lowercase() }
            .map { (_, copies) -> copies.minWithOrNull(scanFileComparator) ?: copies.first() }
            .sortedByDescending { it.lastModified() }
    }

    private fun scanDownloadCandidates(
        context: Context,
        extension: String,
        downloadsOnlyMediaStore: Boolean,
        extraFilter: (File) -> Boolean = { true }
    ): List<File> {
        val byPath = linkedMapOf<String, File>()
        fun addCandidate(file: File) {
            if (!file.isFile) return
            if (!file.extension.equals(extension, ignoreCase = true)) return
            if (!extraFilter(file)) return
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val existing = byPath[key]
            if (existing == null || compareScanFiles(file, existing) < 0) {
                byPath[key] = file
            }
        }

        downloadDirectories(context).forEach { dir ->
            runCatching {
                dir.walkTopDown().maxDepth(4)
                    .filter { it.isFile }
                    .forEach(::addCandidate)
            }
        }
        scanMediaStoreFiles(
            context = context,
            extension = extension,
            alreadyFound = byPath.values,
            downloadsOnly = downloadsOnlyMediaStore
        ).forEach(::addCandidate)

        return byPath.values.toList()
    }

    private fun dedupeScanFiles(files: Collection<File>): List<File> =
        files
            .groupBy { scanIdentity(it) }
            .map { (_, copies) -> copies.minWithOrNull(scanFileComparator) ?: copies.first() }
            .sortedByDescending { it.lastModified() }

    private fun isLauncherManagedPath(file: File): Boolean {
        val path = file.absolutePath
        val sep = File.separator
        return path.contains("${sep}PVZRH_Launcher${sep}") ||
            path.contains("${sep}Android${sep}data${sep}") ||
            path.contains("${sep}Android${sep}obb${sep}")
    }

    private fun scanIdentity(file: File): String {
        val name = file.nameWithoutExtension
            .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
            .lowercase()
        return "$name|${file.length()}"
    }

    private val scanFileComparator = Comparator<File> { left, right ->
        compareScanFiles(left, right)
    }

    private fun compareScanFiles(left: File, right: File): Int {
        val cacheRank = scanCacheRank(left).compareTo(scanCacheRank(right))
        if (cacheRank != 0) return cacheRank
        return right.lastModified().compareTo(left.lastModified())
    }

    private fun scanCacheRank(file: File): Int =
        if (file.path.contains("${File.separator}download-scan${File.separator}")) 1 else 0

    suspend fun importModpack(packageName: String, file: File): ModpackMeta? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { input ->
                importModpackFromStream(packageName, input, file.name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BepInExLog.e("Failed to import modpack from ${file.absolutePath}", e)
            null
        }
    }

    suspend fun importModpack(
        packageName: String,
        uri: Uri,
        context: Context,
        archiveName: String? = null
    ): ModpackMeta? {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            BepInExLog.e("Failed to open modpack archive", e)
            null
        } ?: return null.also {
            BepInExLog.e("Unable to open modpack archive")
        }
        return try {
            input.use { stream ->
                importModpackFromStream(packageName, stream, archiveName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BepInExLog.e("Failed to import modpack", e)
            null
        }
    }

    fun peekModpackInfo(file: File): PeekedModpackInfo? {
        if (!file.isFile) return null
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { item ->
                    !item.isDirectory &&
                        item.name.replace('\\', '/')
                            .substringAfterLast('/')
                            .equals("modpack.json", ignoreCase = true)
                } ?: return null
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    parsePeekedModpackInfo(reader.readText(), file.name)
                }
            }
        } catch (e: Exception) {
            BepInExLog.w("Unable to peek modpack ${file.name}: ${e.message}")
            null
        }
    }

    fun peekModpackInfo(context: Context, uri: Uri, archiveName: String? = null): PeekedModpackInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                peekModpackInfoFromStream(input, archiveName)
            }
        } catch (e: Exception) {
            BepInExLog.w("Unable to peek modpack URI: ${e.message}")
            null
        }
    }

    private fun peekModpackInfoFromStream(input: InputStream, archiveName: String?): PeekedModpackInfo? {
        val zis = ZipInputStream(BufferedInputStream(input))
        try {
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                    .substringAfterLast('/')
                if (!entry.isDirectory && entryName.equals("modpack.json", ignoreCase = true)) {
                    return parsePeekedModpackInfo(String(zis.readBytes(), Charsets.UTF_8), archiveName)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        } catch (e: Exception) {
            BepInExLog.w("Unable to peek modpack stream: ${e.message}")
        } finally {
            zis.close()
        }
        return null
    }

    private fun parsePeekedModpackInfo(jsonText: String, archiveName: String?): PeekedModpackInfo? {
        return try {
            val json = JSONObject(jsonText)
            val fallbackName = archiveName
                ?.substringBeforeLast('.')
                ?.let(::normalizeModpackName)
                .orEmpty()
            PeekedModpackInfo(
                name = json.optString("name").trim().ifEmpty { fallbackName },
                gameVersion = json.optString(GAME_VERSION_KEY).trim()
            )
        } catch (e: Exception) {
            BepInExLog.w("Invalid peeked modpack.json: ${e.message}")
            null
        }
    }

    private fun downloadDirectories(context: Context): List<File> {
        val dirs = linkedSetOf<File>()
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { dirs.add(it) }
        storageRoots(context).forEach { root ->
            EXTRA_DOWNLOAD_RELATIVE_PATHS.forEach { relative ->
                dirs.add(File(root, relative))
            }
        }
        return dirs.filter { it.isDirectory }.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
    }

    private fun storageRoots(context: Context): List<File> {
        val roots = linkedSetOf<File>()
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.getSystemService(StorageManager::class.java)
                    ?.storageVolumes
                    ?.mapNotNull { it.directory }
                    ?.forEach { roots.add(it) }
            }
        }
        context.getExternalFilesDirs(null)?.forEach { appDir ->
            generateSequence(appDir) { it.parentFile }
                .firstOrNull { it.name.equals("Android", ignoreCase = true) }
                ?.parentFile
                ?.let { roots.add(it) }
        }
        return roots.filter { it.isDirectory }.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
    }

    private fun scanMediaStoreFiles(
        context: Context,
        extension: String,
        alreadyFound: Collection<File> = emptyList(),
        downloadsOnly: Boolean = false
    ): List<File> {
        val files = mutableListOf<File>()
        val collections = linkedSetOf<Uri>()
        if (!downloadsOnly) {
            collections.add(MediaStore.Files.getContentUri("external"))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                collections.add(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL))
            }
            runCatching {
                MediaStore.getExternalVolumeNames(context).forEach { volume ->
                    if (!downloadsOnly) {
                        collections.add(MediaStore.Files.getContentUri(volume))
                    }
                    runCatching {
                        collections.add(MediaStore.Downloads.getContentUri(volume))
                    }
                }
            }
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%.$extension")

        collections.forEach { collection ->
            try {
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    while (cursor.moveToNext()) {
                        val name = nameIdx.takeIf { it >= 0 }?.let(cursor::getString)
                        if (!isScannedMediaStoreName(name, extension)) continue
                        val path = dataIdx.takeIf { it >= 0 }?.let(cursor::getString)
                        val direct = path?.takeIf { it.isNotBlank() }?.let(::File)
                        if (direct?.isFile == true) {
                            files.add(direct)
                            continue
                        }
                        if (idIdx < 0 || name.isNullOrBlank()) continue
                        if (alreadyFound.any { it.name.equals(name, ignoreCase = true) }) continue
                        val itemUri = ContentUris.withAppendedId(collection, cursor.getLong(idIdx))
                        copyMediaStoreItemToCache(context, itemUri, name, extension)?.let(files::add)
                    }
                }
            } catch (error: Exception) {
                BepInExLog.w("MediaStore download scan failed for $collection: ${error.message}")
            }
        }
        return files
    }

    private fun isScannedMediaStoreName(name: String?, extension: String): Boolean {
        if (name.isNullOrBlank()) return false
        if (!name.substringAfterLast('.', "").equals(extension, ignoreCase = true)) return false
        return if (extension.equals("dll", ignoreCase = true)) {
            isModFileName(name)
        } else {
            isModpackFileName(name)
        }
    }

    private fun copyMediaStoreItemToCache(
        context: Context,
        uri: Uri,
        displayName: String,
        extension: String
    ): File? {
        return try {
            val safeName = displayName.substringAfterLast('/').ifBlank { "download.$extension" }
            val target = if (extension.equals("dll", ignoreCase = true)) {
                val cacheDir = File(context.cacheDir, "download-scan/dll/${uri.lastPathSegment}")
                cacheDir.mkdirs()
                File(cacheDir, safeName)
            } else {
                val cacheDir = File(context.cacheDir, "download-scan")
                cacheDir.mkdirs()
                File(cacheDir, "${uri.lastPathSegment}-$safeName")
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.takeIf { it.isFile && it.length() > 0L }
        } catch (error: Exception) {
            BepInExLog.w("Unable to copy MediaStore item $displayName: ${error.message}")
            null
        }
    }

    private fun containsModpackMetadata(file: File): Boolean {
        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any { entry ->
                    entry.name.replace('\\', '/')
                        .substringAfterLast('/')
                        .equals("modpack.json", ignoreCase = true)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun importModpackFromStream(
        packageName: String,
        input: InputStream,
        archiveName: String?
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

            currentCoroutineContext().ensureActive()
            modpackDir.parentFile?.mkdirs()
            val children = stagingDir.listFiles().orEmpty()
            val sourceDir = if (children.size == 1 && children[0].isDirectory) children[0] else stagingDir
            val metadataFile = sourceDir.walkTopDown().firstOrNull { file ->
                file.isFile && file.name.equals("modpack.json", ignoreCase = true)
            } ?: throw java.io.IOException("Modpack archive is missing modpack.json")
            val importedJson = JSONObject(metadataFile.readText())
            val importedMods = parseMods(importedJson)
            val importedGameVersion = importedJson.optString(GAME_VERSION_KEY).trim()
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
                createdAt = importedJson.optLong("createdAt", System.currentTimeMillis()),
                modCount = getModCount(packageName, resolvedName),
                createShortcut = importedJson.optBoolean("createShortcut", false),
                gameVersion = importedGameVersion,
                mods = syncedMods(packageName, resolvedName, importedMods)
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
            val parsedMods = parseMods(json)
            val mods = syncedMods(packageName, name, parsedMods)
            val meta = ModpackMeta(
                name = name,
                packageName = packageName,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                modCount = actualModCount,
                createShortcut = json.optBoolean("createShortcut", false),
                gameVersion = json.optString(GAME_VERSION_KEY).trim(),
                mods = mods
            )
            val storedModCount = json.optInt("modCount", -1)
            val shouldRewrite =
                storedModCount != actualModCount ||
                    parsedMods != mods ||
                    json.has(DLL_NAMES_KEY) ||
                    json.has(DISABLED_DLLS_KEY) ||
                    json.has(DLL_CATEGORIES_KEY)
            if (shouldRewrite) {
                writeMeta(meta)
            }
            meta
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
            put("createShortcut", meta.createShortcut)
            if (meta.gameVersion.isNotBlank()) {
                put(GAME_VERSION_KEY, meta.gameVersion.trim())
            }
            val modsJson = JSONObject()
            meta.mods.toSortedMap().forEach { (path, info) ->
                val fileName = path.substringAfterLast('/')
                if (info.isDefault(fileName)) return@forEach
                val entry = JSONObject()
                info.displayName?.trim()
                    ?.takeIf { it.isNotEmpty() && it != fileName }
                    ?.let { entry.put("displayName", it) }
                if (!info.enabled) entry.put("enabled", false)
                info.category?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { entry.put("category", it) }
                if (entry.length() > 0) modsJson.put(path, entry)
            }
            if (modsJson.length() > 0) put(MODS_KEY, modsJson)
        }
        getMetaFile(meta.packageName, meta.name).writeText(json.toString(2))
    }

    private fun updateModInfo(
        current: ModpackMeta,
        packageName: String,
        modpackName: String,
        relativePath: String,
        transform: (StoredModInfo) -> StoredModInfo
    ): ModpackMeta {
        val key = normalizeDllKey(relativePath)
        if (key.isEmpty()) return current
        val fileName = key.substringAfterLast('/')
        val mods = syncedMods(packageName, modpackName, current.mods).toMutableMap()
        val updated = transform(lookupModInfo(mods, key, fileName) ?: StoredModInfo())
        mods.remove(fileName)
        if (updated.isDefault(fileName)) {
            mods.remove(key)
        } else {
            mods[key] = updated
        }
        return current.copy(
            modCount = getModCount(packageName, modpackName),
            mods = mods
        )
    }

    private fun parseMods(json: JSONObject): Map<String, StoredModInfo> {
        val fromNew = parseModsObject(json)
        val fromLegacy = parseLegacyMods(json)
        if (fromNew.isEmpty()) return fromLegacy
        if (fromLegacy.isEmpty()) return fromNew
        val result = linkedMapOf<String, StoredModInfo>()
        (fromNew.keys + fromLegacy.keys).forEach { path ->
            val newer = fromNew[path]
            val older = fromLegacy[path]
            result[path] = StoredModInfo(
                displayName = newer?.displayName ?: older?.displayName,
                enabled = newer?.enabled ?: older?.enabled ?: true,
                category = newer?.category ?: older?.category
            )
        }
        return result
    }

    private fun parseModsObject(json: JSONObject): Map<String, StoredModInfo> {
        val obj = json.optJSONObject(MODS_KEY) ?: return emptyMap()
        val result = linkedMapOf<String, StoredModInfo>()
        obj.keys().forEach { key ->
            val path = normalizeDllKey(key)
            if (path.isEmpty()) return@forEach
            val entry = obj.optJSONObject(key) ?: return@forEach
            val fileName = path.substringAfterLast('/')
            val info = StoredModInfo(
                displayName = entry.optString("displayName").trim().takeIf { it.isNotEmpty() },
                enabled = if (entry.has("enabled")) entry.optBoolean("enabled", true) else true,
                category = entry.optString("category").trim().takeIf { it.isNotEmpty() }
            )
            if (!info.isDefault(fileName)) result[path] = info
        }
        return result
    }

    private fun parseLegacyMods(json: JSONObject): Map<String, StoredModInfo> {
        val names = parseDllNames(json)
        val disabled = parseDisabledDlls(json)
        val categories = parseDllCategories(json)
        if (names.isEmpty() && disabled.isEmpty() && categories.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, StoredModInfo>()
        (names.keys + disabled + categories.keys).forEach { raw ->
            val path = normalizeDllKey(raw)
            if (path.isEmpty()) return@forEach
            val fileName = path.substringAfterLast('/')
            val displayName = names[path] ?: names[fileName]
            val info = StoredModInfo(
                displayName = displayName?.takeIf { it.isNotBlank() && it != fileName },
                enabled = path !in disabled && fileName !in disabled,
                category = (categories[path] ?: categories[fileName])?.takeIf { it.isNotBlank() }
            )
            if (!info.isDefault(fileName)) result[path] = info
        }
        return result
    }

    private fun parseDllNames(json: JSONObject): Map<String, String> {
        val names = json.optJSONObject(DLL_NAMES_KEY) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        names.keys().forEach { key ->
            val displayName = names.optString(key).trim()
            if (key.isNotBlank() && displayName.isNotEmpty()) {
                result[normalizeDllKey(key)] = displayName
            }
        }
        return result
    }

    private fun parseDllCategories(json: JSONObject): Map<String, String> {
        val categories = json.optJSONObject(DLL_CATEGORIES_KEY) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        categories.keys().forEach { key ->
            val category = categories.optString(key).trim()
            if (key.isNotBlank() && category.isNotEmpty()) {
                result[normalizeDllKey(key)] = category
            }
        }
        return result
    }

    private fun parseDisabledDlls(json: JSONObject): Set<String> {
        val array = json.optJSONArray(DISABLED_DLLS_KEY) ?: return emptySet()
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) result.add(normalizeDllKey(value))
        }
        return result
    }

    private fun syncedMods(
        packageName: String,
        modpackName: String,
        existing: Map<String, StoredModInfo>
    ): Map<String, StoredModInfo> {
        if (existing.isEmpty()) return emptyMap()
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        val knownFiles = listMods(packageName, modpackName).associate { file ->
            dllRelativePath(pluginsDir, file) to file.name
        }
        if (knownFiles.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, StoredModInfo>()
        knownFiles.forEach { (relativePath, fileName) ->
            val info = lookupModInfo(existing, relativePath, fileName) ?: return@forEach
            val normalized = StoredModInfo(
                displayName = info.displayName?.trim()?.takeIf { it.isNotEmpty() && it != fileName },
                enabled = info.enabled,
                category = info.category?.trim()?.takeIf { it.isNotEmpty() }
            )
            if (!normalized.isDefault(fileName)) result[relativePath] = normalized
        }
        return result
    }

    private fun lookupModInfo(
        mods: Map<String, StoredModInfo>,
        relativePath: String,
        fileName: String
    ): StoredModInfo? {
        mods[relativePath]?.let { return it }
        mods[fileName]?.let { return it }
        return mods.entries.firstOrNull { it.key.substringAfterLast('/') == fileName }?.value
    }

    private fun dllRelativePath(pluginsDir: File, file: File): String =
        normalizeDllKey(file.relativeTo(pluginsDir).invariantSeparatorsPath)

    private fun normalizeDllKey(path: String): String =
        path.replace('\\', '/').trimStart('/')

    fun updateMeta(
        packageName: String,
        modpackName: String,
        createShortcut: Boolean,
        gameVersion: String? = null
    ): ModpackMeta? {
        val current = readMeta(packageName, modpackName) ?: return null
        val updated = current.copy(
            createShortcut = createShortcut,
            gameVersion = gameVersion?.trim() ?: current.gameVersion
        )
        writeMeta(updated)
        return updated
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
        // Delete old icon first
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
