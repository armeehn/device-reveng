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

# McuStateExport names JSON fields after the data-class fields through reflection, and Helm
# reads those names over the wire. Keep them: on the first release build R8 sent {"a":false,...}.
-keepclassmembernames class com.ripostelabs.carlauncher.carlib.McuOwnerProtocol$** { <fields>; }
-keepclassmembernames class com.ripostelabs.carlauncher.carlib.CanSignal$** { <fields>; }
-keepclassmembernames class com.ripostelabs.carlauncher.carlib.RawCanSignal$** { <fields>; }
-keepnames class com.ripostelabs.carlauncher.carlib.McuOwnerProtocol$RadioEvent$**
-keepnames class com.ripostelabs.carlauncher.carlib.CanSignal$**
