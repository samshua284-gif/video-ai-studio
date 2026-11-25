# Milestone 0 — Repository & Environment Audit (2026-11-25)

## Environment (verified by execution)
- JDK: OpenJDK Temurin 17.0.21 ✓
- Android SDK: platform android-34 + build-tools 34.0.0 + cmdline-tools ✓ (no emulator, no system images, no NDK)
- Gradle: NOT installed — downloadable (services.gradle.org reachable, HTTP 200)
- Kotlin: NOT installed — via Gradle plugin (dl.google.com + Maven Central reachable, HTTP 200)
- Disk: 106 GB free ✓
- ffmpeg 8.1.2 on the VM — usable to generate/verify test media host-side ✓
- Runtime verification: no device/emulator here; field testing on Hilal's phone (established pattern for all his apps)

## Repository
- No repo named `video-ai-studio` / `Video-AI-Studio` under samshua284-gif (API 404, both casings).
- The connector cannot create repositories (403, established) — Hilal creates it, as with all his projects.
- First push when it exists: AGENTS.md (the master prompt, per Hilal's instruction) + this audit + plan docs.

## Risk register (honest, per prompt Rules 2 & 6)
1. **FFmpegKit retired (2025), binaries pulled from Maven** — the video engine needs a real technology decision (maintained fork / bundled ffmpeg binary / MediaCodec-only subset). To be evaluated and documented in TECHNOLOGY_DECISIONS.md.
2. **"AI 4K upscaling" with true detail reconstruction (Real-ESRGAN class) is not realistic on a mid-range phone** in reasonable time/RAM. Honest deliverable: high-quality scaling + denoise + sharpen, truthfully labelled (the prompt's own Feature D clause demands this distinction).
3. **ASR**: Whisper via sherpa-onnx is field-proven on Hilal's own phone (his voice translator, Arabic included) — real path exists.
4. **Noise removal**: FFmpeg spectral DSP (afftdn/anlmdn) is real and available; neural models (RNNoise/DeepFilterNet via ONNX) are a later evaluation.
5. **Offline MT**: NLLB-in-WebView is proven in his translator but heavy; native options (ML Kit Translate — on-device, Arabic, model downloads, Play Services) vs cloud APIs (keys/costs) — decision deferred to Milestone 5, does not block M1.
6. **Build cost**: first Gradle/AGP/Compose dependency fetch is hundreds of MB; builds on 1.5 shared CPUs will be slow. Milestones will take real time; no speed will be claimed that does not exist.

## Architecture decision — PENDING Hilal's choice
- Path 1 (recommended): Native Kotlin + Jetpack Compose + Gradle/AGP, exactly as the master prompt specifies. Toolchain installed here; Hilal field-tests APKs.
- Path 2: WebView hybrid (his previous 4 apps' stack). Faster to an APK, reuses proven JS ASR/MT — but has NO real video-processing engine, so the product's core (enhance/upscale/trim/compress) would be blocked or fake → violates Rule 1. Not recommended.

## Milestone 1 readiness
Once the path is chosen, M1 (skeleton, navigation, dashboard, AR/EN + RTL, import via SAF, preview, metadata via MediaMetadataRetriever) is fully implementable and build-verifiable here; runtime behavior verified by Hilal on his phone.
