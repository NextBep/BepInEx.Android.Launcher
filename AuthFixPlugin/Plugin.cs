using System;
using System.IO;
using System.Reflection;
using BepInEx;
using BepInEx.Logging;
using BepInEx.Unity.IL2CPP;
using HarmonyLib;

namespace NextBep.AuthFix;

[BepInPlugin("dev.nextbep.authfix", "NextBep AuthFix", "1.0.0")]
public class AuthFixPlugin : BasePlugin
{
    private const int EOS_ECT_GOOGLE_ID_TOKEN = 13;
    private const int EOS_ECT_DEVICEID_ACCESS_TOKEN = 10;

    private static readonly string ShareMessagesPath =
        "/storage/emulated/0/BepInEx_Android/share_messages.txt";

    private static readonly string InnerslothUrl =
        "https://accounts.innersloth.com";

    private static ManualLogSource? _logger;

    public override void Load()
    {
        _logger = Log;
        Log.LogInfo("[AuthFix] Loading...");

        var harmony = new Harmony("dev.nextbep.authfix");

        PatchMethod(harmony, "EOSManager:InitializePlatformImpl",
            nameof(SkipPlatformInit));

        PatchMethod(harmony, "EOSManager:LoginWithCorrectPlatformImpl",
            nameof(LoginWithGoogleToken));

        Log.LogInfo("[AuthFix] Loaded");
    }

    private void PatchMethod(Harmony harmony, string signature, string prefixName)
    {
        try
        {
            var method = AccessTools.Method(signature);
            if (method != null)
            {
                harmony.Patch(method,
                    prefix: new HarmonyMethod(typeof(AuthFixPlugin), prefixName));
                Log.LogInfo($"[AuthFix] Patched {signature}");
            }
            else
            {
                Log.LogWarning($"[AuthFix] {signature} not found");
            }
        }
        catch (Exception ex)
        {
            Log.LogError($"[AuthFix] Failed to patch {signature}: {ex.Message}");
        }
    }

    public static bool SkipPlatformInit()
    {
        _logger?.LogInfo("[AuthFix] Skipping InitializePlatformImpl");
        return false;
    }

    public static bool LoginWithGoogleToken(object __instance, object[] __args)
    {
        try
        {
            var successCallback = __args.Length > 0 ? __args[0] : null;
            string? googleToken = ReadAndClearToken();
            bool hasToken = !string.IsNullOrEmpty(googleToken);

            if (hasToken)
            {
                _logger?.LogInfo($"[AuthFix] Using Google token ({googleToken!.Length} chars)");
                CallEosLogin(__instance, EOS_ECT_GOOGLE_ID_TOKEN, googleToken, successCallback);
            }
            else
            {
                _logger?.LogInfo("[AuthFix] No token, opening browser for OAuth...");
                OpenBrowser(InnerslothUrl);
                _logger?.LogInfo("[AuthFix] Falling back to device ID login");
                CallEosLogin(__instance, EOS_ECT_DEVICEID_ACCESS_TOKEN, "DUMMY", successCallback);
            }

            try { SetMember(__instance, "stopTimeOutCheck", true); } catch { }
            return false;
        }
        catch (Exception ex)
        {
            _logger?.LogError($"[AuthFix] LoginWithGoogleToken: {ex}");
            return true;
        }
    }

    private static string? ReadAndClearToken()
    {
        try
        {
            if (!File.Exists(ShareMessagesPath))
                return null;

            string content = File.ReadAllText(ShareMessagesPath).Trim();
            if (string.IsNullOrEmpty(content)) return null;

            _logger?.LogInfo($"[AuthFix] Read share_messages.txt ({content.Length} chars)");

            string? token = null;
            int tokenIdx = content.IndexOf("token=", StringComparison.OrdinalIgnoreCase);
            if (tokenIdx >= 0)
            {
                string afterToken = content.Substring(tokenIdx + 6);
                int endIdx = afterToken.IndexOf('&');
                if (endIdx >= 0) afterToken = afterToken.Substring(0, endIdx);
                afterToken = Uri.UnescapeDataString(afterToken);
                if (afterToken.Length > 10) token = afterToken;
            }

            try { File.Delete(ShareMessagesPath); }
            catch (Exception ex) { _logger?.LogWarning($"[AuthFix] Failed to delete token: {ex.Message}"); }

            return token;
        }
        catch (Exception ex)
        {
            _logger?.LogWarning($"[AuthFix] ReadAndClearToken: {ex.Message}");
            return null;
        }
    }

    private static Type? FindAndroidType(string simpleName)
    {
        foreach (var asm in AppDomain.CurrentDomain.GetAssemblies())
        {
            try
            {
                foreach (var t in asm.GetTypes())
                {
                    if (t.FullName == simpleName || t.FullName == $"UnityEngine.{simpleName}")
                        return t;
                }
            }
            catch (ReflectionTypeLoadException) { }

            var byName = asm.GetType($"UnityEngine.{simpleName}", throwOnError: false);
            if (byName != null) return byName;
        }
        return null;
    }

