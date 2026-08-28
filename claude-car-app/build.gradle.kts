// Top-level build file. Plugin versions are declared here with `apply false`
// and applied in the module build files. Versions mirror ../launcher — both
// projects build with the same JDK17 + Gradle 8.9 toolchain.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
