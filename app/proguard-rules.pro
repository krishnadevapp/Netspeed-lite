# ==============================================================================
# NetSpeed Lite - Essential Keep Rules
# ==============================================================================

# 1. Keep the Service and Receiver so the system can find them
# This prevents the app from crashing when starting the background monitor
-keep class com.krishna.netspeedlite.SpeedService { *; }
-keep class com.krishna.netspeedlite.BootReceiver { *; }

# 2. Keep ALL Activities (prevents navigation/intent crashes)
-keep class com.krishna.netspeedlite.MainActivity { *; }
-keep class com.krishna.netspeedlite.SettingsActivity { *; }
-keep class com.krishna.netspeedlite.TermsPrivacyActivity { *; }

# 3. Keep WorkManager Worker (instantiated via reflection by WorkManager)
# CRITICAL: Without this, service won't restart after device reboot
-keep class com.krishna.netspeedlite.SpeedServiceWorker { *; }

# 4. Keep Notification and Icon classes
# Required because the speed icon is generated dynamically at runtime
-keep class androidx.core.app.NotificationCompat** { *; }
-keep class androidx.core.graphics.drawable.IconCompat** { *; }

# 5. Keep Constants (used via string keys in SharedPreferences)
-keep class com.krishna.netspeedlite.Constants { *; }

# 6. Keep UsageAdapter and ViewHolder (RecyclerView may use reflection)
-keep class com.krishna.netspeedlite.UsageAdapter { *; }
-keep class com.krishna.netspeedlite.UsageAdapter$UsageViewHolder { *; }

# 7. Keep DailyUsage data class fields (prevents obfuscation issues)
-keepclassmembers class com.krishna.netspeedlite.DailyUsage { *; }

# 8. Keep NetworkUsageHelper (static utility methods)
-keep class com.krishna.netspeedlite.NetworkUsageHelper { *; }

# 9. Preserve debugging information for Play Store crash reports
-keepattributes SourceFile, LineNumberTable

# 10. Prevent shrinking of the special property required for Android 14+
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 11. Optimization: Allow aggressive shrinking but keep these naming patterns
-dontwarn java.nio.file.**
-dontnote okhttp3.**

# ==============================================================================
# PRODUCTION OPTIMIZATION - Reduces False Positive Virus Detections
# ==============================================================================

# 12. Strip ALL Log calls from release builds (important for security scanners)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}