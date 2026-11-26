# Milestone 4 — Speech Recognition (0.5.0, versionCode 27)

Status: **IMPLEMENTED — host-verified; phone verification is the owner's**
(the standing rule: what only his device can prove is said before install).

## What was built

- **Engine (TD-002):** Whisper-small via sherpa-onnx 1.12.40 — the exact
  AAR, model files (int8 encoder/decoder + tokens) and recognizer
  configuration the owner field-proved in his voice translator (its
  1.13 ear: `OfflineRecognizer(null, config)`, greedy search, 4 threads,
  language set per run, `setConfig` for language changes). Fully
  offline; the manifest still declares no INTERNET permission.
- **Delivery of binaries:** the repo carries text only, so
  `fetchAsrDeps` (app/build.gradle.kts) downloads the AAR (k2-fsa
  GitHub release) and the model (Hugging Face, csukuangfj) at build
  time in CI and verifies each file's SHA-256 against the
  field-proven artifacts. A mismatch fails the build. The app then
  materializes the model from assets into internal storage with
  exact-size checks before first use.
- **Pipeline:** pick video (gallery, videos only) → FFmpeg extracts a
  16 kHz mono WAV (`MediaCommands.extractWav16k`, the M3-proven path)
  → decode in consecutive 25 s windows (sherpa's Whisper ceiling is
  ~29.5 s/stream; 25 s is the translator's proven chunk) → each
  window becomes a segment stamped with its real position.
  Silent windows (RMS < 0.004) are skipped — Whisper invents text on
  silence, and an honest gap beats a fabricated sentence.
- **Languages:** auto-detect, Arabic, English, Hindi, Urdu
  (manual selection + automatic detection, Feature E).
- **Screen:** `TranscribeToolScreen` (dashboard tool idx 4) mirroring
  the M3 screen's job machine: preparing / extracting (real FFmpeg
  progress) / transcribing (part n of N) / done / failed with the
  error excerpt / cancelled. Results: segment count + speech end,
  an editable transcript, **TXT export** (the edited text) and
  **SRT export** (recognized segments, real timestamps) via the
  system document picker.
- **Native-lib coexistence:** sherpa and ONNX Runtime both bundle a
  `libonnxruntime.so` of the same 1.24 generation; packaging
  pickFirsts let one copy serve GTCRN (Java API) and sherpa (JNI).

## Verified here (Gate A–D, host side)

- `AsrEngine.kt` + `SubtitleFormat.kt` + `WaveIo.kt` compiled with
  the in-process Kotlin compiler against android.jar and the real
  sherpa-onnx 1.12.40 classes.jar — clean (one real defect found and
  fixed by this check: sherpa's only recognizer constructor takes an
  AssetManager argument, null for file-path models).
- `SubtitleFormat` executed: 8/8 checks pass (SRT timestamps incl.
  hour rollover and negative clamp, SRT block numbering/format with
  Arabic text, TXT join/trim, empty input). The same assertions ship
  as `SubtitleFormatTest` for CI's `testDebugUnitTest`.
- CI (GitHub Actions) builds `assembleDebug` + `testDebugUnitTest` —
  see the run for 0.5.0.

## NOT verified here (needs the owner's phone)

- Transcription speed on long videos and thermal behaviour.
- Arabic/English recognition quality on real video audio (the model
  and configuration are the translator's field-proven pair, but on
  different material).
- The libonnxruntime pickFirst coexistence at runtime (GTCRN screen
  + transcription in one session).

## Known limitations

- Segments are 25 s windows, not sentences; sentence-level
  segmentation and the subtitle editor belong to Milestone 6.
- Editing the transcript affects the TXT export; the SRT always
  carries the recognized segments (stated on the screen itself).
- APK grows by ~360 MB of model + ~35 MB of sherpa native libraries.
