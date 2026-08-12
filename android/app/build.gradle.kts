import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Play Store upload key — gitignored, machine-local. Absent on CI/other devs' machines, so
// release builds there stay unsigned (same as before) instead of failing the build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.sufficit.ai.mobiledevice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sufficit.ai.mobiledevice"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AppAuth generates its RedirectUriReceiverActivity manifest entry from this —
        // must match the client's RedirectUris in sufficit-identity exactly (see
        // docs/PLAN-202607091200-mobile-device-ai-provider.md "Modo B").
        manifestPlaceholders["appAuthRedirectScheme"] = "sufficitmobileaimodels"

        // The embedded llama-server binary (jniLibs) is only built for arm64-v8a — no point
        // packaging/shipping other ABI slices that could never run it.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    // PairingApi logs via android.util.Log — AGP's stub android.jar throws on any Android
    // framework call by default; this makes it return defaults (0/null/false) instead so
    // plain JVM unit tests don't need to mock every incidental framework call.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // libllamaserver.so is an actual executable (see LlamaServerManager.kt), not a JNI
    // library — it must be extracted to a real, executable-permitted file on disk
    // (applicationInfo.nativeLibraryDir) so ProcessBuilder can run it. AGP's modern default
    // (useLegacyPackaging = false) instead maps native libs directly out of the APK zip
    // without ever extracting them, which works fine for dlopen() but leaves nothing on
    // disk to exec.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.openid:appauth:0.11.1")
    implementation(files("libs/tsgo.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // AGP's stub android.jar makes org.json.JSONObject a no-op (isReturnDefaultValues just
    // avoids throwing, it doesn't parse) — pull in the real implementation for PairingApiTest.
    testImplementation("org.json:json:20231013")
}
