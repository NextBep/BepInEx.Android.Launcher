package com.bepinex.android.shortcut

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.bepinex.android.R
import com.bepinex.android.ShortcutLauncherActivity
import com.bepinex.android.modpack.ModpackManager

/**
 *  这群国内厂商非常死妈。要适配他们傻逼桌面的桌面快捷方式。
 *  我感觉不如一辈子就让这里TODO了。现在就适配小米，别的就等社区贡献者Issue后Pull Request了
 */
object ModpackShortcutHelper {

    private const val TAG = "ModpackShortcut"

    fun hasShortcutPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> checkMiuiShortcutPermission(context)
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> checkHuaweiShortcutPermission(context)
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> checkOppoShortcutPermission(context)
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> checkVivoShortcutPermission(context)
            else -> true
        }
    }

    private fun checkMiuiShortcutPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getDeclaredMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            val mode = method.invoke(appOps, 10017, context.applicationInfo.uid, context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "MIUI shortcut permission check failed", e)
            true
        }
    }

    private fun checkHuaweiShortcutPermission(context: Context): Boolean {
        return try {
            val intent = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                putExtra("duplicate", false)
            }
            val permissionManager = Class.forName("com.huawei.hsm.permission.PermissionManager")
            Log.i(TAG, "Huawei PermissionManager class found: ${permissionManager.name}")
            val methods = permissionManager.declaredMethods
            for (m in methods) {
                Log.i(TAG, "  method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
            val method = permissionManager.getDeclaredMethod(
                "canSendBroadcast", Context::class.java, Intent::class.java
            )
            method.isAccessible = true
            val result = method.invoke(null, context, intent) as Boolean
            Log.i(TAG, "Huawei shortcut permission: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Huawei shortcut permission check failed: ${e.javaClass.simpleName}: ${e.message}")
            true
        }
    }

    private fun checkOppoShortcutPermission(context: Context): Boolean {
        return try {
            val uri = Uri.parse("content://settings/secure/launcher_shortcut_permission_settings")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val value = it.getInt(0)
                    return value == 1
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "OPPO shortcut permission check failed", e)
            true
        }
    }

    private fun checkVivoShortcutPermission(context: Context): Boolean {
        return try {
            val uri = Uri.parse("content://com.bbk.launcher2.settings/favorites")
            val cursor = context.contentResolver.query(uri, null, "packageName=?", arrayOf(context.packageName), null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val enabled = it.getInt(it.getColumnIndexOrThrow("enabled"))
                    return enabled == 1
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Vivo shortcut permission check failed", e)
            true
        }
    }

    fun tryCreateOrGuide(
        context: Context,
        packageName: String,
        modpackName: String,
        label: String,
        onFailed: () -> Unit
    ) {
        val shortcutIntent = ShortcutLauncherActivity.createIntent(context, packageName, modpackName).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val icon = getShortcutIcon(context, packageName, modpackName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val shortcut = android.content.pm.ShortcutInfo.Builder(
                    context, getShortcutId(packageName, modpackName)
                )
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(icon)
                    .setIntent(shortcutIntent)
                    .build()

                val callback = android.app.PendingIntent.getBroadcast(
                    context, 0,
                    Intent("com.pvzrh.android.shortcut.RESULT"),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val result = shortcutManager.requestPinShortcut(shortcut, callback.intentSender)
                Log.i(TAG, "requestPinShortcut result=$result for $label")
                if (!result) onFailed()
                return
            }
        }

        val broadcast = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
            putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            putExtra("duplicate", false)
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            putExtra(Intent.EXTRA_SHORTCUT_ICON, icon)
        }
        context.sendBroadcast(broadcast)
        Log.i(TAG, "Sent INSTALL_SHORTCUT broadcast for $label")
    }

    fun openShortcutPermissionSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                try {
                    val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        putExtra("extra_pkgname", context.packageName)
                        putExtra("extra_type", 1)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { openGenericSettings(context) }
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                try {
                    val intent = Intent().apply {
                        setClassName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.SingleAppActivity")
                        putExtra("packageName", context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { openGenericSettings(context) }
            }
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                try {
                    val intent = Intent().apply {
                        setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindowmanager.FloatWindowManagerActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { openGenericSettings(context) }
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                try {
                    val intent = Intent().apply {
                        setClassName("com.iqoo.secure", "com.iqoo.secure.safeguard.SoftPermissionDetailActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { openGenericSettings(context) }
            }
            else -> openGenericSettings(context)
        }
    }

    private fun openGenericSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    private fun getShortcutId(packageName: String, modpackName: String): String =
        "modpack_${packageName}_${modpackName}"

    fun createShortcut(
        context: Context,
        packageName: String,
        modpackName: String,
        label: String
    ) {
        val shortcutIntent = ShortcutLauncherActivity.createIntent(context, packageName, modpackName).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val icon = getShortcutIcon(context, packageName, modpackName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val shortcut = android.content.pm.ShortcutInfo.Builder(
                    context, getShortcutId(packageName, modpackName)
                )
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(icon)
                    .setIntent(shortcutIntent)
                    .build()

                val result = shortcutManager.requestPinShortcut(shortcut, null)
                Log.i(TAG, "requestPinShortcut result=$result for $label")
                return
            }
        }

        val broadcast = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
            putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            putExtra("duplicate", false)
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            putExtra(Intent.EXTRA_SHORTCUT_ICON, icon)
        }
        context.sendBroadcast(broadcast)
        Log.i(TAG, "Sent INSTALL_SHORTCUT broadcast for $label")
    }

    fun removeShortcut(
        context: Context,
        packageName: String,
        modpackName: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            shortcutManager?.removeDynamicShortcuts(listOf(getShortcutId(packageName, modpackName)))
        }
    }

    private fun getShortcutIcon(
        context: Context,
        packageName: String,
        modpackName: String
    ): Icon {
        val manager = ModpackManager()
        val iconFile = manager.getModpackIconFile(packageName, modpackName)

        if (iconFile != null && iconFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
            if (bitmap != null) {
                val size = 108
                val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
                return Icon.createWithBitmap(scaled)
            }
        }

        return Icon.createWithResource(context, R.mipmap.ic_launcher)
    }
}
