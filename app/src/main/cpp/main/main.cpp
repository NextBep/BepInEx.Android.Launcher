﻿/*
 * BepInEx.Android 鈥?libmain.so (FusionCore main branch pattern)
 *
 * This is a CUSTOM libmain.so that replaces the game's original libmain.so.
 * It is loaded BY the game's ClassLoader (via findLibrary Pine hook redirect),
 * so JNI FindClass here searches the GAME's DEX 鈥?therefore we can find
 * com.unity3d.player.NativeLoader directly.
 *
 * Architecture:
 *   - NOT linked to libfusion.so (resolved dynamically via dlopen/dlsym)
 *   - JNI_OnLoad: registers custom load/unload on NativeLoader
 *   - load(): resolves fusion symbols 鈫?stages config 鈫?loads unity+il2cpp 鈫? *            bootstraps fusion (which hooks il2cpp_init)
 *
 * This is the entry point that bridges Unity's native loading to BepInEx.
 */

#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "LibMain"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using JNI_OnLoad_t = jint (*)(JavaVM *vm, void *reserved);
using JNI_Unload_t = void (*)(JavaVM *vm, void *reserved);
using FusionStageFromConfigPath_t = bool (*)(const char *configPath);
using FusionBootstrapFromLibMain_t = bool (*)(JNIEnv *env);

static FILE *logFile = nullptr;
static std::string log_path;
static std::string override_unity_path;
static std::string override_il2cpp_path;

static void *unityLibHandle = nullptr;
static void *il2cppLibHandle = nullptr;

static void nlog(const char *msg) {
    __android_log_write(ANDROID_LOG_ERROR, "LibMainN", msg);
}

// Sibling library path resolution

static std::string build_sibling_library_path(const char *libraryFileName)
{
    Dl_info info{};
    if (dladdr(reinterpret_cast<void *>(&build_sibling_library_path), &info) == 0 || !info.dli_fname) {
        return {};
    }
    std::string selfPath(info.dli_fname);
    size_t lastSlash = selfPath.find_last_of('/');
    if (lastSlash == std::string::npos) return {};
    return selfPath.substr(0, lastSlash + 1) + libraryFileName;
}

static bool preload_sibling_library(const char *libraryFileName)
{
    const std::string siblingPath = build_sibling_library_path(libraryFileName);
    if (siblingPath.empty()) {
        LOGE("preload_sibling_library: failed to build path for %s", libraryFileName);
        return false;
    }
    dlerror();
    void *handle = dlopen(siblingPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char *err = dlerror();
        LOGE("preload_sibling_library: dlopen failed for %s at %s: %s",
             libraryFileName, siblingPath.c_str(), err ? err : "(no dlerror)");
        return false;
    }
    LOGI("preload_sibling_library: loaded %s from %s", libraryFileName, siblingPath.c_str());
    return true;
}

static bool preload_dotnet_runtime_libraries()
{
    const char *required[] = {
        "libcoreclr.so", "libclrjit.so",
        "libSystem.Native.so",
        "libSystem.Globalization.Native.so",
        "libSystem.IO.Compression.Native.so",
        "libcrypto.so", "libssl.so",
        "libSystem.Security.Cryptography.Native.OpenSsl.so"
    };
    for (const char *libName : required) {
        if (!preload_sibling_library(libName)) {
            LOGE("preload_dotnet_runtime_libraries: failed to load %s", libName);
            return false;
        }
    }
    preload_sibling_library("libmscordaccore.so");
    preload_sibling_library("libmscordbi.so");
    return true;
}

// Resolve libfusion.so at runtime

static void *resolve_or_load_fusion_handle()
{
    dlerror();
    void *fusionHandle = dlopen("libfusion.so", RTLD_NOW | RTLD_NOLOAD);
    if (fusionHandle) {
        nlog("fusion: already loaded");
        return fusionHandle;
    }
    nlog("fusion: not loaded, preloading");

    preload_sibling_library("libdobby.so");
    nlog("fusion: dobby preloaded");
    if (!preload_dotnet_runtime_libraries()) { nlog("fusion: dotnet FAILED"); return nullptr; }
    nlog("fusion: dotnet OK");
    if (!preload_sibling_library("libfusion.so")) { nlog("fusion: fusion.so FAILED"); return nullptr; }
    nlog("fusion: fusion.so preloaded OK");

    dlerror();
    fusionHandle = dlopen("libfusion.so", RTLD_NOW | RTLD_NOLOAD);
    if (!fusionHandle) {
        nlog("fusion: STILL not visible");
        return nullptr;
    }
    nlog("fusion: all OK");
    return fusionHandle;
}

