# Release builds must never log anything. Strip android.util.Log calls entirely.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}

# kotlinx.serialization ships its own R8 rules; nothing extra is needed here.
