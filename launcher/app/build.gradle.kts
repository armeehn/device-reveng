plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 Compose compiler plugin (see root build.gradle.kts).
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versions are derived from git, never bumped by hand. Hand-claimed versionCodes
// made every squash-merge conflict every sibling PR on exactly those lines.
//
//   anchor      = merge-base with origin/main (HEAD if that ref is missing), so a
//                 feature-branch build never out-numbers the squash-merge that
//                 follows it — Android refuses to install a lower versionCode.
//   versionCode = commit count at the anchor. Each squash-merge adds exactly one
//                 commit to main, so it is monotonic by construction.
//   versionName = "<base>.<versionCode>".
//
// CI reads the result from the APK's output-metadata.json (launcher-ci.yml release
// job); nothing parses this file for a version any more.

// 1.0.0 is reserved for the polished public release; change the base only at a
// deliberate milestone, in its own commit.
val displayVersionBase = "0.4"

fun git(vararg args: String): String = providers.exec {
    workingDir = projectDir
    isIgnoreExitValue = true
    commandLine("git", *args)
}.standardOutput.asText.get().trim()

// A shallow clone would miscount silently (rev-list only sees fetched commits).
if (git("rev-parse", "--is-shallow-repository") == "true") {
    throw GradleException(
        "Shallow git clone: the derived versionCode would be wrong. " +
            "Fetch full history (actions/checkout with fetch-depth: 0)."
    )
}

val versionAnchor = git("merge-base", "HEAD", "origin/main").ifEmpty { "HEAD" }
val gitVersionCode = git("rev-list", "--count", versionAnchor).toIntOrNull()
    ?: throw GradleException("Not a git checkout — the launcher version is derived from git history.")

android {
    namespace = "com.reveng.carlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reveng.carlauncher"
        minSdk = 33
        targetSdk = 33
        versionCode = gitVersionCode
        versionName = "$displayVersionBase.$gitVersionCode"

        // Single head-unit target: arm64 landscape @240dpi, 1920x720.
        ndk { abiFilters += "arm64-v8a" }

        // Compose UI tests under app/src/androidTest run on an emulator in CI
        // (`connectedDebugAndroidTest`); they never run on the head unit.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            //
            // HAZARD, unfixed: a debug keystore is generated per machine and per CI runner, so
            // two builds of the same commit can carry different signatures. Installing one over
            // the other fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and on a device where this
            // app is HOME that leaves the car with no launcher until it is uninstalled by hand.
            // The fix is a real keystore held as repository secrets — it cannot be committed here,
            // it needs owner action. See the release job in .github/workflows/launcher-ci.yml.
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

    // JVM unit tests for the pure logic (preset codec, frequency formatting, theme table,
    // the store codecs). See carlib/build.gradle.kts for why there is no Robolectric.
    testImplementation("junit:junit:4.13.2")

    // The real org.json, for the theme and driver-profile codecs. `org.json` ships in the
    // platform, so the app declares no JSON dependency — but a local unit test compiles against
    // the stub android.jar, where every JSONObject call throws "not mocked". This is the
    // reference implementation of the same API, test-scope only: nothing reaches the APK. It is
    // not byte-identical to Android's copy (Android's getString coerces a number to its text,
    // this one throws), so the codec tests stay off type coercion.
    testImplementation("org.json:json:20240303")

    // Compose UI tests (app/src/androidTest): they pin the four status indicators by test tag.
    // Semantics, not pixels — a colour or icon change must not turn this suite red, only an
    // indicator going missing. Versions come from the same Compose BOM as the app.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")

    // createComposeRule() hosts the composable in a stub activity that only exists in this
    // manifest; without it the test APK has no activity to launch and every test errors out.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