static bool resolve_fusion_symbols(FusionStageFromConfigPath_t *stageFromConfig,
                                   FusionBootstrapFromLibMain_t *bootstrap)
{
    void *fusionHandle = resolve_or_load_fusion_handle();
    if (!fusionHandle) return false;

    dlerror();
    *stageFromConfig = reinterpret_cast<FusionStageFromConfigPath_t>(
        dlsym(fusionHandle, "fusion_stage_from_config_path"));
    if (!*stageFromConfig) {
        LOGE("dlsym fusion_stage_from_config_path failed: %s", dlerror());
        return false;
    }

    dlerror();
    *bootstrap = reinterpret_cast<FusionBootstrapFromLibMain_t>(
        dlsym(fusionHandle, "fusion_bootstrap_from_libmain"));
    if (!*bootstrap) {
        LOGE("dlsym fusion_bootstrap_from_libmain failed: %s", dlerror());
        return false;
    }

    return true;
}

// Config path resolution

static std::string jstring_to_string(JNIEnv *env, jstring value)
{
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static std::string resolve_staged_config_path(JNIEnv *env)
{
    /* Resolve app's internal files dir via ActivityThread.currentApplication() */
    jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
    if (!activityThreadClass) {
        LOGE("ActivityThread class not found");
        return {};
    }

    jmethodID currentApp = env->GetStaticMethodID(activityThreadClass,
        "currentApplication", "()Landroid/app/Application;");
    if (!currentApp) {
        LOGE("currentApplication method not found");
        return {};
    }

    jobject app = env->CallStaticObjectMethod(activityThreadClass, currentApp);
    if (!app) {
        LOGE("currentApplication returned null");
        return {};
    }

    jclass contextClass = env->FindClass("android/content/Context");
    jmethodID getFilesDir = env->GetMethodID(contextClass, "getFilesDir",
        "()Ljava/io/File;");
    jobject filesDirObj = env->CallObjectMethod(app, getFilesDir);
    if (!filesDirObj) {
        LOGE("getFilesDir returned null");
        return {};
    }

    jclass fileClass = env->FindClass("java/io/File");
    jmethodID getAbsolutePath = env->GetMethodID(fileClass, "getAbsolutePath",
        "()Ljava/lang/String;");
    jstring pathStr = (jstring)env->CallObjectMethod(filesDirObj, getAbsolutePath);

    std::string filesDir = jstring_to_string(env, pathStr);
    return filesDir + "/bootstrap/active.cfg";
}

// Library load/unload helpers

jboolean internal_load(JNIEnv *env, const char *libraryPath, void **libHandle)
{
    nlog(libraryPath ? libraryPath : "internal_load: NULL path");
    if (!libraryPath) {
        return JNI_FALSE;
    }

    void *handle = dlopen(libraryPath, RTLD_LAZY | RTLD_LOCAL);
    nlog(handle ? "internal_load: dlopen OK" : "internal_load: dlopen FAILED");
    if (!handle) {
        return JNI_FALSE;
    }

    /* Call JNI_OnLoad if present (libil2cpp.so may not have it) */
    nlog("internal_load: checking JNI_OnLoad");
    JNI_OnLoad_t jniOnLoad = reinterpret_cast<JNI_OnLoad_t>(
        dlsym(handle, "JNI_OnLoad"));
    if (jniOnLoad) {
        nlog("internal_load: calling JNI_OnLoad");
        JavaVM *vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK) {
            nlog("internal_load: GetJavaVM FAILED");
            dlclose(handle);
            return JNI_FALSE;
        }
        jint result = jniOnLoad(vm, nullptr);
        nlog("internal_load: JNI_OnLoad returned");
        if (result < JNI_VERSION_1_6) {
            dlclose(handle);
            return JNI_FALSE;
        }
    }

    *libHandle = handle;
    nlog("internal_load: done");
    return JNI_TRUE;
}

jboolean internal_unload(JNIEnv *env, void **libHandle)
{
    if (!*libHandle) return JNI_FALSE;

    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return JNI_FALSE;

    void *handle = *libHandle;
    *libHandle = nullptr;

    void *unloadSym = dlsym(handle, "JNI_Unload");
    if (unloadSym) {
        JNI_Unload_t jniUnload = reinterpret_cast<JNI_Unload_t>(unloadSym);
        jniUnload(vm, nullptr);
    }
    dlclose(handle);
    return JNI_TRUE;
}

// Exports

