import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hilalsameer.videoaistudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hilalsameer.videoaistudio"
        minSdk = 26
        targetSdk = 34
        versionCode = 31
        versionName = "0.5.4"
        ndk {
            // FFmpegKit ships four ABIs; his devices are ARM — keep the
            // APK lean and skip x86/x86_64 (TD-001).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
    sourceSets {
        getByName("main") {
            // Milestone 4 model files, fetched by fetchAsrDeps into
            // build/asrdeps (models/whisper-small/…) — see below.
            assets.srcDir("build/asrdeps")
        }
    }
    packaging {
        jniLibs {
            // FFmpegKit and ONNX Runtime both bundle libc++_shared.so.
            pickFirsts += listOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                // Milestone 4: sherpa-onnx and ONNX Runtime each bundle
                // a libonnxruntime.so of the same 1.24 generation; one
                // copy serves both (GTCRN via the Java API, sherpa via
                // its JNI bridge).
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.ffmpeg.kit.full.gpl)
    implementation(libs.onnxruntime.android)
    // Milestone 4: sherpa-onnx AAR, fetched + SHA-256 verified by
    // fetchAsrDeps (the repo carries text only; TD-002 allows the
    // builder side to use the internet, never the shipped app).
    implementation(files("build/asrdeps/sherpa-onnx-1.12.40.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Milestone 4 — build-time fetches (sherpa-onnx AAR + Whisper-small model).
//
// The project's source channel carries text only, so these binaries cannot
// be committed; and TD-002 forbids the shipped app from touching the
// network while explicitly allowing the builder side to use it (the same
// way FFmpegKit arrives from Maven in CI). Every artifact is verified by
// SHA-256 against the exact files the owner field-proved in his voice
// translator (sherpa-onnx 1.12.40 + whisper-small int8, its 1.13 ear).
// A mismatch fails the build loudly — a corrupt model never ships.
// ---------------------------------------------------------------------------

fun sha256Hex(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { inp ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchAsrDeps by tasks.registering {
    val depsRoot = layout.buildDirectory.dir("asrdeps").get().asFile
    outputs.dir(depsRoot)
    doLast {
        val deps = listOf(
            Triple(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.40/sherpa-onnx-1.12.40.aar",
                "sherpa-onnx-1.12.40.aar",
                "a7699d6a2e638a38791b2459cb0099c7577e6e5fca6b8ae916742c872a618b82"
            ),
            Triple(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
                "models/whisper-small/small-encoder.int8.onnx",
                "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"
            ),
            Triple(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
                "models/whisper-small/small-decoder.int8.onnx",
                "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"
            ),
            Triple(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
                "models/whisper-small/small-tokens.txt",
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
            ),
            // 0.5.1: silero VAD for the speech gate (TD-008) — the
            // sherpa project's own release asset, hash-pinned like
            // everything else here.
            Triple(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                "models/whisper-small/silero_vad.onnx",
                "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
            )
        )
        for ((url, rel, sha) in deps) {
            val out = File(depsRoot, rel)
            if (out.exists() && sha256Hex(out) == sha) continue
            out.parentFile.mkdirs()
            val tmp = File(out.parentFile, out.name + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "VideoAIStudio-CI")
            conn.connectTimeout = 60_000
            conn.readTimeout = 600_000
            conn.inputStream.use { inp -> tmp.outputStream().use { o -> inp.copyTo(o, 1 shl 16) } }
            val got = sha256Hex(tmp)
            check(got == sha) { "SHA-256 mismatch for $rel: got $got" }
            if (out.exists()) out.delete()
            check(tmp.renameTo(out)) { "cannot move $tmp into place" }
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchAsrDeps) }
tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }
    .configureEach { dependsOn(fetchAsrDeps) }
