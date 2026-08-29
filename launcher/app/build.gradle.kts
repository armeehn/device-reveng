plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 Compose compiler plugin (see root build.gradle.kts).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.reveng.carlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reveng.carlauncher"
        minSdk = 33
        targetSdk = 33
        versionCode = 67
        // 1.0.0 is reserved for the polished public release. versionCode keeps climbing normally.
        versionName = "0.4.3.7"

        // Single head-unit target: arm64 landscape @240dpi, 1920x720.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // Also package x86_64 native libs (androidx graphics-path + datastore ship .so) so a
            // debug build installs on a KVM-accelerated x86_64 emulator for local prototyping.
            // Merges with defaultConfig's arm64-v8a, so debug still installs on the head unit too;
            // release stays arm64-only (unchanged shipping artifact).
            ndk { abiFilters += "x86_64" }
        }
        release {
            // v1.0: shrink + obfuscate for a much smaller side-load APK. The KEEP rules that
            // make this safe (AIDL stubs, NotificationListenerServices, reflective libsu,
            // Compose, Kotlin metadata) live in proguard-rules.pro. If minify ever needs to be
            // disabled to unblock a build, flip both flags to false — the rules file stays.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No release keystore for this side-loaded head-unit build: sign release with the
            // debug key so `assembleRelease` produces an installable APK out of the box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // v1.0: `assembleRelease` runs lint-vital by default, which drags in extra resolution we
    // don't need for a side-loaded head-unit APK (and can't fetch on an offline builder).
    // Disable it so a release assemble is self-contained; run `./gradlew lint` explicitly if wanted.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":carlib"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Compose BOM keeps all Compose artifacts on compatible versions.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DataStore Preferences — app-local persistence (theme system v0.5 + drawer favorites/order v0.4).
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // v2.9 ProfileInstaller — writes src/main/baseline-prof.txt (which AGP compiles into the APK)
    // into the app's ART profile on first run, so the cold-start path is AOT-compiled from the
    // second launch. Required here because the unit side-loads: the Play Store install path that
    // normally applies a baseline profile never runs.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM unit tests for the pure logic (preset codec, frequency formatting, theme table).
    // See carlib/build.gradle.kts for why there is no Robolectric.
    testImplementation("junit:junit:4.13.2")
}
