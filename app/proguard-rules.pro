# Keep Unity-related classes — required by native libmain.so JNI
-keep class com.unity3d.player.** { *; }
-keep class com.bepinex.android.** { *; }

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI callback classes
-keep class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Pine hook framework
-keep class top.canyie.pine.** { *; }
-dontwarn top.canyie.pine.**

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