    private static void OpenBrowser(string url)
    {
        try
        {
            var androidJavaClass = FindAndroidType("AndroidJavaClass");
            var androidJavaObject = FindAndroidType("AndroidJavaObject");

            if (androidJavaClass == null || androidJavaObject == null)
            {
                _logger?.LogError("[AuthFix] AndroidJavaClass/AndroidJavaObject not found");
                return;
            }

            var uriClass = Activator.CreateInstance(androidJavaClass, "android.net.Uri");
            var callStaticMethod = androidJavaClass.GetMethod("CallStatic",
                new[] { typeof(string), typeof(object[]) });
            var parsedUri = callStaticMethod?.Invoke(uriClass,
                new object[] { "parse", new object[] { url } });

            var intent = Activator.CreateInstance(androidJavaObject,
                "android.content.Intent", "android.intent.action.VIEW", parsedUri);

            var upClass = Activator.CreateInstance(androidJavaClass, "com.unity3d.player.UnityPlayer");
            var getStaticMethod = androidJavaClass.GetMethod("GetStatic", new[] { typeof(string) });
            var activity = getStaticMethod?.Invoke(upClass, new object[] { "currentActivity" });

            var callMethod = androidJavaObject.GetMethod("Call", new[] { typeof(string), typeof(object[]) });
            callMethod?.Invoke(activity, new object[] { "startActivity", new object[] { intent } });

            _logger?.LogInfo("[AuthFix] Browser opened");
        }
        catch (Exception ex)
        {
            _logger?.LogError($"[AuthFix] OpenBrowser failed: {ex}");
        }
    }

    private static bool CallEosLogin(object eos, int credType, string token, object? callback)
    {
        try
        {
            var credentialsType = FindType("Epic.OnlineServices.Connect.Credentials");
            var loginOptionsType = FindType("Epic.OnlineServices.Connect.LoginOptions");
            var externalCredType = FindType("Epic.OnlineServices.ExternalCredentialType");
            var utf8StringType = FindType("Epic.OnlineServices.Utf8String");

            if (credentialsType == null || loginOptionsType == null ||
                externalCredType == null || utf8StringType == null)
            {
                _logger?.LogError("[AuthFix] EOS types not found");
                return false;
            }

            object utf8Token;
            try { utf8Token = Activator.CreateInstance(utf8StringType, token); }
            catch { return false; }

            var creds = Activator.CreateInstance(credentialsType);
            SetMember(creds, "Type", Enum.ToObject(externalCredType, credType));
            SetMember(creds, "Token", utf8Token);

            var options = Activator.CreateInstance(loginOptionsType);
            Type? nullableType;
            var il2cppNullable = FindType("Il2CppSystem.Nullable`1");
            nullableType = il2cppNullable != null
                ? il2cppNullable.MakeGenericType(credentialsType)
                : typeof(Nullable<>).MakeGenericType(credentialsType);

            SetMember(options, "Credentials",
                Activator.CreateInstance(nullableType, creds));

            var platform = GetMember(eos, "PlatformInterface");
            if (platform == null) return false;

            var getConnect = platform.GetType().GetMethod("GetConnectInterface",
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
            if (getConnect == null) return false;

            var connect = getConnect.Invoke(platform, null);
            if (connect == null) return false;

            var loginMethod = connect.GetType().GetMethod("Login",
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
            if (loginMethod == null) return false;

            loginMethod.Invoke(connect, new[] { options, null, callback });
            _logger?.LogInfo($"[AuthFix] EOS Login OK (type={credType})");
            return true;
        }
        catch (Exception ex)
        {
            _logger?.LogError($"[AuthFix] CallEosLogin: {ex}");
            return false;
        }
    }

    private static void SetMember(object target, string name, object? value)
    {
        var prop = target.GetType().GetProperty(name,
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        if (prop != null && prop.CanWrite) { prop.SetValue(target, value); return; }
        var field = target.GetType().GetField(name,
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        if (field != null) { field.SetValue(target, value); return; }
    }

    private static object? GetMember(object target, string name)
    {
        var prop = target.GetType().GetProperty(name,
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        if (prop != null) return prop.GetValue(target);
        var field = target.GetType().GetField(name,
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        if (field != null) return field.GetValue(target);
        return null;
    }

    private static Type? FindType(string fullName)
    {
        foreach (var asm in AppDomain.CurrentDomain.GetAssemblies())
        {
            var t = asm.GetType(fullName, throwOnError: false);
            if (t != null) return t;
        }
        return null;
    }
}
