/*
 * BepInEx.Android 鈥?.NET CoreCLR bootstrap
 *
 * Starts the .NET CoreCLR runtime and calls BepInEx entry points.
 * Called from the il2cpp_init hook callback, AFTER Unity's native init
 * but BEFORE Unity scripts start running.
 */

#include "fusion.h"
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "Fusion/DotNet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// CoreCLR host API types

typedef int (*coreclr_initialize_fn)(
    const char *exePath,
    const char *appDomainFriendlyName,
    int propertyCount,
    const char **propertyKeys,
    const char **propertyValues,
    void **hostHandle,
    unsigned int *domainId);

typedef int (*coreclr_create_delegate_fn)(
    void *hostHandle,
    unsigned int domainId,
    const char *assemblyName,
    const char *typeName,
    const char *methodName,
    void **delegate);

// Helpers

static bool str_ends_with(const char *str, const char *suffix)
{
    size_t slen = strlen(str);
    size_t xlen = strlen(suffix);
    if (xlen > slen) return false;
    return strcasecmp(str + slen - xlen, suffix) == 0;
}

static bool file_exists(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0;
}

static std::vector<std::string> scan_dlls(const std::string &dir)
{
    std::vector<std::string> result;
    DIR *d = opendir(dir.c_str());
    if (!d) {
        LOGW("Cannot open dir: %s (%s)", dir.c_str(), strerror(errno));
        return result;
    }

    struct dirent *entry;
    while ((entry = readdir(d)) != nullptr) {
        if (entry->d_type != DT_REG && entry->d_type != DT_LNK
            && entry->d_type != DT_UNKNOWN)
            continue;
        if (!str_ends_with(entry->d_name, ".dll"))
            continue;
        result.push_back(dir + "/" + entry->d_name);
    }
    closedir(d);
    std::sort(result.begin(), result.end());
    return result;
}

// CoreCLR startup

bool dotnet_start_runtime()
{
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "dotnet_start_runtime enter");
    LOGI("=== Starting CoreCLR + BepInEx ===");

    /* Resolve CoreCLR */
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "dotnet: resolving coreclr");
    void *coreclr = dlopen("libcoreclr.so", RTLD_NOLOAD);
    if (!coreclr) {
        __android_log_write(ANDROID_LOG_ERROR, "FusionB", "dotnet: libcoreclr.so NOT loaded");
        return false;
    }
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "dotnet: coreclr found");

    auto init_fn = (coreclr_initialize_fn)dlsym(coreclr, "coreclr_initialize");
    auto delegate_fn = (coreclr_create_delegate_fn)dlsym(coreclr, "coreclr_create_delegate");
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", init_fn ? "coreclr_initialize found" : "coreclr_initialize MISSING");
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", delegate_fn ? "coreclr_create_delegate found" : "coreclr_create_delegate MISSING");
    if (!init_fn || !delegate_fn) {
        return false;
    }

    /* Find BepInEx core directory */
    std::string bepInExCore = g_config.bepInExDir + "/core";
    std::string dotnetRuntime = g_config.dotnetDir;

    LOGI("BepInEx core: %s", bepInExCore.c_str());
    LOGI("DotNet runtime: %s", dotnetRuntime.c_str());

    if (!file_exists(bepInExCore.c_str())) {
        LOGE("BepInEx core dir not found: %s", bepInExCore.c_str());
        return false;
    }

    /* Build TPA list */
    auto coreDlls = scan_dlls(bepInExCore);
    auto rtDlls = scan_dlls(dotnetRuntime);

    LOGI("Assemblies: %zu in core, %zu in dotnet", coreDlls.size(), rtDlls.size());

    if (coreDlls.empty()) {
        LOGE("No .dll files in %s", bepInExCore.c_str());
        return false;
    }

    std::string tpa;
    for (size_t i = 0; i < coreDlls.size(); i++) {
        if (i > 0) tpa += ":";
        tpa += coreDlls[i];
    }
    for (size_t i = 0; i < rtDlls.size(); i++) {
        tpa += ":";
        tpa += rtDlls[i];
    }

    /* Runtime config */
    std::string runtimeconfig = bepInExCore +
        "/BepInEx.Unity.IL2CPP.runtimeconfig.json";
    if (!file_exists(runtimeconfig.c_str())) {
        runtimeconfig = dotnetRuntime + "/Microsoft.NETCore.App.runtimeconfig.json";
        LOGI("Using fallback runtimeconfig: %s", runtimeconfig.c_str());
    } else {
        LOGI("Runtime config: %s", runtimeconfig.c_str());
    }

    /* CoreCLR properties 鈥?exact FusionCore pattern */
    /*
     * FusionCore uses only 3 keys. Enables needed crypto by letting CoreCLR
     * resolve native libraries through Android's default linker paths.
     */
