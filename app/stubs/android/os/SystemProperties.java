package android.os;

/** Compile-time only stub. Runtime uses framework android.os.SystemProperties. */
public class SystemProperties {
    public static String get(String key) {
        return "";
    }

    public static String get(String key, String def) {
        return def;
    }

    public static void set(String key, String val) {
    }

    public static int getInt(String key, int def) {
        return def;
    }

    public static long getLong(String key, long def) {
        return def;
    }

    public static boolean getBoolean(String key, boolean def) {
        return def;
    }
}
