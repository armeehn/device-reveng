import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The car service (os/CARHAL.md "The car service"): runs as android.uid.system, which only an
// APK signed with the image's platform key may claim. That key is AOSP's public test key
// (platform-testkey/README.md), so debug and release both sign with it.
val platformKeyDir = file("platform-testkey")
val platformAlias = "platform"
// Guards a store rebuilt from public material on every configure; it protects nothing.
val platformStorePass = "android"

// PKCS#8 key + X.509 cert -> PKCS12 under build/, with the JDK alone: no openssl or keytool on
// the path, so forge and CI build it the same way.
fun platformKeystore(): File {
    val store = layout.buildDirectory.file("platform.p12").get().asFile
    store.parentFile.mkdirs()

    val keySpec = PKCS8EncodedKeySpec(platformKeyDir.resolve("platform.pk8").readBytes())
    val key = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    val cert = platformKeyDir.resolve("platform.x509.pem").inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it)
    }

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(platformAlias, key, platformStorePass.toCharArray(), arrayOf(cert))
    store.outputStream().use { ks.store(it, platformStorePass.toCharArray()) }
    return store
}

// versionCode from git as the launcher derives it (app/build.gradle.kts): the commit count at
// the merge-base with origin/main, so the service ships with the launcher release's number.
fun git(vararg args: String): String = providers.exec {
    workingDir = projectDir
    isIgnoreExitValue = true
    commandLine("git", *args)
}.standardOutput.asText.get().trim()

val versionAnchor = git("merge-base", "HEAD", "origin/main").ifEmpty { "HEAD" }
val gitVersionCode = git("rev-list", "--count", versionAnchor).toIntOrNull()
    ?: throw GradleException("Not a git checkout: the car service version is derived from git history.")

android {
    namespace = "com.ripostelabs.car"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ripostelabs.car"
        minSdk = 33
        targetSdk = 33
        versionCode = gitVersionCode
        versionName = "0.3"
    }

    signingConfigs {
        create("platform") {
            storeFile = platformKeystore()
            storePassword = platformStorePass
            keyAlias = platformAlias
            keyPassword = platformStorePass
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("platform")
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The binder tests construct the generated ICarService.Stub, whose base is android.os.Binder.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // McuOwner, the ICarService AIDL and the parcels live in carlib, shared with the launcher.
    implementation(project(":carlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
