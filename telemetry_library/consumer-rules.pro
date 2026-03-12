# Edge Telemetry Android SDK - ProGuard/R8 Rules

# ============================================================================
# Security: API Key Protection
# ============================================================================
# Obfuscate string constants to protect API keys in release builds
# Note: This provides basic obfuscation but developers should still use
# BuildConfig or secure storage for API keys
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
}

# ============================================================================
# Keep SDK Public API
# ============================================================================
# Keep TelemetryManager public API
-keep public class com.androidtel.telemetry_library.core.TelemetryManager {
    public static *** initialize(...);
    public static *** getInstance();
    public *** trackEvent(...);
    public *** trackMetric(...);
    public *** trackScreen(...);
    public *** setUserProfile(...);
    public *** clearUserProfile(...);
    public *** recordNetworkRequest(...);
    public static *** createNetworkInterceptor();
}

# Keep TelemetryInterceptor for OkHttp integration
-keep public class com.androidtel.telemetry_library.core.TelemetryInterceptor {
    public <init>(...);
}

# Keep EdgeTelemetryInterceptor for OkHttp integration
-keep public class com.androidtel.telemetry_library.core.http.EdgeTelemetryInterceptor {
    public <init>(...);
}

# Keep CrashReporter public methods
-keep public class com.androidtel.telemetry_library.core.crash.CrashReporter {
    public *** trackError(...);
    public *** testCrashReporting(...);
}

# ============================================================================
# Keep Model Classes (for Gson serialization)
# ============================================================================
-keep class com.androidtel.telemetry_library.core.models.** { *; }
-keep class com.androidtel.telemetry_library.core.payload.** { *; }

# ============================================================================
# Gson Rules
# ============================================================================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ============================================================================
# OkHttp Rules
# ============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ============================================================================
# WorkManager Rules
# ============================================================================
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(...);
}
-keep class com.androidtel.telemetry_library.core.retry.CrashRetryWorker {
    public <init>(...);
}

# ============================================================================
# Kotlin Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ============================================================================
# Room Database
# ============================================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ============================================================================
# Keep line numbers for crash reports
# ============================================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
