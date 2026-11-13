import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The signing key lives outside the repo (~/.config/enlace/signing). Every APK
// must carry the same signature or Android refuses to install it as an update.
// Without the key the build still works, signed with the throwaway debug key.
val signingDir = File(System.getProperty("user.home"), ".config/enlace/signing")
val signingProps = Properties().apply {
    val file = File(signingDir, "signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.enlace.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.enlace.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("vcode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("vname") as String?) ?: "dev"
    }

    signingConfigs {
        if (signingProps.containsKey("storePassword")) {
            create("app") {
                storeFile = File(signingDir, "enlace.jks")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val key = signingConfigs.findByName("app")
        getByName("debug") {
            if (key != null) signingConfig = key
        }
        release {
            isMinifyEnabled = false
            if (key != null) signingConfig = key
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // QR scanner for pairing: a ready-made camera screen on top of ZXing.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // JVM tests against contract/fixtures.json, shared with the PC. Real org.json:
    // the one in android.jar is a stub that throws "not mocked" in unit tests.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Draws the real screens to PNG without a device (tools/screens.sh).
    testImplementation("org.robolectric:robolectric:4.13")
}

// tools/interop.sh starts the real PC service and passes its pairing link here;
// without it LinkInteropTest skips itself.
tasks.withType<Test> {
    systemProperty("enlace.interop", System.getenv("ENLACE_INTEROP") ?: "")
    systemProperty("enlace.interop.mic", System.getenv("ENLACE_INTEROP_MIC") ?: "")
    systemProperty("enlace.screens", System.getenv("ENLACE_SCREENS") ?: "")
    outputs.upToDateWhen {
        System.getenv("ENLACE_INTEROP").isNullOrEmpty() && System.getenv("ENLACE_SCREENS").isNullOrEmpty()
    }
}
