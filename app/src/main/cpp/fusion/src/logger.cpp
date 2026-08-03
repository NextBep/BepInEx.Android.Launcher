/*
 * BepInEx.Android — Fusion logger
 *
 * Centralized logging for the fusion native library.
 * Routes messages to Android logcat.
 */

#include <android/log.h>

#define LOG_TAG "Fusion/Log"

extern "C" {

void fusion_log_info(const char *msg)
{
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", msg);
}

void fusion_log_error(const char *msg)
{
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", msg);
}

void fusion_log_warning(const char *msg)
{
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", msg);
}

} /* extern "C" */
