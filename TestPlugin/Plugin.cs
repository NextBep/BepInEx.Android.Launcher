using BepInEx;
using BepInEx.Unity.IL2CPP;
using BepInEx.Logging;

namespace BepInExTest;

[BepInPlugin("com.bepinex.test", "BepInEx Test Plugin", "1.0.0")]
public class TestPlugin : BasePlugin
{
    public override void Load()
    {
        Log.LogInfo("========================================");
        Log.LogInfo("  BepInEx Test Plugin — Load() called!");
        Log.LogInfo("========================================");
        Log.LogMessage("Message level works");
        Log.LogInfo("Info level works");
        Log.LogDebug("Debug level works");
        Log.LogWarning("Warning level works");
        Log.LogError("Error level works");
        Log.LogInfo("  Plugin GUID: " + ((BepInPlugin)GetType().GetCustomAttributes(typeof(BepInPlugin), false)[0]).GUID);
        Log.LogInfo("========================================");
    }
}
