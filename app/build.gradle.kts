plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "im.mrgreen"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "im.mrgreen"
        minSdk = 26
        targetSdk = 30
        versionCode = 16
        versionName = "0.10.7-web-facts-preview"
        // Exposes device-local consent only; a personal build does not itself authorize reads.
        buildConfigField("boolean", "PERSONAL_SCREEN_READING", providers.gradleProperty("personalScreenReading").map { (it == "true").toString() }.getOrElse("false"))
        ndk { abiFilters += "armeabi-v7a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=TRUE", "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF", "-DGGML_LLAMAFILE=OFF")
                cppFlags += listOf("-std=c++17")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Owner release signing is deliberately not fabricated or committed.
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    lint {
        abortOnError = true
        // The owner explicitly requires targetSdk 30 and sideload-only distribution.
        // Keep other fatal release checks enabled; this is not a Play Store build.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.rivescript:rivescript-core:0.11.0")
    // No-op logging prevents the interpreter from logging private trigger/reply text.
    implementation("org.slf4j:slf4j-nop:1.7.26")
    // Bundle SQLite rather than assuming the MIUI system library has FTS5.
    implementation("com.github.requery:sqlite-android:3.49.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Deterministic code-owned self-manual; never generated from private logs or model text.
val generatedManual = layout.buildDirectory.dir("generated/self-manual-assets")
val generateSelfManual by tasks.registering {
    inputs.dir("src/main/java")
    outputs.dir(generatedManual)
    doLast {
        val root=generatedManual.get().asFile.resolve("self_manual").apply { mkdirs() }
        val pages=linkedMapOf(
            "architecture" to "Mr Green uses one serialized engine queue and one local GGUF model. Deterministic registered actions and validated local identity data precede saved replies and local generation. All model paths are local only; unavailable GGUF never falls back to cloud. Editable identity data is not GGUF weight training. Plugins use bounded flows and QuickJS. User messages interrupt idle maintenance; no AI runs for each event. No dedicated cloud maintenance route exists.",
            "privacy" to "Screen snapshots, notifications, call logs and private documents remain local. Screen answers are ephemeral and always local. Remote MCP accepts only explicitly reviewed user-typed public arguments. Credentials never enter logs, exports or packs.",
            "web-facts" to "Enable explicitly requested web access in Knowledge, then type ask web QUESTION or ask web https://page | QUESTION. Only the typed public question goes to search; no chat history or screen content. Source pages are temporary untrusted data, not instructions. A bounded local OpenNARS core handles only supported simple factual relations; otherwise replies quote relevant source text or report no answer. This path needs no GGUF or cloud AI. Web questions and answers are never saved to SQL chat, memory or learned replies. Clear the temporary answer manually; it also expires after five minutes or the next message.",
            "doctor-limits" to "Doctor changes are data only. No identity, permission, code, tool registry or risk changes. Typed proposals require whitelist checks, at most ten rows, incident improvement and unchanged golden dry runs, then explicit approval. Unsupported changes fail closed. Undo restores stored rows; snapshots are separate backups.",
            "miui" to "Android 11 and MIUI may suspend background work. Review autostart, battery restrictions and pop-up permissions manually. The app never grants its own special access. Clock setup opens external confirmation and is not proof an alarm exists. Phone performance and longevity acceptance remain device tests.",
            "known-fixes" to "Missing permission: open live setup checklist and grant only the needed access. No model: import a compatible ARMv7-supported GGUF and load it. Out of memory: unload and reduce context to 512 and two threads. App layout mismatch: stop recipe and review current UI. Native patches require an independently reviewed owner key, a signed ABI-compatible library and explicit approval; never install unvetted native code."
        )
        pages.forEach { (name,text) -> root.resolve("$name.md").writeText("# $name\n\n$text\n") }
        val toolSource=file("src/main/java/im/mrgreen/actions/ActionRuntime.kt").readLines().filter { it.contains("ToolSchema(\"") }
        toolSource.chunked(5).forEachIndexed { n,lines -> root.resolve("tools-$n.md").writeText("# Registered tool schemas\n\n"+lines.joinToString("\n")+"\n") }
        val schema=file("src/main/java/im/mrgreen/data/GreenStore.kt").readLines().filter { it.contains("CREATE TABLE ") }
        schema.chunked(3).forEachIndexed { n,lines -> root.resolve("database-$n.md").writeText("# Database tables\n\n"+lines.joinToString("\n")+"\n") }
    }
}
android.sourceSets.getByName("main").assets.srcDir(generatedManual)
tasks.named("preBuild").configure { dependsOn(generateSelfManual) }
