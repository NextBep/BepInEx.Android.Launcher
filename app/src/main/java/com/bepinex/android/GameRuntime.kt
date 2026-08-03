package com.bepinex.android

/**
 * Describes a target Unity IL2CPP game for the FusionCore launcher.
 *
 * Each runtime specifies the game's identity, Unity version, and the
 * Java package where UnityPlayer classes reside. This information is
 * used by [BootstrapActivity] and native libmain/libfusion to resolve
 * classes, libraries, and hook targets.
 *
 * ## Extensibility
 *
 * Currently hardcoded for Among Us. Future versions will support
 * multiple games via dynamic discovery from extracted APK metadata.
 */
data class GameRuntime(
    /** Android package name (e.g. "com.innersloth.spacemafia") */
    val id: String,

    /** Human-readable display name (e.g. "Among Us") */
    val name: String,

    /** Unity engine version (e.g. "2022.3.62f3") */
    val unityVersion: String = "",

    /** Java package containing UnityPlayer/NativeLoader classes */
    val unityJavaPackage: String = "com.unity3d.player"
)
