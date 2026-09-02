# Keep the vendor AIDL stubs and Parcelable names intact for the bound EventService.
-keep class com.szchoiceway.eventcenter.** { *; }
-keep class com.szchoiceway.canbus.** { *; }

# v2.9: RootBroadcastHelper is launched by `app_process` in a separate root process, which
# resolves the class by its fully-qualified name and then looks up a static main(String[]).
# R8 sees no caller for either, so without this it renames the class and drops the method,
# and the protected-broadcast capture dies on a ClassNotFoundException nobody ever sees.
-keep class com.ripostelabs.carlauncher.carlib.RootBroadcastHelper {
    public static void main(java.lang.String[]);
}
