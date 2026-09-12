package com.bepinex.android.fusion;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Display;
import android.hardware.display.DisplayManager;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Direct port of FusionCore's CustomContextWrapper.
 * Used to wrap the UnityPlayer constructor argument.
 */
public class GameContextWrapper extends ContextWrapper {
    Context fusionContext;
    Context appContext;
    private android.content.pm.ApplicationInfo modifiedAppInfo;

    public GameContextWrapper(Context gameContext, Context fusionContext, Context appContext) {
        super(gameContext);
        this.fusionContext = fusionContext;
        this.appContext = fusionContext;
        // Create a copy of ApplicationInfo and modify it — do NOT mutate the
        // original instance (it is shared with the real Activity and modifying
        // it corrupts the package context for the entire process).
        this.modifiedAppInfo = new android.content.pm.ApplicationInfo(gameContext.getApplicationInfo());
        this.modifiedAppInfo.dataDir = appContext.getApplicationInfo().dataDir;
        // Hide the game's native library dir so System.loadLibrary is routed
        // through findLibrary. Use an empty string (not the launcher dir) to
        // avoid changing the game's native namespace.
        this.modifiedAppInfo.nativeLibraryDir = "";
    }

    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        return modifiedAppInfo;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return this.fusionContext.getSharedPreferences(name, mode);
    }

    public boolean deleteSharedPreferences(String name) {
        return this.fusionContext.deleteSharedPreferences(name);
    }

    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return this.fusionContext.moveSharedPreferencesFrom(sourceContext, name);
    }

    @Override
    public File getFilesDir() {
        return this.fusionContext.getFilesDir();
    }

    @Override
    public File getCacheDir() {
        return this.fusionContext.getCacheDir();
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return this.fusionContext.getExternalCacheDir();
    }

    @Override
    public File[] getExternalCacheDirs() {
        return this.fusionContext.getExternalCacheDirs();
    }

    @Override
    public File getExternalFilesDir(String type) {
        return this.fusionContext.getExternalFilesDir(type);
    }

    @Override
    public Display getDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Display display = this.fusionContext.getDisplay();
            if (display != null) return display;

            // Some Huawei/HarmonyOS builds temporarily return null from a
            // window context during rotation. The original Activity and the
            // display manager remain valid during that transition.
            if (this.fusionContext instanceof android.app.Activity) {
                display = ((android.app.Activity) this.fusionContext).getDisplay();
                if (display != null) return display;
            }
            DisplayManager manager = (DisplayManager) this.fusionContext
                    .getSystemService(Context.DISPLAY_SERVICE);
            if (manager != null) return manager.getDisplay(Display.DEFAULT_DISPLAY);
        }
        return null;
    }

    @Override
    public Object getSystemService(String name) {
        return this.fusionContext.getSystemService(name);
    }

    @Override
    public Context getBaseContext() {
        return super.getBaseContext();
    }

    @Override
    public Context getApplicationContext() {
        return appContext;
    }

    @Override
    public File getObbDir() {
        return null;
    }

    @Override
    public File[] getObbDirs() {
        return this.fusionContext.getObbDirs();
    }
}
