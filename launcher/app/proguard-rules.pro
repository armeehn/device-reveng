# =====================================================================================
# Car Launcher — R8/ProGuard keep rules (v1.0 release shrink)
#
# Release builds enable minify + resource shrinking (app/build.gradle.kts). These rules keep
# everything that R8 cannot see through: the reconstructed vendor AIDL Binder stubs, the
# NotificationListenerServices bound by the framework by name, and the libsu classes probed
# reflectively. Compose + Kotlin metadata keeps are belt-and-braces on top of the AGP-bundled
# proguard-android-optimize.txt rules.
# =====================================================================================

# ---- Vendor AIDL stubs / Parcelables (com.szchoiceway.*) ----------------------------
# Binder transaction plumbing and Parcelable CREATORs are matched by exact name across the
# process boundary; renaming or stripping them breaks the IPC to the vendor EventCenter.
-keep class com.szchoiceway.eventcenter.** { *; }
-keep class com.szchoiceway.canbus.** { *; }
-keep interface com.szchoiceway.** { *; }

# Standard AIDL Stub/Proxy + Parcelable CREATOR safety net.
-keepclassmembers class * implements android.os.IInterface { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---- NotificationListenerServices ---------------------------------------------------
# Instantiated and bound by the framework from the manifest name — must not be renamed/removed.
-keep class com.reveng.carlauncher.media.MediaListenerService { *; }
-keep class com.reveng.carlauncher.nav.NavListenerService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

# ---- libsu / RootShell (reflection) -------------------------------------------------
# RootShell probes `Class.forName("com.topjohnwu.superuser.Shell")` and invokes it reflectively.
# libsu is an optional/compileOnly-style dependency; keep it (and don't warn) if present.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# ---- Jetpack Compose ----------------------------------------------------------------
# AGP ships Compose rules, but keep runtime + @Composable metadata explicitly for safety.
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---- Kotlin metadata / coroutines ---------------------------------------------------
# Preserve @Metadata so reflection and Kotlin intrinsics keep working post-shrink.
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisible*Annotations
-dontwarn kotlin.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- App enums (persisted by .name(), e.g. DayNightMode) -----------------------------
-keepclassmembers enum com.reveng.carlauncher.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
