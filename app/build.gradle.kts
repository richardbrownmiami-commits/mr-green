plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "im.mrgreen"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "armeabi-v7a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=TRUE", "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF", "-DGGML_LLAMAFILE=OFF")
                cppFlags += listOf("-std=c++17")
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { abortOnError = true }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.rivescript:rivescript-core:0.11.0")
    // No-op logging prevents the interpreter from logging private trigger/reply text.
    implementation("org.slf4j:slf4j-nop:1.7.26")
    // Bundle SQLite rather than assuming the MIUI system library has FTS5.
    implementation("com.github.requery:sqlite-android:3.49.0")
}