#define NUM_KEYS 3
    const char *propertyKeys[NUM_KEYS] = {
        "TRUSTED_PLATFORM_ASSEMBLIES",
        "APP_PATHS",
        "APP_CONTEXT_BASE_DIRECTORY"
    };

    std::string appPaths = bepInExCore + ":" + dotnetRuntime;
    std::string tpa_list = tpa;

    const char *propertyValues[NUM_KEYS] = {
        tpa_list.c_str(),
        appPaths.c_str(),
        bepInExCore.c_str()
    };

    setenv("DOTNET_ReadyToRun", "0", 1);

    /* Initialize CoreCLR 鈥?with W^X retry (FusionCore pattern) */
    setenv("DOTNET_EnableWriteXorExecute", "0", 1);

    void *host = nullptr;
    unsigned int dom = 0;
    int rc = -1;

    for (int attempt = 1; attempt <= 2; ++attempt) {
        host = nullptr;
        dom = 0;
        rc = init_fn(runtimeconfig.c_str(), "BepInExHost",
                     NUM_KEYS, propertyKeys, propertyValues, &host, &dom);

        { char buf[64]; snprintf(buf, sizeof(buf), "coreclr_initialize attempt %d rc=%d", attempt, rc);
          __android_log_write(ANDROID_LOG_ERROR, "FusionB", buf); }

        if (rc >= 0) break;

        if (attempt == 1) {
            setenv("DOTNET_EnableWriteXorExecute", "1", 1);
            LOGI("Retrying CoreCLR with W^X enabled");
            usleep(75000);
        }
    }

    if (rc >= 0) {
        LOGI("CoreCLR started (host=%p, domain=%u)", host, dom);
        __android_log_write(ANDROID_LOG_ERROR, "FusionB", "CoreCLR started!");
    } else {
        LOGE("coreclr_initialize failed: 0x%x", rc);
        return false;
    }

    /* Call BepInEx entry point 鈥?NextCore style */
    // NextCoreEntrypoint.Start reads NEXT_* env vars via EnvVars.LoadVars()
    // and calls UnityPreloaderRunner.PreloaderMain internally.
    void *bepInExEntry = nullptr;
    int entryRc = delegate_fn(host, dom,
        "BepInEx.Unity.IL2CPP",
        "BepInEx.Unity.IL2CPP.NextCoreEntrypoint", "Start",
        &bepInExEntry);
    { char buf[160]; snprintf(buf, sizeof(buf), "delegate NextCoreEntrypoint.Start rc=%d", entryRc); __android_log_write(ANDROID_LOG_ERROR, "FusionB", buf); }
    if (entryRc == 0 && bepInExEntry) {
        __android_log_write(ANDROID_LOG_ERROR, "FusionB", "Found entry point!");
    }

    if (bepInExEntry) {
        __android_log_write(ANDROID_LOG_ERROR, "FusionB", "Calling BepInEx entry...");
        std::string bepinRoot = g_config.bepInExDir;
        chdir(bepinRoot.c_str());
        mkdir((bepinRoot + "/logs").c_str(), 0755);
        typedef void (*entry_fn)();
        ((entry_fn)bepInExEntry)();
        __android_log_write(ANDROID_LOG_ERROR, "FusionB", "BepInEx done");
    } else {
        __android_log_write(ANDROID_LOG_ERROR, "FusionB", "No BepInEx entry found");
    }

    return true;
}
