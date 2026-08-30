import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ripostelabs.claudecar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ripostelabs.claudecar"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

        // Single head-unit target: arm64 landscape @240dpi, 1920x720.
        ndk { abiFilters += "arm64-v8a" }

        // Default backend URL is a tailnet address, which must not be committed
        // (PII hook) — baked in from gitignored local.properties instead:
        //   claudecar.server=http://<x-tailscale-ip>:8799
        // Without it the app starts unconfigured and asks for the URL in-app.
        val props = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use { props.load(it) }
        buildConfigField(
            "String", "DEFAULT_SERVER",
            "\"${props.getProperty("claudecar.server", "")}\""
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // x86_64 too so a debug build installs on the rav4_headunit emulator.
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Side-loaded head-unit build: sign with the debug key so
            // `assembleRelease` produces an installable APK out of the box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
