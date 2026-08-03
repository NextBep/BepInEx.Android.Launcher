﻿﻿package com.bepinex.android.modpack

import android.content.Context
import android.net.Uri
import com.bepinex.android.BepInExLog
import com.bepinex.android.BepInExPaths
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Metadata for a modpack.
 */
data class ModpackMeta(
    val name: String,
    val packageName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val modCount: Int = 0
)

/**
 * Manages modpack CRUD operations on the file system.
 *
 * Directory layout:
 * ```
 * /storage/emulated/0/BepInEx_Android/{pkg}/
 *   modpacks/
 *     {name}/
 *       modpack.json       (metadata)
 *       plugins/           (mod DLL files)
 *       config/            (mod .cfg config files)
 * ```
 */
class ModpackManager {

    private fun getModpacksDir(packageName: String): File =
        File(BepInExPaths.getGameRootDir(packageName), "modpacks")

    private fun getModpackDir(packageName: String, name: String): File =
        File(getModpacksDir(packageName), name)

    private fun getModpackPluginsDir(packageName: String, name: String): File =
        File(getModpackDir(packageName, name), "plugins")

    private fun getModpackConfigDir(packageName: String, name: String): File =
        File(getModpackDir(packageName, name), "config")

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
        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        if (safeName.isEmpty()) return null

        val modpackDir = getModpackDir(packageName, safeName)
        if (modpackDir.exists()) return null // already exists

        return try {
            modpackDir.mkdirs()
            getModpackPluginsDir(packageName, safeName).mkdirs()
            getModpackConfigDir(packageName, safeName).mkdirs()

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
        val safeNewName = newName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        if (safeNewName.isEmpty()) return false

        val oldDir = getModpackDir(packageName, oldName)
        val newDir = getModpackDir(packageName, safeNewName)
        if (!oldDir.exists() || newDir.exists()) return false

        return oldDir.renameTo(newDir).also { success ->
            if (success) {
                // Update metadata
                readMeta(packageName, safeNewName)?.let {
                    writeMeta(it.copy(name = safeNewName))
                }
                BepInExLog.i("Renamed modpack: $oldName  -> $safeNewName")
            }
        }
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

    /** Re-sync modpack.json modCount with actual file system count */
    private fun syncModpackMeta(packageName: String, modpackName: String) {
        val current = readMeta(packageName, modpackName) ?: return
        val real = getModCount(packageName, modpackName)
        if (current.modCount != real) {
            writeMeta(current.copy(modCount = real))
        }
    }

    fun listMods(packageName: String, modpackName: String): List<File> {
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        return pluginsDir.listFiles()?.filter { it.isFile && it.extension == "dll" } ?: emptyList()
    }

    fun listConfigs(packageName: String, modpackName: String): List<File> {
        val configDir = getModpackConfigDir(packageName, modpackName)
        return configDir.listFiles()?.filter { it.isFile && it.extension == "cfg" } ?: emptyList()
    }

    fun addMod(packageName: String, modpackName: String, sourceFile: File): File? {
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
        val fileName = resolveFileName(context, uri) ?: "mod_${System.currentTimeMillis()}.dll"
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
                // Infer packageName and modpackName from path
                // Path: .../modpacks/{modpackName}/plugins/{file}
                val pluginsDir = file.parentFile
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
     * Copy the modpack's plugins and configs to the active BepInEx directory.
     */
    fun applyModpack(packageName: String, modpackName: String): Boolean {
        try {
            val pluginsDir = BepInExPaths.getPluginsDir(packageName)
            val configDir = BepInExPaths.getConfigDir(packageName)

            // Clear existing
            pluginsDir.deleteRecursively()
            configDir.deleteRecursively()
            pluginsDir.mkdirs()
            configDir.mkdirs()

            // Copy modpack plugins
            val modpackPlugins = getModpackPluginsDir(packageName, modpackName)
            modpackPlugins.listFiles()?.forEach { plugin ->
                plugin.copyTo(File(pluginsDir, plugin.name), overwrite = true)
            }

            // Copy modpack configs
            val modpackConfig = getModpackConfigDir(packageName, modpackName)
            modpackConfig.listFiles()?.forEach { cfg ->
                cfg.copyTo(File(configDir, cfg.name), overwrite = true)
            }

            BepInExLog.i("Applied modpack: $modpackName  -> active")
            return true
        } catch (e: Exception) {
            BepInExLog.e("Failed to apply modpack", e)
            return false
        }
    }

    /** Clear active mods (vanilla mode) */
    fun clearActiveMods(packageName: String) {
        BepInExPaths.getPluginsDir(packageName).deleteRecursively()
        BepInExPaths.getConfigDir(packageName).deleteRecursively()
        BepInExPaths.getPluginsDir(packageName).mkdirs()
        BepInExPaths.getConfigDir(packageName).mkdirs()
        BepInExLog.i("Cleared active mods (vanilla mode)")
    }

    // Export / Import

    fun exportModpack(packageName: String, modpackName: String, outputFile: File): Boolean {
        val modpackDir = getModpackDir(packageName, modpackName)
        if (!modpackDir.exists()) return false

        return try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                modpackDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val entryName = file.relativeTo(modpackDir).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            BepInExLog.i("Exported modpack: $modpackName  -> ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            BepInExLog.e("Failed to export modpack", e)
            false
        }
    }

    fun importModpack(packageName: String, uri: Uri, context: Context): ModpackMeta? {
        try {
            // Read ZIP entries to determine modpack name
            var modpackName: String? = null
            val tempDir = File(context.cacheDir, "modpack_import_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.trim('/')
                        // First top-level directory is the modpack name
                        if (!name.contains("/") && modpackName == null) {
                            modpackName = name
                        }
                        val outFile = File(tempDir, name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            val resolvedName = modpackName ?: "imported_${System.currentTimeMillis()}"
            val modpackDir = getModpackDir(packageName, resolvedName)
            if (modpackDir.exists()) modpackDir.deleteRecursively()

            // Move from temp to modpacks directory
            val sourceDir = if (modpackName != null) File(tempDir, modpackName) else tempDir
            sourceDir.copyRecursively(modpackDir, overwrite = true)
            tempDir.deleteRecursively()

            // Ensure proper structure
            getModpackPluginsDir(packageName, resolvedName).mkdirs()
            getModpackConfigDir(packageName, resolvedName).mkdirs()

            val meta = ModpackMeta(name = resolvedName, packageName = packageName,
                modCount = getModCount(packageName, resolvedName))
            writeMeta(meta)
            BepInExLog.i("Imported modpack: $resolvedName")
            return meta
        } catch (e: Exception) {
            BepInExLog.e("Failed to import modpack", e)
            return null
        }
    }

    // Metadata persistence

    private fun readMeta(packageName: String, name: String): ModpackMeta? {
        val file = getMetaFile(packageName, name)
        if (!file.exists()) {
            // Infer from directory
            return ModpackMeta(
                name = name,
                packageName = packageName,
                modCount = getModCount(packageName, name)
            )
        }
        return try {
            val json = JSONObject(file.readText())
            ModpackMeta(
                name = json.optString("name", name),
                packageName = json.optString("packageName", packageName),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                modCount = json.optInt("modCount", getModCount(packageName, name))
            )
        } catch (e: Exception) {
            ModpackMeta(name = name, packageName = packageName,
                modCount = getModCount(packageName, name))
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
}
