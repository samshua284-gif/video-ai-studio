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
Light (nr=8, nf=−30), Balanced (nr=14, nf=−24, tracking on),
Strong (nr=24, nf=−21, tracking on). (First field build used nf=−14
for Strong; FFmpeg 6.0's afftdn accepts nf only in [−80, −20], the
filter refused it on the owner's device, and the values were
corrected the same day — the log he sent is the evidence.) Audio enhancement is a separate
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

**Amendment (same day) — provenance secured, neural mode shipped:**
the original Xiph RNNoise v0.1 model, serialized for arnndn as
`std.rnnn`, was obtained from richardpl/arnndn-models at pinned
commit `0fda24c46d78f0207d820bb970fbe85c1971b39c` and verified
locally before bundling: size 302,903 bytes, SHA-256
`6b8943dc4a9b6b24425873992a44f29c0577503276456af46a8854774faeb294`
(matching the published provenance record), license = Xiph's BSD
3-clause COPYING (SHA-256 `e2f59ff4…`, bundled alongside the model
in assets). Independent projects have verified the serialized
arrays equal Xiph's `rnn_data.c` exactly. Host measurement (same
pink-noise testbed): neural suppression reached **−22.6 dB** of
noise reduction versus the spectral Balanced mode's −5.0 dB;
speech preservation is by design (the network is speech-trained)
and is judged by the owner's ear in the field test. The neural mode
ships as the fourth noise-removal mode in 0.3.3; the spectral modes
remain for comparison and fallback.

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

---

## TD-004 — Neural denoise engine: RNNoise (2017) replaced by GTCRN (2024) via ONNX Runtime

**Context.** The M3 neural mode shipped RNNoise v0.1 through FFmpeg's
`arnndn` (TD-003). The owner's field verdicts were consistent: noise
suppression itself was "excellent", but speech articulation came out
smeared ("مخارج الكلام مشوشة") across whole utterances. A dry/wet
clarity blend built to compensate was field-rejected (0.3.14): by
construction it re-admits the original noise through the dry path.
The defect is the 2017 model's ceiling, not tuning. Per the owner's
standing rule (2026-11-25), the replacement had to be measured to
proven success on the build host before delivery.

**Measurement (build host, before any integration).** Real speech
(Piper TTS, consonant-rich sentences) mixed with shaped noise at
8 dB SNR; log-spectral distance (LSD) to the clean reference over
speech-active frames (lower = closer to clean speech), plus the
noise-only tail level after processing:

| Engine | LSD to clean | Noise tail |
|---|---|---|
| Noisy input | 20.30 dB | -23.7 dB |
| RNNoise v0.1 (incumbent) | 13.11 dB | -28.2 dB |
| GTCRN (reference runtime) | 11.50 dB | -61.0 dB |
| GTCRN (this app's own STFT port) | 11.46 dB | -55.5 dB |

An earlier sine-tone articulation proxy was discarded as invalid
for learned models (a lone 2.7 kHz sine is not speech to them);
the real-speech test above is the governing evidence.

**Decision.** The neural denoise mode runs **GTCRN**
(`gtcrn_simple.onnx`, 535,638 bytes, SHA-256
e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534,
from the sherpa-onnx project's official model release) through
**ONNX Runtime Android 1.24.3** (`com.microsoft.onnxruntime:
onnxruntime-android`, resolved via the GCS mirror like everything
else). The reference runtime (sherpa-onnx) is **not** published on
Maven Central (verified 404 on both Central hosts), so it serves
as the verification oracle only; the app integrates the model
directly: `media/GtcrnEngine.kt` (streaming per-frame inference
with the model's three cache tensors; 30 s cores with 1 s context)
+ `media/Dsp.kt` (STFT/iSTFT) + `media/WaveIo.kt` (PCM16 WAV I/O).
Pipeline: FFmpeg extracts 16 kHz mono WAV → GTCRN → FFmpeg remuxes
the enhanced audio with the untouched video stream.

**STFT convention** (matched numerically against the reference
before porting): 512-point periodic Hann, hop 256, zero center-pad
256 both sides, overlap-add with window-square normalization. The
Kotlin port reproduces the golden STFT frame to 1.0e-5 max abs
difference and reconstructs identity to 3.4e-6 (JVM harness);
CI additionally runs an STFT/iSTFT identity unit test.

**Asset channel.** The .onnx is binary and this project's source
channel carries text only, so the model ships as
`assets/gtcrn.onnx.b64` and is decoded once into internal storage
with an exact-size check (the same pattern class as std.rnnn's
materialization). The base64 round-trip was hash-verified.

**Disclosed limitations.**
- The neural path is 16 kHz mono: content above 8 kHz is not
  preserved in neural mode. That is the speech band; the spectral
  modes remain full-band for music/general audio.
- This port's noise tail measured -55.5 dB vs the reference
  runtime's -61.0 dB on the artificial test (low-band residue);
  speech quality (LSD) is at parity. The remux chain's 70 Hz
  high-pass covers part of that band in practice.
- The APK grows by ONNX Runtime's native libraries (~tens of MB
  across the two shipped ABIs); `libc++_shared.so` collisions with
  FFmpegKit are resolved by packaging pickFirsts.
- Final device verdict (owner's ear, owner's material) remains the
  last gate, as with every milestone — no emulator exists here.

**RNNoise's remaining role.** `arnndn` still powers the Enhance
screen's pre-clean (measured -85.2 dB gap noise there; not the
articulation-critical path). The `denoiseNeural` command builder
is retained but no longer referenced by the UI; its removal is
deferred to the final audit (Command 7) to keep this change
minimal and reviewable.

**Addendum (0.4.1, owner's order after field PASS).** The owner
field-tested the GTCRN mode on 0.4.0 and judged it good ("الوضع
الباقي جيد الان"), then ordered the three spectral modes deleted
from the Noise screen. The screen now offers the GTCRN engine
alone, with the soften-start fade and start mute retained (both
ride the remux chain). The spectral `denoise` command builder and
its tests remain in the codebase, unreferenced by the UI, pending
the final audit (Command 7) — the same disposition as
`denoiseNeural`. The clarity blend went with the spectral modes:
it existed only to compensate their (and RNNoise's) smearing.
