plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reveng.carlauncher.carlib"
    compileSdk = 34

    defaultConfig {
        minSdk = 33
        // NOTE: targetSdk is set on the application module; libraries inherit it.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // We hand-write the vendor AIDL under src/main/aidl (see CAR_API §3).
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")

    // Kotlin coroutines — CarEvents exposes car state as Flows.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // libsu — robust `su` shell for privileged SysVar writes and `am broadcast`.
    // Published on JitPack (repo declared in settings.gradle.kts).
    // If offline / the vendor blocks JitPack, RootShell also has a pure-ProcessBuilder
    // fallback path (see RootShell.kt) so this dependency is not strictly required.
    implementation("com.github.topjohnwu.libsu:core:6.0.0")

    // JVM unit tests for the pure decode/threshold logic (RadarState, ClimateState,
    // CarEvents.nextMotion). No Robolectric: everything under test is deliberately free of
    // framework calls, so a plain local JVM run needs no emulator and stays fast in CI.
    testImplementation("junit:junit:4.13.2")
}
