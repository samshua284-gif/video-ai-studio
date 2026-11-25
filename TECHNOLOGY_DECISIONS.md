# Technology Decisions

## TD-002 — Offline-first is a binding constraint (owner's rule, 2026-11-25)

The owner's standing rule for all his projects: they must suit the
poor — meaning **no feature may require an internet connection at use
time**, in any milestone. Concretely for this app:

- The manifest declares **no INTERNET permission**; the app cannot
  connect even accidentally. No analytics, no ads, no cloud SDKs.
- Media engine, audio enhancement (DSP), subtitles and video
  enhancement are all local libraries/filters — offline by design.
- ASR (Milestone 4) uses on-device Whisper via sherpa-onnx, the same
  approach the owner already field-proved in his voice translator.
- Translation (Milestone 5) must use bundled offline models
  (OPUS-MT / NLLB class, ONNX) — cloud translation APIs are forbidden
  by this rule. Because speech/translation models are large, they may
  ship as one-time downloadable model packs: downloaded once, then
  offline forever. Offline translation quality is below cloud quality;
  that trade-off is accepted and must be disclosed in the UI.
- Development (GitHub CI) uses the internet on the builder's side
  only; the shipped app never does.

Any future proposal that needs a connection at use time is rejected
by this decision unless the owner himself amends it.

## TD-003 — Noise removal & audio enhancement engine (2026-11-25)

**Verified present in the shipped engine binary** (strings of the
FFmpegKit 6.0-2 arm64 `libavfilter.so`, checked 2026-11-25):
`afftdn`, `anlmdn`, `arnndn`, `highpass`, `loudnorm`, `equalizer`,
`acompressor` — all available offline, no new dependency.

**Decision:** noise removal is built on FFmpeg's **afftdn** (adaptive
FFT spectral denoiser with noise-floor tracking), preceded by a
70 Hz high-pass. Three user modes vary reduction strength and floor:
Light (nr=8, nf=−30), Balanced (nr=14, nf=−22, tracking on),
Strong (nr=24, nf=−14, tracking on). Audio enhancement is a separate
chain: high-pass 60 Hz + presence equalizer (+3 dB @ 3 kHz) +
loudnorm to −16 LUFS. The video stream is copied untouched; only the
audio is processed. The UI labels this honestly as spectral DSP
noise reduction — not a neural network.

**Objective host-side measurement** (VM FFmpeg, identical chains,
pink noise at −20.3 dB mean): Light −4.6 dB, Balanced −5.0 dB,
Strong −5.2 dB broadband reduction; a 196 Hz tone mixed in noise
survived processing (mix level fell ≈3 dB, i.e. mostly the noise
component). Genuine but moderate — exactly what spectral DSP
delivers on steady noise; speech-gap behaviour is the device field
test's to judge.

**Neural path (arnndn + RNNoise):** the filter is present in the
binary, but it needs an external `.rnnn` model file, and no model
with clean, verifiable provenance/licensing for redistribution was
secured at decision time. Per Command 3's own fallback clause, the
genuine DSP solution ships now and the neural upgrade stays an open,
documented path — it must not be faked or bundled from a murky
source.

## TD-001 — Media engine for Milestone 2 (2026-11-25)

**Decision:** FFmpegKit `com.arthenica:ffmpeg-kit-full-gpl:6.0-2`
from Maven Central, restricted to `arm64-v8a` + `armeabi-v7a` ABIs.
Verified published 2026-11-25 via the Maven Central search index
(.aar/.pom/.module all listed) and Google's mirror of Central
(POM served, HTTP 200; the 59.6 MB AAR itself downloaded intact).
**Correction (same day, proven by the CI log):** the live Central
CDN (repo.maven.apache.org) answers 404 for this artifact even from
GitHub's own runners — the first Milestone 2 CI build failed at
dependency resolution for exactly this reason. It is not a sandbox
proxy quirk. The fix is in `settings.gradle.kts`: Google's official
GCS mirror of Maven Central is declared as an additional repository,
and resolution succeeds through it.

**Why an FFmpeg engine is required at all:** Milestone 2 must export
real MP3 files. Android's platform codecs (MediaCodec) include no MP3
encoder, and Media3 Transformer — the maintained Google editor
library — therefore cannot export MP3 either. WAV export, trimming and
H.264 compression could be done with platform APIs, but splitting the
engine per feature would double the failure surface; one proven
engine (FFmpeg 6.0 with libmp3lame and libx264) covers all four M2
features with exact, testable command lines.

**Disclosed limitation:** FFmpegKit was retired upstream in 2025. The
6.0.LTS binaries remain published on Maven Central and are a complete,
real FFmpeg — this is a maintenance freeze, not a capability gap. If a
future milestone hits a requirement the frozen engine cannot meet,
the migration path is Media3 Transformer for video operations plus a
separately maintained LAME build for MP3, and this file must be
updated before that work starts.

**Licensing:** the full-gpl package is GPL (libx264). The app is
open source (public repository), which keeps this lawful; the README
carries the attribution.

**Alternatives rejected:** FFmpegKit `min-gpl` (uncertain external-lib
coverage for lame/x264 across ABIs), bundling a raw ffmpeg binary
(process/ABI management burden, no maintained source), pure-Java MP3
encoders (unproven quality/performance for feature-length audio).