extern "C" {

void libmain_set_override_unity_path(const char *path)
{
    override_unity_path = path;
    LOGI("override unity path: %s", path ? path : "(null)");
}

void libmain_set_override_il2cpp_path(const char *path)
{
    override_il2cpp_path = path;
    LOGI("override il2cpp path: %s", path ? path : "(null)");
}

const char *libmain_get_override_unity_path()
{
    return override_unity_path.c_str();
}

const char *libmain_get_override_il2cpp_path()
{
    return override_il2cpp_path.c_str();
}

void libmain_set_log_path(const char *path)
{
    log_path = path;
    if (logFile) { fclose(logFile); logFile = nullptr; }
    if (!log_path.empty()) {
        logFile = fopen(log_path.c_str(), "w");
        if (logFile) LOGI("Logging to %s", log_path.c_str());
    }
}

// NativeLoader.load / unload replacement

JNIEXPORT jboolean JNICALL
load(JNIEnv *env, jobject activityObject, jstring path)
{
    nlog("load() called");
    (void)path;

    /* 1. Resolve libfusion symbols */
    nlog("load: resolving fusion symbols");
    FusionStageFromConfigPath_t stageFromConfig = nullptr;
    FusionBootstrapFromLibMain_t bootstrap = nullptr;
    if (!resolve_fusion_symbols(&stageFromConfig, &bootstrap)) {
        nlog("load: FAILED to resolve fusion symbols");
        return JNI_FALSE;
    }
    nlog("load: fusion symbols resolved OK");

    /* 2. Read staged config */
    nlog("load: resolving config path");
    std::string configPath = resolve_staged_config_path(env);
    if (configPath.empty()) {
        nlog("load: FAILED to resolve config path");
        return JNI_FALSE;
    }
    nlog("load: config path OK");

    /* 3. Stage fusion */
    nlog("load: calling fusion_stage_from_config_path");
    if (!stageFromConfig(configPath.c_str())) {
        nlog("load: fusion_stage_from_config_path FAILED");
        return JNI_FALSE;
    }
    nlog("load: fusion staged OK");

    /* 4. Load Unity + IL2CPP */
    nlog("load: loading libunity");
    if (!internal_load(env, override_unity_path.c_str(), &unityLibHandle)) {
        nlog("load: FAILED to load libunity.so");
        return JNI_FALSE;
    }
    nlog("load: libunity OK");

    if (!internal_load(env, override_il2cpp_path.c_str(), &il2cppLibHandle)) {
        internal_unload(env, &unityLibHandle);
        nlog("load: FAILED to load libil2cpp.so");
        return JNI_FALSE;
    }
    nlog("load: libil2cpp OK");

    /* 5. Bootstrap fusion */
    nlog("load: calling fusion_bootstrap_from_libmain");
    if (!bootstrap(env)) {
        nlog("load: fusion_bootstrap FAILED");
        return JNI_FALSE;
    }

    nlog("load: COMPLETE");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
unload(JNIEnv *env, jclass activityObject)
{
    if (unityLibHandle) internal_unload(env, &unityLibHandle);
    if (il2cppLibHandle) internal_unload(env, &il2cppLibHandle);
    if (logFile) { fclose(logFile); logFile = nullptr; }
    LOGI("unload complete");
    return JNI_TRUE;
}

// Constructor runs before JNI_OnLoad 鈥?verifies library code executes at all
__attribute__((constructor)) static void libmain_ctor() {
    nlog("libmain constructor");
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    nlog("JNI_OnLoad enter");

    JNIEnv *globalEnv;
    if (vm->GetEnv(reinterpret_cast<void **>(&globalEnv), JNI_VERSION_1_6) != JNI_OK) {
        nlog("GetEnv FAILED");
        return JNI_ERR;
    }

    jclass clazz = globalEnv->FindClass("com/unity3d/player/NativeLoader");
    if (!clazz) {
        nlog("FindClass NativeLoader FAILED");
        if (globalEnv->ExceptionCheck()) {
            globalEnv->ExceptionDescribe();
            globalEnv->ExceptionClear();
        }
        return JNI_ERR;
    }

    static const JNINativeMethod methods[] = {
        {"load",   "(Ljava/lang/String;)Z", reinterpret_cast<void *>(load)},
        {"unload", "()Z",                   reinterpret_cast<void *>(unload)}
    };

    jint ret = globalEnv->RegisterNatives(clazz, methods,
        sizeof(methods) / sizeof(JNINativeMethod));
    if (ret != JNI_OK) {
        nlog("RegisterNatives FAILED");
        return ret;
    }

    nlog("RegisterNatives OK");
    return JNI_VERSION_1_6;
}

} /* extern "C" */
