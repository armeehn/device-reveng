// Top-level build file. Plugin versions are declared here with `apply false`
// and applied in the module build files.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Kotlin 2.0 moved Compose compiler out of the Kotlin plugin into its own plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
