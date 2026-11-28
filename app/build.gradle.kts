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
        versionCode = 50
        versionName = "0.9.5"
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
            // build/asrdeps (models/whisper-medium/…) — see below.
            assets.srcDir("build/asrdeps")
            // Milestone 5 translation engine + NLLB model, fetched
            // by fetchMtDeps into build/mtdeps (mt/engines/…).
            assets.srcDir("build/mtdeps")
        }
    }
    androidResources {
        // ML model files are stored uncompressed: quantized ONNX is
        // high-entropy (deflate gains almost nothing), and trying to
        // compress ~1.3 GB of models blew the asset worker's heap
        // in CI (0.6.0, build #43). Stored assets are also read
        // directly by the engines with no decompression step.
        noCompress += "onnx"
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
    implementation(libs.androidx.compose.material.icons.extended)
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
// Milestone 4 — build-time fetches (sherpa-onnx AAR + Whisper model).
//
// The project's source channel carries text only, so these binaries cannot
// be committed; and TD-002 forbids the shipped app from touching the
// network while explicitly allowing the builder side to use it (the same
// way FFmpegKit arrives from Maven in CI). Every artifact is verified by
// SHA-256 against pinned files (sherpa-onnx 1.12.40, the AAR the owner
// field-proved in his voice translator; the Whisper model is MEDIUM int8
// since 0.9.0, TD-015 — the owner's explicit decision reversing his 0.7.0
// choice of small, its hashes computed from the verified local copies).
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
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-encoder.int8.onnx",
                "models/whisper-medium/medium-encoder.int8.onnx",
                "1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e"
            ),
            Triple(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-decoder.int8.onnx",
                "models/whisper-medium/medium-decoder.int8.onnx",
                "595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747"
            ),
            Triple(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-tokens.txt",
                "models/whisper-medium/medium-tokens.txt",
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
            ),
            // 0.5.1: silero VAD for the speech gate (TD-008) — the
            // sherpa project's own release asset, hash-pinned like
            // everything else here. Since 0.9.0 it ships inside the
            // whisper-medium folder, where AsrEngine/VadSpans read it.
            Triple(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                "models/whisper-medium/silero_vad.onnx",
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

// Milestone 5 (TD-010): the offline translation engine —
// Transformers.js 2.17.2 + onnxruntime-web 1.14.0 binaries and
// the NLLB-200-distilled-600M quantized model, every file
// SHA-256-pinned to the exact artifacts field-proven in the
// owner's voice translator (TD-002 sanctions builder-side
// internet; the shipped app keeps no INTERNET permission).
val fetchMtDeps by tasks.registering {
    val depsRoot = layout.buildDirectory.dir("mtdeps").get().asFile
    outputs.dir(depsRoot)
    doLast {
        val tjs = "https://cdn.jsdelivr.net/npm/@xenova/transformers@2.17.2/dist"
        val ort = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.14.0/dist"
        val nllb = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main"
        val deps = listOf(
            Triple("$tjs/transformers.min.js", "mt/engines/transformers.min.js",
                "bcf7cf304e51f470ed59409622b9d6ffbad80dfcf5baf6a40c919e4b9c4ff812"),
            Triple("$tjs/ort-wasm.wasm", "mt/engines/ort-wasm.wasm",
                "bbdcb6b3c7d294577d806077630460be4e13ca35a345acb5e81188e9649fa74a"),
            Triple("$tjs/ort-wasm-simd.wasm", "mt/engines/ort-wasm-simd.wasm",
                "9bd07bababc65f53d061f457233eeae501be7ceb8a2adb9eef52d87fe776d865"),
            Triple("$tjs/ort-wasm-threaded.wasm", "mt/engines/ort-wasm-threaded.wasm",
                "2ee2f715093aaacc0344bfb1610313eb49a3474e8afde5e4635f30ddfc7615c8"),
            Triple("$tjs/ort-wasm-simd-threaded.wasm", "mt/engines/ort-wasm-simd-threaded.wasm",
                "ac23f2f3cbd519a65a0796f7c79eb34ead4c1f6f31eb06e14ed8a9579d697ef6"),
            Triple("$ort/ort-wasm-threaded.worker.js", "mt/engines/ort-wasm-threaded.worker.js",
                "0cf0d14ffa4dcbece952a66ee317ff656915784aa5cb6b86e0c0beb2d1d8a5e9"),
            Triple("$nllb/config.json", "mt/engines/models/nllb-200-distilled-600M/config.json",
                "52f035acb54ac80e5ef7fe78d6967f8ddf8e8799f078d1a92a2c8168d8ff4a20"),
            Triple("$nllb/generation_config.json", "mt/engines/models/nllb-200-distilled-600M/generation_config.json",
                "1ce846259ae04c572ae1863e358534bbc376ff3d3f100b1494feda6200b51de8"),
            Triple("$nllb/tokenizer.json", "mt/engines/models/nllb-200-distilled-600M/tokenizer.json",
                "8ac789ad7dabea44d41537822d48c516ba358374c51813e2cba78c006e150c94"),
            Triple("$nllb/tokenizer_config.json", "mt/engines/models/nllb-200-distilled-600M/tokenizer_config.json",
                "ddf411c9f790d081e72de76bb8e8b714d74e160e61bc10c9bd8f56022dcd7fd7"),
            Triple("$nllb/special_tokens_map.json", "mt/engines/models/nllb-200-distilled-600M/special_tokens_map.json",
                "992bd4ed610d644d6823081937bcc91bb8878dd556cea4ae5327f2480361330e"),
            Triple("$nllb/onnx/encoder_model_quantized.onnx", "mt/engines/models/nllb-200-distilled-600M/onnx/encoder_model_quantized.onnx",
                "5cde664eacba07a62f198857ec6c06e09572b1ebb77c8137f1fa99ac604a3a28"),
            Triple("$nllb/onnx/decoder_model_merged_quantized.onnx", "mt/engines/models/nllb-200-distilled-600M/onnx/decoder_model_merged_quantized.onnx",
                "dd66608c2a4194e78f95548fa0e64f24302303698c5b09fa8e1f9e16ec00676b")
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

tasks.named("preBuild") { dependsOn(fetchMtDeps) }
tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }
    .configureEach { dependsOn(fetchMtDeps) }
