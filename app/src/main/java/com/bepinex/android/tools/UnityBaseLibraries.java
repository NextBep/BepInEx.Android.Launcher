package com.bepinex.android.tools;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the managed Unity base libraries ZIP that BepInEx should use.
 *
 * The managed Il2CppInteropManager reads the [IL2CPP] UnityBaseLibrariesSource
 * config option to locate the ZIP. The default source points at
 * {@code https://unity.bepinex.dev/libraries/{VERSION}.zip}; when the exact
 * Unity version is not available on the server, this class rewrites the config
 * to the closest older patch release and remembers the rewritten value in a
 * marker file.
 *
 * The caller is expected to pre-download the resolved ZIP with Android's HTTP
 * stack (see {@code UnityLibsDownloader}) so that managed code never performs
 * the HTTPS download through .NET HttpClient (which crashes on Android 16).
 */
public final class UnityBaseLibraries {
    private static final String TAG = "UnityBaseLibraries";
    private static final String SERVER = "https://unity.bepinex.dev/libraries/";
    private static final String DEFAULT_SOURCE = SERVER + "{VERSION}.zip";
    private static final String KEY = "UnityBaseLibrariesSource";
    private static final String AUTO_MARKER = ".bepinex_base_libraries";
    private static final int TIMEOUT_MS = 5000;

    private UnityBaseLibraries() {}

    /**
     * Resolves the Unity base libraries source for the given Unity version.
     *
     * Rewrites [IL2CPP] UnityBaseLibrariesSource in BepInEx.cfg when the exact
     * version is unavailable and an older patch release exists.
     *
     * @return the resolved source URL (e.g. {@code https://unity.bepinex.dev/libraries/2022.3.60.zip}),
     *         or null when the version cannot be resolved, the config is missing,
     *         or the user replaced the source manually.
     */
    public static String resolve(File bepInExDir, String unityVersion) {
        Matcher match = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(unityVersion);
        if (!match.find()) return null;
        String majorMinor = match.group(1) + "." + match.group(2);
        int patch = Integer.parseInt(match.group(3));
        String exact = majorMinor + "." + patch;

        File config = new File(bepInExDir, "config/BepInEx.cfg");
        File marker = new File(config.getParentFile(), AUTO_MARKER);
        try {
            String text = readText(config);
            Matcher line = Pattern.compile("(?m)^" + KEY + "\\s*=\\s*(.*)$").matcher(text);
            if (!line.find()) return null;
            String current = line.group(1).trim();
            String auto = marker.isFile() ? readText(marker).trim() : null;
            // An empty source is our own "auto-download disabled" fallback; treat it as
            // restorable. Any other custom value is left untouched.
            boolean restorable = current.isEmpty();
            if (!restorable && !current.equals(DEFAULT_SOURCE) && !current.equals(auto)) {
                Log.i(TAG, "UnityBaseLibrariesSource customized, leaving as-is: " + current);
                return null;
            }

            String source = DEFAULT_SOURCE;
            boolean exactAvailable = new File(bepInExDir, "unity-libs/" + exact + ".zip").isFile()
                    || exists(SERVER + exact + ".zip");
            if (!exactAvailable) {
                String fallback = findFallback(majorMinor, patch);
                if (fallback == null) {
                    Log.w(TAG, "No Unity base libraries available for " + exact);
                    return null;
                }
                source = SERVER + fallback + ".zip";
                Log.i(TAG, "No Unity base libraries for " + exact + ", using " + fallback);
            }

            if (!source.equals(current)) {
                writeText(config, text.substring(0, line.start(1)) + source + text.substring(line.end(1)));
            }
            if (source.equals(DEFAULT_SOURCE)) {
                //noinspection ResultOfMethodCallIgnored
                marker.delete();
            } else {
                writeText(marker, source);
            }
            return source.replace("{VERSION}", exact);
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve Unity base libraries for " + exact, e);
            return null;
        }
    }

    private static boolean exists(String url) throws IOException {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("HEAD");
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) return true;
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return false;
            throw new IOException("Unexpected response " + code + " for " + url);
        } finally {
            connection.disconnect();
        }
    }

    private static String findFallback(String majorMinor, int patch) throws IOException {
        HttpURLConnection connection = open(SERVER);
        String listing;
        try (InputStream in = connection.getInputStream()) {
            listing = new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }

        Matcher entry = Pattern.compile("href=\"\\./" + Pattern.quote(majorMinor) + "\\.(\\d+)\\.zip\"").matcher(listing);
        int best = -1;
        while (entry.find()) {
            int candidate = Integer.parseInt(entry.group(1));
            if (candidate < patch && candidate > best) best = candidate;
        }
        return best < 0 ? null : majorMinor + "." + best;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        return connection;
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
