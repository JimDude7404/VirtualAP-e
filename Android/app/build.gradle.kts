import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "com.virtualap.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.virtualap.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val keystoreFile = file("../../virtualap.keystore")
        val keystorePassword = keystoreProperties["KEYSTORE_PASSWORD"] as String?
            ?: project.findProperty("KEYSTORE_PASSWORD") as String?
            ?: ""
        val keyAliasName = keystoreProperties["KEY_ALIAS"] as String?
            ?: project.findProperty("KEY_ALIAS") as String?
            ?: "virtualap"
        val actualKeyPassword = keystoreProperties["KEY_PASSWORD"] as String?
            ?: project.findProperty("KEY_PASSWORD") as String?
            ?: keystorePassword

        if (keystoreFile.exists() && keystorePassword.isNotEmpty()) {
            getByName("debug") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = actualKeyPassword
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = actualKeyPassword
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        } else {
            val fallbackKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
            println("Using fallback debug keystore: ${fallbackKeystore.absolutePath}")
            getByName("debug") {
                storeFile = fallbackKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            create("release") {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all"
        )
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ---------------------------------------------------------------------------
// prepareFonts: copies JetBrains Mono TTF files from sibling Droidspaces-OSS
// project. Skips gracefully if source directory does not exist.
// ---------------------------------------------------------------------------
tasks.register("prepareFonts") {
    doLast {
        val srcFontDir = file("../../Droidspaces-OSS/Android/app/src/main/res/font")
        if (!srcFontDir.exists()) {
            println("prepareFonts: Droidspaces-OSS font directory not found, skipping.")
            return@doLast
        }
        val destFontDir = file("src/main/res/font")
        destFontDir.mkdirs()
        srcFontDir.listFiles()?.filter { it.extension == "ttf" || it.extension == "xml" }?.forEach { src ->
            val dest = File(destFontDir, src.name)
            src.copyTo(dest, overwrite = true)
            println("prepareFonts: copied ${src.name}")
        }
    }
}

// ---------------------------------------------------------------------------
// prepareAssets: copies tools and rootfs tarball into assets directory.
// Skips gracefully if source files don't exist.
// ---------------------------------------------------------------------------
tasks.register("prepareAssets") {
    doLast {
        val toolsSrc = file("../backend")
        val outDir = file("../out")
        val assetTools = file("src/main/assets/backend")
        val assetBin = file("src/main/assets/bin")
        val assetRootfs = file("src/main/assets/rootfs")

        assetTools.mkdirs()
        assetBin.mkdirs()
        assetRootfs.mkdirs()

        // Copy vap.sh
        val vapSh = File(toolsSrc, "vap.sh")
        if (vapSh.exists()) {
            vapSh.copyTo(File(assetTools, "vap.sh"), overwrite = true)
            println("prepareAssets: copied vap.sh")
        } else {
            println("prepareAssets: vap.sh not found at ${vapSh.absolutePath}, skipping.")
        }

        // Copy start-ap
        val startAp = File(toolsSrc, "start-ap")
        if (startAp.exists()) {
            startAp.copyTo(File(assetTools, "start-ap"), overwrite = true)
            println("prepareAssets: copied start-ap")
        } else {
            println("prepareAssets: start-ap not found at ${startAp.absolutePath}, skipping.")
        }

        // Copy busybox
        val busybox = File(toolsSrc, "bin/busybox")
        if (busybox.exists()) {
            busybox.copyTo(File(assetBin, "busybox"), overwrite = true)
            println("prepareAssets: copied busybox")
        } else {
            println("prepareAssets: busybox not found at ${busybox.absolutePath}, skipping.")
        }

        // Copy latest rootfs tarball
        if (outDir.exists()) {
            val tarballs = outDir.listFiles()?.filter { it.name.endsWith(".tar.xz") }
                ?.sortedByDescending { it.lastModified() }
            val latest = tarballs?.firstOrNull()
            if (latest != null) {
                latest.copyTo(File(assetRootfs, latest.name), overwrite = true)
                println("prepareAssets: copied rootfs tarball ${latest.name}")
            } else {
                println("prepareAssets: no .tar.xz tarball found in ${outDir.absolutePath}, skipping.")
            }
        } else {
            println("prepareAssets: out/ directory not found at ${outDir.absolutePath}, skipping.")
        }
    }
}

tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("Build")) {
        dependsOn("prepareFonts")
        dependsOn("prepareAssets")
    }
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Root execution - libsu
    implementation("com.github.topjohnwu.libsu:core:5.2.1")
    implementation("com.github.topjohnwu.libsu:service:5.2.1")
    implementation("com.github.topjohnwu.libsu:io:5.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
