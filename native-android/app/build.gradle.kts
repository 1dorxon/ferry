import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Renders the real Compose screens to PNGs on the JVM (Robolectric), so the UI
    // can be checked without a phone: ./gradlew recordRoborazziDebug
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "com.dorxon.browserdrop"
    compileSdk = 36
    // Pinned to what native-android/docker/Dockerfile installs (AGP would
    // otherwise ask for its own default, 35.0.0).
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // The public, permanent ID (locked by Play on first upload). The code's
        // own package stays com.dorxon.browserdrop (`namespace` above): internal only.
        applicationId = "com.dorxon.ferry"
        // 29+: received files go to Downloads through MediaStore without any
        // storage permission.
        minSdk = 29
        targetSdk = 36
        versionCode = 18
        versionName = "2.7.0"
        // Signalling server. Override for local testing:
        //   ./gradlew assembleDebug -PbdServer=http://10.0.2.2:8899
        // WebRTC's native library is ~12 MB per CPU type. ARM covers phones (and
        // x86 Chromebooks run ARM apps); Play delivers only the one a device needs.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "SERVER", "\"${project.findProperty("bdServer") ?: "https://ferry.luitlabs.in"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"          // Robolectric is hungry; the host runs production services
    maxParallelForks = 1
}

dependencies {
    // Newest versions that build with AGP 8.13 / compileSdk 36 (the next ones need AGP 9.1 + SDK 37).
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    testImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
