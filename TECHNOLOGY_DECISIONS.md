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

## TD-005 — One shared before/after compare component (0.4.5)

**Context.** The compare-player fixes of 0.4.2–0.4.4 (own
Play/Pause button, restart after end, highlighted tabs,
autoplay on tab switch, full-screen frame, on-screen error
reporting) were earned on the Noise screen. The owner ordered
them applied to EVERY tool that previews original vs processed
— compression, extraction, noise, translation, and all future
ones.

**Decision.** The comparison lives in ONE file,
`ui/ComparePlayer.kt`: `CompareTabs`, `ComparePlayer` (video),
and `AudioComparePlayer` (audio-only outputs such as MP3/WAV
extraction). AudioTools and MediaTools both call it; the M4–M7
tools (transcription, translation, subtitles, enhancement)
must use it too rather than growing their own preview. The
export validator is likewise a single shared function
(`media/ExportValidator.kt`), so its 0.4.2 probe fix already
covered every tool.

## TD-006 — Translation correction layer for M5/M6 (design recorded 2026-11-25)

**Status: DESIGN RECORDED — NOT IMPLEMENTED.** Recorded at the
owner's order ("احفظ الخطة في وثائق المشروع") while the project
is closed; it slots into M5 (translation) and M6 (subtitles)
when the project reopens, and — per the owner's standing rule —
is measured on real samples before any success is claimed.

**Context.** The owner asked whether wrong words in offline
video translation can be discovered and fixed automatically,
and whether the layered plan below is the maximum possible.
The governing lesson comes from his voice-translator project:
general-purpose automatic correctors (Arabic QALB/CAMeL-class
models) corrupted more than they fixed and were rejected, and
an offline Helsinki model invented content and dropped phone
numbers in job ads. A corrector that invents is more dangerous
than an error that shows. This design therefore corrects only
by measurable rules, and otherwise DETECTS and lets the owner
decide — which is also the professional subtitling pipeline's
own ceiling (ASR → MT → quality flagging → human decision),
miniaturised for a phone.

**Layer 1 — Safe automatic fixes (applied silently).**
- Numbers/dates extracted from the source segment and verified
  present and correct in the translation (digit conversion
  ar/en is deterministic).
- Glossary protection: proper names and place names shielded
  from distortion; a known-error replacement list, in the
  spirit of the voice translator's two proven automatic
  corrections («الآن», «سلطنة عُمان»).

**Layer 2 — Automatic detection (flags, never rewrites).**
- Whisper per-segment confidence (avg logprob): weak segments
  are highlighted in the subtitle editor.
- Dual-engine agreement: OPUS-MT and NLLB translate the same
  segment; agreement = trusted, disagreement = flag + show
  both candidates on tap.
- Back-translation divergence: the translation is translated
  back and compared with the source; large divergence flags
  the segment.
- Optional upgrade: a distilled Quality-Estimation (QE) model
  scoring segments without a reference — more precise than
  back-translation, same flag-only role.

**Layer 3 — Personal correction memory (learns from the owner).**
When the owner edits a word in any subtitle, the correction
pair is stored and applied automatically to future occurrences.
His memory, growing with use; it invents nothing.

**Upstream levers (fix errors before they exist).** Most
"translation errors" are born in ASR: VAD-based segmentation
so words are never cut, punctuation restoration, translating
complete sentences rather than fragments, and carrying the
previous sentence as context where the model supports it.

**Bounded extras for flagged segments only.** Minimum-Bayes-risk
voting among several candidate translations; a small on-device
LLM as a CONSTRAINED post-editor (flagged segments only, barred
from altering numbers/names, diff always shown); and an
ear-check button playing the segment's original audio beside
both translations — the owner's ear has twice settled what
metrics could not (start thump, muffled articulation in M3).

**Explicitly excluded.** A free-rewriting "smart corrector"
that silently rephrases text — rejected on the owner's own
evidence above. Cloud-giant translation is likewise outside
the plan by TD-002 (offline-first); its quality is not the
target and never was.

## TD-007 — Milestone 4 binary delivery + segmentation (2026-11-26)

ASR ships Whisper-small via sherpa-onnx 1.12.40 (TD-002). The repo's
source channel carries text only and the model+AAR are ~430 MB of
binaries, so they are NOT committed and NOT base64-stuffed (the
GTCRN trick does not scale past ~1 MB): a Gradle task
(`fetchAsrDeps`) downloads them in CI from their canonical sources
(k2-fsa GitHub release; Hugging Face csukuangfj) and verifies
SHA-256 against the exact artifacts field-proven in the owner's
voice translator before every build. TD-002 already sanctions
builder-side internet; the shipped app remains permission-free.

Segmentation: sherpa's Whisper takes ≤~29.5 s per stream, so audio
is decoded in 25 s windows (the translator's proven chunk); each
window's text is stamped with the window's real bounds. Silent
windows (RMS < 0.004) are skipped rather than transcribed —
Whisper hallucinates on silence. Sentence-level segmentation is
deferred to the Milestone 6 subtitle system, by design.

## TD-008 — Long-job survival + speech gate (0.5.1, 2026-11-27)

Field evidence (first night of 0.5.0): with the screen awake the
owner's phone decoded at ~45 s per 25 s part; overnight, screen
asleep, it managed 13 parts in 5.5 hours (~25 min/part) — the
activity-bound coroutine was frozen by Doze. The engine was never
the problem; the job's host was. Decisions:

- The pipeline moves into a **foreground service**
  (TranscribeService, type dataSync) holding a **partial wake
  lock** (6 h timeout) for the job's duration, with a progress
  notification (part n/N, cancel action) and a completion
  notification. The screen only starts the job and observes its
  state. Permissions added: FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, POST_NOTIFICATIONS.
  INTERNET remains absent — TD-002 untouched.
- **VAD gate:** silero_vad.onnx (643,854 B, sherpa's own release
  asset, SHA-256 pinned in fetchAsrDeps like every other binary)
  runs before the decoder; a 29 s window with < 1.0 s of detected
  speech is skipped (pure decision in VadGate, unit-tested). The
  RMS silence floor stays as a pre-filter. Any VAD error falls
  back to decoding — the gate may waste time, never speech.
- **Windows 25 s → 29 s** (~14% fewer encoder passes; sherpa's
  ceiling is ~29.5 s) and **recognizer threads 4 → min(8, cores)**.
- Declared limits: VAD behaviour and thread gains are measurable
  only on the owner's phone; the service/wake-lock mechanics are
  standard Android, compile- and CI-verified here. The decisive
  test is the same film re-run with the screen off.
- Amendment (0.5.7, same day): the one-shot 6 h wake-lock timeout
  could lapse mid-film on jobs longer than 6 h (the owner's first
  film crossed that line), so the lock is now renewed on every
  transcribed part — its deadline always sits 6 h ahead of the
  latest part, for exactly as long as the job runs.

## TD-009 — SRT merge option inside Milestone 5 (decided 2026-11-27)

Owner's workflow for very long films: split the film into parts,
transcribe each part separately (shorter jobs, natural rest
points, no single job at the mercy of one failure), then work
with ONE subtitle file. Decision: the Milestone 5 translation
tooling gains a **merge SRT files** option — the user picks two
or more SRT files in order, and the tool emits a single merged
SRT.

Correctness rule (the whole point): each file's timestamps are
offset by the **exact durations of the preceding video parts** —
never by the preceding file's last subtitle timestamp. A part's
last line ends before the part itself ends, so offsetting by it
would shift every later line early and accumulate drift across
parts. The durations come from the user (entered per part) or
are read from the selected video files by the existing
inspection path.

The merge is language-agnostic pure timestamp arithmetic
(offset + continuous renumbering) in the SubtitleFormat family,
unit-tested like exportBaseName; it therefore serves both
orders — merge-then-translate and translate-then-merge.

Owner's ordering rule (2026-11-27, his words): the merge runs
**in order, from the first file to the last file, through to
the production stage** — files are processed strictly in the
sequence the user picked them, offsets accumulate in that same
sequence, and the single merged file is the production output
that continues onward (to translation and final export). No
reordering, no parallel shuffling: first → last → production.
Status: recorded decision only, NOT implemented; it lands with
Milestone 5, not before.

## TD-010 — Milestone 5 implementation choices (0.6.0, 2026-11-27)

- **Engine: the voice translator's proven stack, reused whole.**
  NLLB-200-distilled-600M (quantized) under Transformers.js
  2.17.2 / onnxruntime-web 1.14.0 in a hidden WebView, assets
  served under a fake https host with cross-origin-isolation
  headers (the VT serving pattern). All 13 binaries are fetched
  at build time by fetchMtDeps, SHA-256-pinned to the VT
  field-proven artifacts. The worker keeps VT's proven rules:
  sentence-by-sentence only (merged chunks silently drop
  sentences), per-unit completeness guard (retry once, then
  source passes through visibly), greedy decoding. Cost stated
  plainly: +~950 MB of model/engine assets in the APK.
- **Jobs run in a foreground service** (TranslateService) with
  TD-008's survival contract — wake lock renewed per segment,
  progress notification, instant-visible cancel, auto-save of
  the translated SRT + TXT to Downloads on completion.
  Segments keep their source timestamps untouched, so the
  translated SRT is synchronised by construction.
- **TD-006 as implemented here:** Layer 1 = number verification
  per segment (digit scripts normalised for the comparison);
  a segment whose numbers did not survive is flagged by number
  in the result — detected, never rewritten. Output digit
  script is NOT auto-converted (a display convention, left to
  the owner). Layer 3 = segment-level translation memory: the
  owner's edited lines are harvested at export (only when the
  edited text still line-aligns with the segments) and served
  verbatim on identical future segments. Layer 2's dual-engine
  agreement and QE scoring are NOT in this build: a second
  engine (OPUS-MT) is another ~300 MB per pair and another
  integration, for flag-only value; they remain recorded in
  TD-006 for a later, separately-measured step.
- **TD-009 merge is in:** SrtParser + SubtitleMerge (pure,
  unit-tested), UI in the translation tool — parts added in
  order, each with its video duration entered as mm:ss, merged
  strictly first → last into the production source.
- **Owner's same-day additions:** the transcription importer now
  accepts a video OR an audio clip in any format (the FFmpeg
  pipeline decodes either; an MP3 yields SRT/TXT like a film),
  and both the transcription and translation tools carry a
  «طريقة الاستخدام» (HowToCard) with numbered steps.
- Declared limits: NLLB quality/speed on the owner's phone is
  field-proven for single spoken sentences (voice translator),
  NOT yet for film-length batch translation; the first run's
  model load takes minutes. Both are his to judge in the field.

---

**TD-002 reaffirmed (owner decision, 2026-11-27):** A hybrid online/offline mode was proposed by the owner himself and considered with its trade-offs on the record — cloud transcription would be far faster (a 2.5 h film in minutes on a large model) but costs a provider account and per-minute fees, sends the film audio off the device, and gives the project's offline users nothing. The owner declined: **«لا، يبقى دون اتصال فقط»** — the app stays offline-only; the INTERNET permission is never added. Do not re-propose an online mode without his new order.

## TD-011 — Final transcription revision (0.7.0, owner's orders, 2026-11-27)

The owner declared this the LAST transcription edit and named its
contents himself:

- **Auto-save removed.** The 0.5.4 behaviour (SRT + TXT written to
  Downloads the moment a job completes) is deleted from the
  transcription pipeline at his order — «الحفظ اليدوي أفضل». Saving
  happens only from the Done screen's export buttons, when he
  presses them. (Translation's own save behaviour is untouched —
  his order named transcription.)
- **Speech-only decoding.** The fixed-window decode is replaced by
  a two-pass pipeline: a silero VAD pass over the whole file finds
  the speech spans; `SpeechChunks` (pure, unit-tested) plans decode
  chunks from those spans alone (merge gap 1.5 s, cap 25 s, minimum
  0.35 s of speech); Whisper decodes only the chunks. Non-speech —
  music, noise, effects — never reaches the decoder, so it cannot
  invent words for it. A decoded segment holding no letter or digit,
  or wrapped whole in brackets (a sound annotation), is dropped by
  `SpeechTextFilter`. The TD-008 rule survives: a VAD failure falls
  back to plain windows over the whole file — speech is never lost
  to a VAD error. `VadGate` (TD-008's per-window gate) is superseded
  by the chunk planner and deleted with its test. Declared limit: a
  scream that resembles a human voice can pass the VAD — the gate
  separates voice from non-voice, not shouting from talking.
- **Model size: small stays, by the owner's decision.** Whisper
  medium (int8, ≈946 MB vs small's ≈375 MB) was put to him with the
  measured trade-off — roughly half the Arabic word errors in our
  own corpus study (WER 5.2% vs 11.9%), at ~3× the transcription
  time and +~570 MB of app size — and he chose: «إبقاء small — نفس
  السرعة الحالية». The accuracy gains of 0.7.0 therefore come from
  the speech-only pipeline and the filters, not from a bigger model.

## TD-012 — Saves land in Downloads; audio picker opens there (0.7.1, 2026-11-27)

Field report on 0.7.0, same day: the owner extracted an MP3 from
his clip, pressed «حفظ في الاستيديو», got the saved ✓ — and could
not find the file anywhere he looks. The ✓ was honest but the
destination was wrong for him: `saveToGallery` (his own
2026-11-25 requirement) published audio to Music/VideoAIStudio,
which is neither Downloads nor anywhere the file manager shows
him first. His order replaced the rule: the button now reads
«حفظ في مدير الملفات» (his exact words) and `saveToDownloads`
publishes every media-tool export into the public Downloads
folder via MediaStore.Downloads; the document-picker fallback on
failure is unchanged. GallerySaver.kt is deleted; the Gallery
rule of 2026-11-25 is superseded by this decision.

Same report, second half: importing the extracted MP3 into the
transcription tool should «يوجهك تلقائيا الى ملفات mp3». The
transcription audio picker now launches through
`OpenAudioAtDownloads`, an OpenDocument contract that adds
EXTRA_INITIAL_URI for the Downloads document — a hint each
picker may honor; the audio/* filter applies regardless. That is
the strongest steering the platform offers a caller; it is
recorded here as best-effort by design, not as a guarantee.

## TD-013 — Speech gate completes the noise tool (0.7.2, 2026-11-27)

The owner's standing rule — maximum capability at every milestone
without draining time — was measured against the noise tool by his
own field comparison the same evening: a clip whose leading gasp
CapCut's gated cleanup erased «as if it never was» kept that gasp
through our GTCRN denoise (measured on his file: the processed
copy's first second is absolute digital silence; ours preserved
the breath as voice-like content). GTCRN's ceiling is exactly
this: it removes steady noise and protects everything produced
by a human mouth, breaths included. The gap is closed with a
second stage, not a different engine:

- After GTCRN, the silero VAD (the same model and configuration
  transcription trusts, via the new standalone VadSpans — a
  deliberate small duplication rather than touching AsrEngine,
  whose behaviour 0.7.0 froze) marks the speech spans of the
  denoised WAV.
- SpeechGate silences everything outside those spans, with the
  three protections a naive gate lacks: spans grow by safety
  margins (0.15 s before / 0.25 s after) so word onsets and
  tails are never clipped; pauses under 0.35 s are kept whole
  as natural prosody; all cuts ride 30 ms fades (click-free).
- Safety rails: no speech spans → the gate does not run at all
  (music and nature recordings are never wiped); any gate/VAD
  failure falls back to the ungated denoised audio — the gate
  can never fail a finished denoise. The option is a checkbox,
  default ON, beside the existing soften-start control; the
  manual start-mute remains for what it was built for.

Verified before the push: the pure region/gain logic and a
synthetic-WAV pass (gasp window, mid pause, two speech windows)
run in the host harness against the shipped Kotlin; the Compose
wiring is arbitrated by CI like every UI change before it.

## TD-014 — The dark-and-gold skin (0.8.0, 2026-11-27)

The owner brought a ChatGPT-made template (dark + gold home,
hero banner, two-column tool grid, bottom bar) and code for a
central design system, and ordered it adopted with three
decisions of his own: the crown image and the word "Pro" are
deleted (a free app carries no paid-tier branding); the bottom
bar holds only destinations that exist (Home, and Settings —
which routes exactly where its grid card routes today); and
the decorative noise-level slider shown in the mockup is NOT
adopted (the owner's own 0.4.1 decision removed levels; a
slider that does nothing would violate Rule 1).

Adaptation, not transplantation — the received code was an
uncompiled draft for a foreign package with hardcoded Arabic
strings, forced RTL and emoji icons. What shipped instead:
StudioTokens in Theme.kt is the single design source (the whole
app, transcription and translation included, re-skins from the
theme alone — their files were not touched); the dashboard is
rebuilt to the template (hero, gold import button at its
owner-mandated 64 dp, section rule, icon-chipped grid, bottom
bar) on the existing navigation and import machinery, with
drawn vector icons (material-icons-extended, BOM-managed) in
per-tool colours instead of emoji; shared GoldButton and
ToolPanel components carry the skin into tool screens (the
audio and media run buttons use GoldButton explicitly).
Strings stay bilingual at parity (161 keys); direction follows
the locale, never forced. No engine, pipeline or behaviour
changed anywhere.

## TD-015 — Whisper medium replaces small (0.9.0, owner's decision, 2026-11-28)

At 0.7.0 the owner chose small over medium for speed, with the
trade measured in front of him. In the field he found small's
word accuracy wanting («لا يعطي نتيجة جيدة إلى حد ما»), and on
2026-11-27 he reversed the decision explicitly: «بدّل التفريغ
إلى medium», deferred into the 0.9.0 round with Projects. The
measured basis stands from the voice-translator corpus study:
Arabic WER small 11.9% vs medium 5.2% on the same 20-sentence
corpus through this same sherpa path.

The swap changes model files and folders ONLY — the 0.7.0
pipeline (silero VAD spans, SpeechChunks planning,
SpeechTextFilter, the subtitle segmenter) is byte-identical.
This is deliberately NOT the voice translator's rejected 1.14
experiment: that failure was a different engine (whisper.cpp
medium through a JNI bridge, slower and deafer on his phone);
here the proven sherpa-onnx path stays and only its model
weights grow. fetchAsrDeps now downloads from
csukuangfj/sherpa-onnx-whisper-medium into
models/whisper-medium/ — medium-encoder.int8.onnx
(374,196,283 B), medium-decoder.int8.onnx (571,059,257 B),
tokens (816,730 B; the same vocab file as small's, hence the
same SHA-256) — every pin computed from the verified local
copies, never transcribed. silero_vad.onnx moves into the same
folder (pin unchanged); AsrEngine and VadSpans read it there.
On first run the new engine deletes the retired
asr/whisper-small materialization so its ~375 MB does not stay
orphaned on the phone (best-effort; a cleanup failure never
blocks transcription). Costs, stated to the owner before he
decided: the APK grows by roughly 570 MB and decode is slower
per chunk; both are the price of the accuracy he asked for.

## TD-016 — Projects: the works registry (0.9.0, owner's order, 2026-11-28)

The owner asked why a Projects section should exist when the
grid already carries History, Queue and Batch cards — and the
honest answer shaped the build: Queue is future work, Batch is
an action, History is an event log; none of them hands him back
the work itself. His own definition governed: «المشاريع جيد
لحفظ ما قمت بعمله» — Projects is where finished works live,
reopenable. The M8 History card therefore becomes «مشاريعي»
itself (the registry of completed works IS the history of what
was produced) and routes to the new page; Queue and Batch stay
honest M8 placeholders until their milestone.

Implementation: a pure-Kotlin store (media/Projects.kt — entry
= id, kind, title, locator, source name, creation time;
hand-rolled JSON, no Android dependencies, host-tested 16/16
including Arabic/quote/control-character round-trips and
corrupt-file tolerance) persisted as one small JSON file in
internal storage by media/ProjectsRepository.kt. An entry is
registered ONLY after a save really completes: a Downloads
publish that returned its content URI (saveToDownloads now
returns the URI instead of a boolean), a document-picker copy
that finished without error, or the translation service's
auto-save (which registers both the SRT and the TXT). Kinds
mirror the tools that actually save: MP3 (extraction),
TRANSCRIPT (transcription manual save), TRANSLATION,
ENHANCED (noise and audio-enhance outputs), TRIM, COMPRESS.

The page (ui/ProjectsScreen.kt, dark-and-gold, bottom bar now
Home / Projects / Settings — all three real) lists works
newest-first with kind icon, title, date and source; each row
can open (ACTION_VIEW; plain-path locators resolve through
the Downloads collection by display name, since a raw path
cannot be handed to another app), share, or be removed. A row
whose file was deleted outside the app says «لم يعد الملف
موجودًا» in place instead of crashing or failing silently.
Deletion removes the underlying file ONLY for a plain path
inside the public Downloads folder; every other entry is
removed from the list alone, and its button says exactly that
(«إزالة من القائمة» vs «حذف») before it is pressed. Registry
writes are best-effort: an index failure never fails a save
that already succeeded.

## TD-017 — Translation percent + in-app audio chooser (0.9.0, field defects, 2026-11-28)

Two field reports from the owner's own use, fixed in the same
round by his order («الان تعديل وبناء نموذج medium مع
المشاريع معا»):

1. A 113-cue SRT translation showed no percentage anywhere;
   he cancelled blind. The per-cue count already existed
   (TranslateJobState.Translating done/total) — what was
   missing was the percent and a notification that carried it.
   Now the m5_translating string itself carries the percent in
   both the tool UI (over the existing progress bar) and the
   foreground notification, and progress notifications update
   on whole-percent changes instead of a one-second clock: at
   most 100 updates per job, each carrying a number he can act
   on. The sentence-by-sentence rule and the NLLB engine are
   untouched.

2. The 0.7.1 audio-import steering (TD-012's
   EXTRA_INITIAL_URI towards Downloads) did not land on his
   audio files in his file manager — field verdict negative.
   The transcription audio import's first door is now an
   in-app chooser over MediaStore's audio index (name +
   duration, newest first, capped at 100), feeding the picked
   content URI into the existing import path unchanged. It
   needs a runtime permission the app never needed before —
   READ_MEDIA_AUDIO on API 33+, READ_EXTERNAL_STORAGE
   (maxSdkVersion 32) below — declared in the manifest and
   requested only when he taps the audio import. Denial falls
   back to the existing system picker automatically, and the
   picker remains a visible secondary button («منتقي النظام»)
   in both import states; an empty library says so and offers
   the same door. The video import flow is unchanged.
   Refinement (same round, the owner's follow-up): each
   chooser row also names the folder the file lives in —
   MediaStore RELATIVE_PATH on API 29+, derived from the
   legacy path column below it — because his audio is
   scattered across many folders and the single gathered
   list must say where each file sits while he picks.

## TD-018 — Saves split by output type: video to the Studio, audio to Downloads (0.9.0, owner's order, 2026-11-28)

TD-012 moved every save into Downloads after the owner's MP3
went invisible in a Gallery collection. His correction of
2026-11-28 splits the rule by what is being saved: «عند حفظ
الفيديو تكون خانه الحفظ مكتوبه حفظ في مدير الملفات قم
بتعديلها الى الحفظ في الاستوديو، وحفظ الصوتيات الحفظ في مدير
الملفات». The label and the destination move together — a
button never promises one place and delivers to another.

What changed: a new media/GallerySaver.kt publishes video
outputs through MediaStore.Video into Movies/VideoAIStudio
(the collection the system Studio/Gallery shows) and returns
the content URI, mirroring DownloadsSaver's contract, so the
TD-016 Projects registration works unchanged on both paths.
The document-picker fallback is kept for both. Classified by
the output file itself: MediaTools TRIM and COMPRESS (MP4)
and both AudioTools outputs (the denoised and enhanced clips
are MP4 video — cleaned audio remuxed with the untouched
video stream) now save with the button «الحفظ في
الاستوديو» / "Save to Studio" and confirm «حُفظ في
الاستوديو ✓». MediaTools EXTRACT (MP3/WAV) is untouched:
Downloads, «حفظ في مدير الملفات», «حُفظ في التنزيلات ✓» —
the 0.7.1 behaviour exactly. Text outputs are outside the
split: transcription/translation exports go where the owner
picks in the document picker, and the translation auto-save
still writes its SRT/TXT to Downloads as before.

## TD-019 — Translation stop that stops, visible load progress, and stale-text corrections (0.9.1, 2026-11-28)

**HEADLINE — the defect that outranked the other four.** The
owner's 0.9.0 screenshot showed the engine log tail:
«فشل تحميل نموذج الترجمة: Uncaught SyntaxError: Cannot use
import statement outside a module». Root cause, proven by
byte-level comparison: `mt/engines/mt.html` created the worker
CLASSIC (`new Worker('mt-worker.js')`) while mt-worker.js
opens with an ES-module import of transformers.min.js — a
classic worker dies parsing it, with exactly that error
string. The model therefore NEVER loaded: field translation
had been dead since M5 shipped in 0.6.0, invisible to CI
(which never executes a WebView) and to every host test. The
fix is one construction: `new Worker('mt-worker.js',
{ type: 'module' })` — the exact construction the owner's
voice translator ships (its index.html line 527) for the
identical worker and a transformers.min.js whose sha256 is
byte-identical to the copy pinned here (bcf7cf304e51f470…).
Audit of the same defect class: mt.html holds the ONLY
`new Worker(` in all web assets, and MtEngine's MIME mapping
already matches the voice translator's proven serving
(.js → text/javascript, .wasm → application/wasm,
.json → application/json), so no MIME change was needed.
Honest limit: removing the proven root cause is verified
statically; that the model now loads end-to-end and the
first cue translates can only be proven on the owner's
phone.

The remaining field reports from the owner on 0.9.0, each
root-caused from the shipped code before any change:

1. **Cancel did not stop.** ACTION_CANCEL only set a flag that
was consulted after `MtEngine.ensureLoaded()` returned and
between segments; the awaits themselves rode out the engine's
pathological ceilings (load 15 min, segment 20 min), so
«جارٍ الإيقاف» could hang for many minutes. Fix: a new
`media/AbortableAwait` — the bridge deferreds are awaited in
250 ms poll slices that check the owner's cancel flag, so a
stop lands in about a quarter second in BOTH phases. The
timeouts are untouched and still apply via withTimeout as
ceilings for the non-abort path, throwing the same
TimeoutCancellationException as before. Abort during a segment
abandons the await (the worker finishes that unit in the
background into a deferred nobody awaits; the engine stays
loaded and reusable). Abort during LOAD poisons the engine:
the next ensureLoaded destroys the WebView and recreates page
and worker from scratch rather than trusting a half-loaded
state. Translation semantics are unchanged — sentence by
sentence, same engine, same completeness guard, same saves.

2. **The load phase showed no progress.** The TD-017 cue
percent exists only in the Translating state, while the
owner's whole wait happens loading the ~950 MB NLLB model
behind a bare spinner. The worker already reported per-file
events; a new pure `media/MtLoadTracker` turns them into an
honest "file x of y": a file counts when the worker itself
reports it done (or at 100%), and the total is the fixed
7-file set fetchMtDeps pins (5 JSON + 2 ONNX — byte-weighted
percent was rejected because the WebView asset bridge does
not guarantee byte lengths, and no percentage may be
invented). LoadingModel now carries (filesDone, filesTotal,
currentFile); the tool UI draws the bar and count, and the
foreground notification carries the same numbers.

3. **Stale engine note.** `m4_engine_note` still said
"Whisper (small)" and "29-second parts" — the 0.9.0 engine
is medium and the pipeline decodes VAD speech chunks of up
to 25 seconds. Both locales rewritten with corrected facts
only, same structure and claims otherwise.

4. **Stale save claims in the transcription texts** (same
family, owner's order the same morning). `m4_howto_4` still
promised automatic saving to Downloads — false since 0.7.0
made transcription saves manual; rewritten in both locales
as a plain brief fact (save it yourself with the two export
buttons, wherever you choose), with no narration of the
change. The audit of its siblings found `m4_notif_done_saved`
and `m4_autosave_note` making the same false claim but with
zero usages left in the tree — both removed from both
locales. The m5 strings were NOT touched: translation
auto-save still exists, so its claims remain true.

## TD-020 — Translation confesses, transcription counts, input preview (0.9.2, 2026-11-28)

**Field fact that forced this release.** TD-019's headline
fix is FIELD-PROVEN on the owner's phone: the NLLB model
loads with its file counter running, a 118-cue EN→AR job
ran to completion and auto-saved — the first end-to-end
translation completion in the tool's history. But the
saved output was the English source verbatim for every
segment: every unit had fallen into the worker's
completeness guard (two empty attempts → pass the source
through visibly). Static comparison against the owner's
field-proven voice translator showed the whole chain
identical — worker file, transformers.min.js sha, the 7
pinned model files, the bridge protocol, the FLORES
language mapping, CorrectionMemory, the service loop — so
the failure lives inside the worker's per-unit generation
on his device, and it was INVISIBLE because
`translateOnce` swallowed the real error
(`.catch(function () { return ''; })`) and an empty
`translation_text` is indistinguishable from an exception.
0.9.2 is a diagnostic + honesty release: it makes the
engine confess. It does NOT fix the empty translations —
no speculative cure is attempted; the cure ships in a
later version once the real error is known, and the
owner's screenshot of the new Failed message is the input
to that root-cause fix.

1. **The worker stops swallowing** (`mt-worker.js`).
`translateOnce` now resolves `{text, error}`: an exception
is recorded as `name + ': ' + message`; a resolved-but-
empty `translation_text` stays EMPTY with error null. Each
unit therefore ends in exactly one of three outcomes —
OK (non-empty translation), EMPTY (pipe resolved, no
text), ERROR (pipe threw). The completeness guard's
behaviour is byte-for-byte the old behaviour (retry once
on empty/error, then pass the source unit through);
only its bookkeeping changed. `mt-done` gains
`diagnostics: {unitsTotal, unitsOk, unitsPassed,
firstError, srcLang, tgtLang}` — unitsPassed counts units
whose final result is the passed-through source, firstError
is the first recorded exception string of the request or
null when every failure was EMPTY-only, and the languages
are echoed back exactly as received so a mapping fault
would be visible too. `mt-partial` is unchanged.

2. **Bridge + service aggregate honestly.** `MtEngine`
returns a `MtResult` (text + the six diagnostics fields);
the pending-deferred plumbing carries it; a missing
diagnostics object degrades to zeros/nulls — nothing is
invented — and whole-request `mt-error` behaviour is
unchanged. `TranslateService` aggregates across the job
(segmentsTotal, fully-passed, partially-passed, first
non-null firstError). Segments served from
CorrectionMemory are NOT pass-throughs and count in
neither bucket, only in the total. The end-of-job
decision is a pure function, `TranslateOutcome.outcome`
in `TranslateFix.kt`:
   - every engine-served segment fully passed → **Failed**,
     never a fake Done: the message states plainly that
     every part came back untranslated, plus the firstError
     verbatim when one exists, or the fact that the engine
     reported no error and every result was empty. A
     Failed job is NOT auto-saved.
   - some segments fully or partially passed → Done as
     before (auto-save kept), PLUS a visible warning line
     on the Done screen and in the final notification with
     the owner's prescribed count text («مرّت X من Y مقاطع
     دون ترجمة — بقي نصها الأصلي كما هو»).
   - none passed → Done clean, exactly as before.
   New strings both locales: `m5_err_all_passed`,
   `m5_err_all_empty`, `m5_passed_warning`.

3. **Transcription percent** (owner's field report on
0.9.1, same visibility family as TD-017). Medium Arabic
quality was judged «جميل», but «لا يظهر عداد التقدم»:
`m4_transcribing` never got the percent TD-017 gave
translation, and `m4_extracting` showed a bar fraction
with no number. Both strings now carry the percent
(transcribing mirrors `m5_translating` exactly;
extracting takes the existing fraction ×100, rounded),
and every call site — tool UI and both service
notifications — passes it. **Known limit, stated plainly:
no sub-chunk progress is fabricated.** Sherpa decodes a
whole speech chunk per call, so the percent counts
completed chunks exactly as translation counts completed
cues; a 24-second clip is ONE chunk and reads 0% then
100%.

4. **Input preview player** (owner's explicit order,
screenshot-backed: he imported a WhatsApp voice note,
AUD-20260913-WA0002.opus, and found no way to hear it).
In the Transcribe tool's loaded state, under the input
card: Play/Pause («استماع» / «إيقاف مؤقت»), Stop, and an
elapsed/total readout refreshed while playing. Audio
plays through a platform MediaPlayer prepared from the
imported content URI; a file carrying a video track —
decided by its own inspection (`VideoInfo.videoMime`),
never by file extension — also shows its picture in a
bounded VideoView with the same controls. Works with
content:// URIs from both the in-app chooser and the
system picker. Lifecycle discipline: the player is
released when the input changes or the screen leaves
composition, and paused when a transcription job starts.
A prepare/decode failure (an exotic codec) shows one
honest line («تعذّر تشغيل هذا الملف») — the VideoView
error listener returns true so the platform's own dialog
never appears — and can never crash or block
transcription. New strings: `m4_preview_play`,
`m4_preview_pause`, `m4_preview_stop`, `m4_preview_failed`.
**Phone-only verification, stated honestly: opus decode,
VideoView rendering and the lifecycle on the owner's
Samsung can only be proven on his device; the code is
type-checked locally and in CI, playback itself is
IMPLEMENTED — NOT VERIFIED until his field test.**

**Verification.** Worker JS: `node --check` clean, plus a
node harness running the REAL shipped worker against a
stubbed pipeline — 18/18 (mixed OK/EMPTY/ERROR request:
unitsTotal 3, unitsOk 1, unitsPassed 2,
firstError "TypeError: stub boom", languages echoed,
pass-through text preserved; EMPTY-only → firstError
null; all-OK → clean). `TranslateOutcome`: host harness
8/8 and a JUnit twin (8 tests) in CI's
testDebugUnitTest. Existing suites re-run green:
SpeechChunks 32/32, SpeechGate ALL PASS, Projects 16/16,
MtLoadTracker + AbortableAwait 14/14. Full local
type-check of all 35 app sources with the Compose
compiler plugin: zero errors. Strings parity 187/187
exact key sets; aapt2 compiles both locales clean; the
manifest still carries no INTERNET permission.
Translation semantics, the module-worker fix,
AbortableAwait, MtLoadTracker, Projects, medium ASR,
all save behaviour and timestamps are untouched.

## TD-021 — Arabic is arb_Arab: the translation root cause (0.9.3, 2026-11-28)

**The confession arrived.** 0.9.2's diagnostics worked
exactly as designed: on the owner's first field run the
job ended Failed — honestly, with no fake save — and its
message carried the engine's first real error verbatim:

`Error: Target language code "ara_Arab" is not valid.
Must be one of: {ace_Arab, ..., arb_Arab, ...,
eng_Latn, ..., hin_Deva, ...}`

The valid set contains **arb_Arab**. It does not contain
ara_Arab. Arabic was the only broken code.

**Root cause.** When M5 was ported in 0.6.0, the
UI→FLORES map was written fresh in Kotlin
(`MtLanguages` in `media/MtEngine.kt`) instead of being
copied from the field-proven voice translator, and one
entry slipped: `"ar" to "ara_Arab"`. Every Arabic
request therefore threw inside the worker on its first
unit. From 0.6.0 to 0.9.1 the old worker swallowed
per-unit errors, so the throw was invisible and the
guard's pass-through made jobs "succeed" holding the
source text; TD-019's module-worker fix let the model
load and jobs complete end-to-end, but the output was
still the source — until TD-020's diagnostics surfaced
this error on the first run that could report it.

**Evidence chain (each link checked, none assumed).**
(1) The field error above, screenshotted from the
owner's phone 2026-11-28 10:40. (2) The model's OWN
tokenizer — `tokenizer.json` of Xenova/
nllb-200-distilled-600M, the exact model this app
downloads with pinned hashes: `arb_Arab` is present in
its vocabulary, `ara_Arab` is absent; the other four
app codes (eng_Latn, hin_Deva, urd_Arab, tur_Latn) are
all present — Arabic was the only invalid entry.
(3) The field-proven voice translator's map
(`app/index.html`): `ar: { ..., nllb: 'arb_Arab' }` —
the reference the 0.6.0 port should have copied.
(4) Repo-wide grep before the fix: exactly ONE
occurrence of the bad code existed, MtEngine.kt's map.

**The fix.** One word: `"ar" to "ara_Arab"` →
`"ar" to "arb_Arab"`. Nothing else in the map changes;
no string changes at all in this release.

**Regression lock.** `MtLanguagesTest` (JUnit, runs in
CI's testDebugUnitTest) asserts the map equals exactly
{ar→arb_Arab, en→eng_Latn, hi→hin_Deva, ur→urd_Arab,
tr→tur_Latn} — the five codes verified against the
tokenizer above. A future edit that drifts the map away
from the model's contract now fails the build instead
of failing silently on a phone.

**Lesson.** A language-code map is part of the model's
contract, not a detail to write from memory: verify
every code against the model's own tokenizer before
shipping, exactly as model files are hash-pinned. And
the meta-lesson of the whole arc: the defect survived
three releases because the error was swallowed — the
0.9.2 diagnostics stay permanently, because they are
what caught this.

**Phone-only remainder, stated honestly.** The code's
validity is proven here (tokenizer + reference +
regression lock). What only the owner's phone can prove
is the generation itself — NLLB's Arabic output quality
and speed on his Samsung. His re-run of the same EN→AR
job is the verdict: Arabic text in the result box is
the first true end-to-end translation in the app's
history.

## TD-022 — Field polish: a named load, a counter born at planning, manual-only translation saving (0.9.4, 2026-11-28)

Three items, every one of them ordered by the owner from
his 0.9.2 field runs on 2026-11-28. Nothing else moved:
the 0.9.2 diagnostics and honesty machinery and the
0.9.3 arb_Arab fix are untouched.

**Item 1 — transcription: the load has a name, and the
counter is born at planning.** Field report: «عند تفريغ
فيديو او صوت لايظهر عداد التقدم» — transcribing a video
or audio file shows no progress counter. The mechanics,
verified in the code: `AsrEngine.transcribe` began with
`recognizerFor(language)` — building the sherpa
recognizer over ~946 MB of medium model — then ran the
VAD pass and the chunk planning, and the FIRST
`onProgress` fired only after the first chunk had
decoded. Until then the job sat in `Transcribing(0,0)`,
whose UI fallback was the generic «تجهيز الملف...» over
a 0% bar. On a short clip that invisible stretch is most
of the job, so the counter effectively did not exist.

The fix has three parts. (a) The load is split out:
`AsrEngine.prepare(language)` performs exactly what
`recognizerFor(language)` does, with its caching —
idempotent, a no-op when the recognizer for that
language exists; `transcribe`'s internal call becomes
the cached no-op. (b) `TranscribeService.runPipeline`
runs the load as a new state, `TranscribeJobState.
LoadingEngine`, between extraction and transcription,
shows «جارٍ تحميل نموذج التفريغ...» on screen and in the
notification with an indeterminate indicator — no
percent, because the native load reports none and none
is fabricated — and checks the cancel flag the moment
`prepare` returns, finishing Cancelled BEFORE any VAD
or decode. A defensive `isCancelled()` check also sits
immediately after the internal `recognizerFor` call in
`transcribe`. (c) The counter is born at planning:
`transcribe` now calls `onProgress(0, chunks.size)`
immediately after the chunk plan is built — on both the
VAD path and the fallback-windows path — before the
decode loop. The remaining `Transcribing(0,0)` window
(VAD + planning) is labelled honestly as «جارٍ تحليل
الكلام...» (`m4_analysing`) in the UI fallback and in
the notification, replacing the generic preparing text.

**Item 2 — transcription cancel states its limit.**
Field report: «وعند الالغاء يتوقف عند جار الايقاف» —
cancelling seems to hang at «جارٍ الإيقاف...». A cancel
pressed during the model load must wait out the load:
recognizer construction is a single native call and
cannot be cut mid-way safely. Item 1's post-load check
bounds the wait to the load remainder plus ZERO decodes
— the job stops before any further work. What was
missing was the statement of that limit: the
Transcribing screen's Cancelling state now carries a
smaller line under «جارٍ الإيقاف...» — `m4_cancelling_
hint`: «يُستكمل الإيقاف عند أول نقطة آمنة — تحميل
النموذج أو فكّ جزء جارٍ لا يُقطع من المنتصف.» The
translation Cancelling gets no such line: its waits are
AbortableAwait slices and land in ~250 ms, so there is
no limit to confess.

**Item 3 — translation saving is manual-only.** The
owner's failed 0.9.2 run saved nothing (the TD-020
honesty rule), and HowTo item 5 still claimed the
translated SRT + TXT "are saved automatically to
Downloads" — a claim his own run had just disproved.
Offered both corrections, he chose (2026-11-28 10:50):
«اجعل حفظ الترجمة يدويًا فقط مثل التفريغ، وأزل الحفظ
التلقائي» — make translation saving manual-only like
transcription, remove the auto-save. That mirrors his
0.7.0 transcription rule exactly: the owner's standing
preference is that nothing is written until he presses
the button himself.

So: `TranslateService.autoSave()` and `writeDownload()`
are deleted entirely; no file is written at job
completion for DoneClean or DoneWithWarning; the Done
state no longer carries an `autoSaved` flag; the final
notification is the one plain text (`m5_notif_done` —
"open the app to see and export it"); the Done screen's
auto-save note is gone. The two export buttons (TXT,
SRT) — which operate on the in-memory Done result and
were field-used — are the only save path. `m5_howto_5`
is rewritten truthfully in both locales, opening with
the same sentence as transcription's corrected
`m4_howto_4` ("save the result yourself with the two
export buttons (TXT and SRT), wherever you choose"),
and keeping its other two facts, which remain true: the
⚠ line naming parts whose numbers need his check, and
the correction-memory sentence. The two strings that
existed only to claim the auto-save — `m5_notif_done_
saved` and `m5_autosave_note` — are removed from both
locales (the 0.9.1 precedent). Parity: 188/188.

**Projects coherence.** Translation works joined
Projects at auto-save time (0.9.0, TD-016). The
registration point moves to where saving now happens:
TranslateTool's manual export. That hook already existed
— the save launcher registers `ProjectKind.TRANSLATION`
with the picked document's URI when the copy finishes,
the exact pattern transcription's manual saves use — so
removing the service-side registration leaves precisely
one registration per exported file, and an unexported
translation registers nothing. The Projects model is
unchanged.

**Phone-only remainder, stated honestly.** What is
proven here: the code paths, the state machine, the
strings, the compile, the suites. What only the owner's
phone can prove: how long the named loading phase
actually lasts on his Samsung, whether the counter's
birth at planning reads as promised on a short clip,
and how the bounded cancel FEELS — the load remainder
is still a wait, now a named and bounded one.

## TD-023 — Detached decode: poison-and-drain, a process gate, and a live chunk clock (0.9.5, 2026-11-28)

The radical transcription-responsiveness release, ordered
by the owner after his 0.9.4 field runs («ما زالت مشكلتنا
في التفريغ الصوتي: العداد يقف مع القيمة الظاهرة في الصورة
ولا يتحرك، وعند الإلغاء جارٍ الإيقاف يأخذ وقتًا طويلًا —
أرجو إيجاد حل جذري»). One item-set only; translation, the
diagnostics, the planning math and the media tools are
untouched, and the atoms stay at 25 s (shrinking them is
an unmeasured hypothesis the parent explicitly excluded).

**The root.** A single chunk's sherpa `decode()` is one
long, opaque, uninterruptible native call. Until 0.9.5
the job thread sat inside it, so (a) the progress counter
had nothing to count during a chunk, and (b) a cancel
pressed mid-chunk waited for the whole decode — 0.9.4
named the phases and birthed the counter at planning,
but the atom itself stayed opaque and binding. This
release separates the decode from the screen entirely.
Total transcription time does NOT change; responsiveness
does. That non-claim is part of the decision.

**Thread confinement.** The whisper recognizer's whole
life — construction (`recognizerFor`), every stream's
create/accept/decode/result/release, and the recognizer's
own release — happens on ONE dedicated single-thread
executor owned by the new `media/DecodeCoordinator`. The
job thread never touches sherpa whisper objects; it
submits tasks and polls their futures in 250 ms slices
(the `AbortableAwait` pattern and its `MtAbortException`
signal, applied to a `CompletableFuture` and WITHOUT
AbortableAwait's timeout ceiling — that ceiling exists
for the MT bridge; a decode has no honest ceiling, see
the residual bound below). The silero VAD pass stays on
the job thread as before: a separate object, already
cancel-aware.

**prepare(language, isCancelled).** Construction is
submitted to the decode thread and awaited in slices.
The native build cannot be cut, but the await can: a
cancel during the load returns false at once, the
service's post-load check finishes Cancelled, and the
construction completes on the decode thread into a
ready-but-unused recognizer — harmless, released at
engine close. Abandoning a construction await never
poisons.

**Per-chunk decode.** Each planned chunk is ONE task
(stream create → accept → decode → result → release)
returning its text; the job thread awaits it in slices.
Completion follows the pre-0.9.5 flow exactly
(SpeechTextFilter, segment append, progress callback).

**Abandon + poison.** If the cancel flag rises while
awaiting a chunk, the job thread ABANDONS the await
within one slice (host-measured: 102 ms after the flag
rose, against a fake 10 s decode) and `transcribe`
returns its partial list promptly — the service's flag
check finishes Cancelled and discards the partials, as
a boundary cancel always did. The engine is now
POISONED: the orphan still runs on the decode thread;
when it returns, its result is discarded, its stream is
released by the task itself, and the drain callback
releases the recognizer and the process gate, clears
the poison, and — if the engine was closed meanwhile —
runs the close's finalise and shuts the executor down.
No new decode may ever start on a poisoned engine.

**The process gate — never two recognizers.** A
companion-level fair semaphore in DecodeCoordinator
guarantees at most one recognizer alive in the process
across engine instances (services are recreated per
job; the process is not). A fresh engine's prepare
acquires the gate before constructing; while a poisoned
predecessor drains, it waits — polling its own cancel
hook, so a cancel during that wait aborts the new job
cleanly while the orphan drains regardless. The gate
frees ONLY when the predecessor's recognizer is
actually released (the drain, or a normal close).

**Engine close.** `TranscribeService` still caches `asr`
per service instance. `AsrEngine.release()` releases the
VAD directly (job-thread object) and closes the
coordinator with a finalise that releases the recognizer
and the gate on the decode thread — submitted behind any
queued work, or deferred to the drain when poisoned. No
recognizer is leaked without a scheduled release.

**The live clock.** `TranscribeJobState.Transcribing`
gains `chunkElapsedSec`. The service tracks (done,
total, chunkStartMs) as the single source: the (0,total)
birth and each progress callback restart the current
chunk's clock; a 1-second ticker coroutine, alive only
while the job is in Transcribing and never fighting a
registered cancel, re-emits the state with the elapsed
seconds and refreshes the notification's text with the
same clock at most every ~2 s (its bar already carries
done/total; the existing throttle is respected). The
screen shows, under the unchanged counter line,
`m4_chunk_elapsed` — «جارٍ فكّ هذا الجزء منذ %1$s» —
rendered by the existing pure `formatDuration` (the
preview player's helper; m:ss, h:mm:ss beyond an hour).
A chunk has no internal progress to report; the honest
moving number is how long the atom has been cooking.

**The cancelling hint, rewritten to the new truth.**
`m4_cancelling_hint` now reads: «تتوقف الشاشة فورًا؛
وأي فكّ جارٍ يُنهي نفسه في الخلفية وتُرمى نتيجته.» /
"The screen stops at once; any decoding in flight
finishes in the background and its result is
discarded." The 0.9.4 "first safe point" wording is
gone — it described a wait this release abolished.

**Residual bound, stated plainly.** If a native decode
were to hang FOREVER, transcription would stay gated
until process death: the orphan never returns, the
drain never runs, the gate never frees. There is no
fake timeout and no unsafe kill — interrupting a native
call mid-flight risks corrupting the recognizer for
every later job, which is worse than the wait. In
practice a decode of at most 25 s of speech is finite;
the bound exists so the design's one unfixable case is
named, not hidden.

**Verification.** The orchestration is host-tested
against the REAL shipped `DecodeCoordinator` (harness in
vas-host-test/mt095, 28/28): (a) a cancel during a fake
10 s decode freed the awaiting side 102 ms after the
flag rose (bound: ~300 ms); (b) the orphan's completion
ran the drain, freed the gate, and a second coordinator
acquired it in 1 ms; (c) three normal decodes returned
their results and progress in order; (d) a gate wait
during a predecessor's drain aborted on its own cancel
in 501 ms (flag at 300 ms + one slice) while the orphan
drained regardless, and the gate was free afterwards; a
construction abandon does not poison; `formatDuration`
renders the clock (0:00, 0:07, 1:05, 10:00, 59:59,
1:01:01, negative clamped). A JUnit twin
(`DecodeCoordinatorTest`, shorter sleeps) runs the same
invariants in CI, and FormatTest gained the clock cases.
Existing host suites re-run green: SpeechChunks 32/32,
SpeechGate ALL PASS, Projects 16/16, mt091 14/14,
TranslateOutcome 8/8, MtLanguages 8/8, worker node
18/18. Full local type-check: all 36 app sources, zero
errors. Strings parity 189/189 (one addition:
`m4_chunk_elapsed`; one rewrite: `m4_cancelling_hint`),
aapt2 clean in both locales, `ara_Arab` in code still
0, no INTERNET permission.

**Phone-only remainder, stated honestly.** What is
proven here: the orchestration mechanics against fake
decodes, the compile, the suites, the strings. What
only the owner's Samsung can prove: real medium decode
durations per chunk, how the instant cancel FEELS, how
long a real drain takes after an abandoned long chunk,
and whether the ticking clock reads as promised on his
short clips — his original complaint case.

## TD-024 — Overlapped voices: clause-level cue placement and a minimum cue floor (0.9.6, 2026-11-28)

The phase-closing build, ordered by the owner after 0.9.5
was field-confirmed («نجح الأمر مبروك، ولكن تبقى مشكلة
تداخل الأصوات في الترجمة — بعدها نغلق هذه المرحلة»).
One file's mechanics only: `media/SubtitleSegmenter.kt`.
Everything else is frozen.

**The defect, mechanically.** Translation never retimes
cues, so the drift and interleave the owner sees in
translated subtitles is inherited from the transcription's
cue placement in overlapped-speech regions. Overlapped
voices produce ONE long unbroken VAD span (no pauses)
holding many sentences, and whisper returns
under-punctuated run-on text for the mixture.
`SubtitleSegmenter.distribute` placed sentences by global
character-length proportion, so (1) a single huge sentence
became one huge cue pinned to a wide proportional window —
a guess that visibly drifts — and (2) very short
interjections got sub-second windows that flicker. Cues
were already monotonic and non-overlapping by
construction; this pass tightens PLACEMENT granularity
and readability inside spans without breaking that.

**Change 1 — clause-level splitting of over-long
sentences.** Before distribution, `splitClauses` splits
any sentence longer than 84 characters at clause
boundaries, in this priority: '،', ',', '؛', ';', '—',
'–', and finally ' و ' (space-waw-space). Boundary
punctuation stays attached to the PRECEDING piece; the
conjunction stays attached to the FOLLOWING piece, as
Arabic reads. Within one boundary kind, the cut nearest
the piece's midpoint is chosen. A split is usable only
when both sides keep at least 20 characters (no
shredding); a sentence with no usable boundary stays
whole and is never cut mid-word. HARD INVARIANT:
splitting loses and adds nothing — rejoining the pieces
in order reproduces the original sentence exactly,
character for character, separators included. The pieces
flow into `distribute` as individual sentences; the
sub-cues carry no spans, exactly as distribute's output
was already shaped.

**Change 2 — a minimum cue duration floor of 0.8 s.**
After a span's group is placed by the existing logic, any
cue shorter than the floor is extended by borrowing time
from the other cues of the same span: the FOLLOWING cues
first (nearest first), then the preceding ones — the
preceding fallback exists for a short cue whose following
neighbours are themselves sub-floor, where the time can
only come from earlier in the same span. A donor never
drops below the floor itself. If every donor is already
at the floor, the short cue grows into the span's unused
room. Borrowing never crosses the span's bounds, never
creates overlap or a negative duration, and never
reaches into a silence gap. THE FLOOR YIELDS when the
span cannot fund it: if the span is shorter than
cues × 0.8 s, the existing proportional placement for
that span stands unchanged. When neither the cap nor the
floor binds, behaviour is identical to 0.9.5's
segmenter — verified, not hoped (see below).

**Honest scope.** This tightens cue placement for
app-produced transcriptions; imported SRT files keep
their own timings. Two voices truly simultaneous in the
recording still share one span and one mixed text
stream — no diarization or source-separation model
exists in this offline stack, and that residual is a
physical limit, recorded here as the phase's known
limit at closing.

**Verification.** JUnit (`SubtitleSegmenterTest`, runs
in CI) plus a host twin harness against the REAL shipped
segmenter (vas-host-test/mt096, 47/47): (a) the
text-preservation invariant holds for Arabic and English
samples across every boundary kind, including a ' و '
case (the conjunction opens the following piece) and a
no-boundary case that stays whole; (b) one 25 s span
carrying 10 alternating short sentences yields 10 cues,
monotonic, pairwise non-overlapping, every cue ≥ 0.8 s,
all inside the span; (c) one unpunctuated 303-character
run-on over a 20 s span yields 5 cues whose texts rejoin
to the original sentence exactly, every cue text ≤ 84
characters; (d) ALL pre-existing SubtitleSegmenterTest
cases pass UNCHANGED — no expectation was edited. Two of
them now exercise the new code: the dialogue block's
floor binds (its shortest cue, «Yep.», was 0.42 s under
the old placement and is exactly 0.8 s now; count,
texts, endpoints and contiguity are unchanged), and the
voice-spans case is bit-identical because the floor
never binds there. A floor-yield case (3 cues in a 2.0 s
span) keeps the proportional placement. Existing host
suites re-run green: SpeechChunks 32/32, SpeechGate ALL
PASS, Projects 16/16, mt091 14/14, TranslateOutcome 8/8,
MtLanguages 8/8, DecodeCoordinator 28/28, worker node
18/18. Full local type-check: all 36 app sources, zero
errors, 313 classes — identical to 0.9.5, as expected
for a change inside one existing object. Strings parity
189/189 exact key sets — NO string changes in this
release; aapt2 compiles both locales clean; `ara_Arab`
in code still 0; no INTERNET permission.

**Phone-only remainder, stated honestly.** What is
proven here: the splitting invariant, the floor's
placement guarantees, and non-regression, all against
the shipped code. What only the owner's ear can prove:
the felt sync on real overlapped film dialogue. The
synthetic placement gains are what this build claims —
nothing more.

## TD-025 — The transcription correction station: words corrected inside their timed cues (0.10.0, 2026-11-28)

Ordered by the owner as a big item after the 0.9.x phase
closed by his word: «لدي فكرة — لماذا لا ننسخ النص المفرغ
ونقوم في إصلاح كلماته لغوياً وبعدها نحوله ملف SRT» —
take the transcribed text, fix its words linguistically,
then make the SRT from the fixed text. The scope he
approved by name: a correction screen for the timed
cues, preview at the cue's moment, find & replace across
all cues, a correction memory for transcription, a
narrow automatic-fix mechanism, and correction feeding
everything downstream. The exclusions he approved with
it: no retiming, no cue split/merge, no diarization, no
ASR engine/model change, no general rewriter.

**The one engineering fact that shapes the build.**
Timings live in the cue structure, not in the text. The
naive form of the idea — copy the plain text out, fix
it, convert it back to SRT — throws every timing away,
and rebuilding them from bare text would re-create the
proportional-placement error measured in mt097 (mean
0.26 s, max 0.65 s) or worse. So the station corrects
the words INSIDE their timed cues: the SRT that comes
out carries the original timing lines byte for byte,
and only the text lines change.

**The structural timing lock.** The pure core
(`media/TranscriptionCorrection.kt`) accepts segments
plus text-keyed edits and returns segments built with
`AsrSegment.copy(text = …)` — start, end and speech
spans are the input's own values, and no function in
the layer takes a timing as a parameter, so no caller
can retime a cue even by accident. The screen's
editing state is a list of texts only, one per cue;
timings are not merely displayed read-only, they are
never in the room. The service enforces the other
half: `TranscribeService.applyCorrectedSegments`
replaces the Done state only with a list of the SAME
size — correction can neither add nor remove a cue.

**The six parts.** (1) The correction screen
(`ui/CorrectionTool.kt`), entered from the
transcription Done state by a labelled button: one
card per cue — its time range shown, its text editable
in place. (2) Hear-the-cue preview on the proven
InputPreview pattern (0.9.2): MediaPlayer for audio, a
bounded VideoView when the file's own inspection says
it carries video; tapping a cue seeks to its start and
playback pauses at its end (a 200 ms poll, the input
preview's rhythm). When the screen was recreated and
the imported file forgotten, there is no preview and
the station simply works without it — correction never
depended on it. (3) Find & replace across all cues:
literal `String.replace` — no regex, no word
boundaries, single pass — with the count of cues that
WILL change shown live before applying and the count
that DID change shown after. (4) A correction memory
for transcription, mirroring the translation design
(TD-006 Layer 3): pairs harvested at save time under
`CorrectionMemory.key`, stored in SharedPreferences
(`TranscribeService.CORRECTION_PREFS`, the counterpart
of `translation_memory`); when the same recognized
text recurs, the stored correction is OFFERED on the
cue, visibly marked as his memory — applied only on
his tap, dismissed on his tap, never silent. (5) The
Layer-1 mechanism, `applyRules`, runs at the station's
door over `BUILT_IN_RULES` — which ships EMPTY, and
the emptiness is the honest part: a rule may enter
only with its own measured evidence on whisper-medium
output, no such evidence exists in this project today,
and the voice translator's rules belong to a different
model and were not imported. The live source of fixes
is the owner's memory (part 4), applied by his hand.
There is no general linguistic rewriter in this build,
by the approved scope and by the standing design
(a corrector that invents is worse than an error that
shows). (6) Correction precedes translation: the
corrected list becomes THE transcription result — the
Done state itself is replaced — so the SRT/TXT
exports are built from corrected cues and the
translation tool's in-memory source (which reads the
Done state) receives the corrected text. An already
imported SRT, or a translation source the owner had
already picked from an older transcription, is not
retro-changed: source selection keeps its existing
semantics, and re-picking «the last transcription»
reads the corrected cues.

**Verification.** Host harness on the REAL shipped
files (vas-host-test/mt100, 39/39): (a) after text
edits, replace-all, rule sweeps and memory application
— and after the full chain the screen performs — the
timing fingerprint (every start, end and span) is
identical to the original's; out-of-range edits are
ignored; untouched cues are the same objects. (b)
Replace-all counts and contents exact on Arabic and
English samples, including the guards (empty needle,
find == replacement), single-pass growth (a replacement
containing the needle is not rescanned) and literal
substring semantics. (c) Memory: a taught pair is
offered on recurrence at the right index and changes
nothing by itself; a tap applies it with timings
intact; blank/unchanged edits and size mismatches
teach nothing; the whitespace-normalised key still
offers. (d) The corrected SRT's timing and index lines
are byte-identical to the uncorrected export's, its
text lines differ exactly where edited, and the TXT
reflects the corrections. JUnit twin
(`TranscriptionCorrectionTest`, 14 tests) runs in CI
with the full suite. Full local type-check: all 38 app
sources, zero errors, 333 files in the check jar (313
classes at 0.9.6; the growth is the two new files and
their composables). Strings parity 203/203 — exact key
sets in the same order; aapt2 compiles both locales
clean; `ara_Arab` in code still 0; the manifest is
untouched and still declares no INTERNET permission.

**Phone-only remainder, stated honestly.** What is
proven here: the timing lock, the sweep exactness, the
memory semantics and the export identity, all against
the shipped code, plus CI's full build and test suite.
What only the owner's hands can prove: the station's
feel on a real film's transcription — correcting a cue
while hearing it, teaching a fix and meeting it again
in the next file. The synthetic guarantees are what
this build claims — nothing more.

## TD-026 — Work drafts and the translation correction station: the translator inside the corrector (0.10.1, 2026-11-28)

Ordered by the owner after his first field session on
0.10.0, in his words: «لقد تعبت والله — عندما خرجت من
المصحح اللغوي اختفى كل شي ولم أحفظ ملف SRT، وبحثت في
المشاريع ولم أجده» (he exited the corrector and
everything was gone; no SRT saved; nothing in Projects),
and «يجب أن يكون هناك مترجم أوفلاين في المصحح» (there
must be an offline translator inside the corrector).
Two parts, approved by name; nothing else was built.

**Part A — the work draft.** The 0.10.0 loss was
structural, not a bug in one screen: a finished result
lived only in the services' in-memory state, and
Projects registers only manual exports (TD-016), so
leaving the tool — or the app dying — discarded the
work itself. The fix is a draft, and the draft's
boundary is the decision: a draft is app-internal
working state (filesDir/transcription_draft.json,
filesDir/translation_draft.json, written by
DraftsRepository over the pure WorkDrafts models). It
is written when a job finishes and at every
correction-station save; it is cleared when a new job
starts; opening a tool idle restores it as the Done
state under a visible «مسودة مستعادة» note. A draft
NEVER writes to Downloads or MediaStore, NEVER
registers a Project, and NEVER exports anything — the
owner's manual-save rule (0.7.0, TD-022) is untouched,
because what he ordered then was that no FILE appear
without his tap, not that his work be fragile. The
draft model is pure Kotlin with hand-rolled JSON in
the ProjectStore discipline; doubles round-trip via
Double.toString/parseDouble, so a restored result's
segments are byte-exact in text and bit-identical in
timing (host harness mt101, section a). A corrupt or
foreign draft file reads as no draft at all.

Restore edges, stated plainly: the draft stores the
source media URI when known. MainActivity's 0.5.5
restore usually re-attaches the last imported file on
its own; the tools additionally re-inspect the draft's
URI when nothing is attached. If the URI is no longer
readable, the result, its exports and both stations
work unchanged — only hear-the-cue is unavailable, the
TD-025 forgotten-file degradation. Uninstalling the
app (required between CI-signed builds) clears drafts
and both correction memories together with all app
data, as before.

**Part B — the translation correction station.** The
mirror of TD-025 for a finished translation
(ui/TranslationCorrectionTool.kt over the pure
media/TranslationCorrection.kt): each cue card shows
the SOURCE text read-only above the editable
translation; timings are locked by the same structural
discipline (text-keyed edits only, rebuilt through
AsrSegment.copy; the source list is never an output).
Hear-the-cue works when the source media is known —
the translation tool now tells the service, before a
job starts, whether its source is the latest
transcription (whose media is the transcription's
input); an imported or merged SRT carries no media and
the button is honestly absent.

«أعد ترجمة هذا السطر» runs the existing MtEngine on
the cue's source text as a single unit — the unit
rules, the worker and the guard do not change — using
the station's own engine instance (the service's
engine is released with its job and is never borrowed;
TranslateJobState.Done now carries the job's language
pair so the station re-translates with the pair the
job actually used). The outcome is judged per cue by
TranslationCorrection.evaluate, TD-020's honesty at
cue level: a real rendering is OFFERED in the card and
replaces the cue's text only on his «اعتماد» tap —
applyOffer is the single path from an offer to a cue —
while a complete pass-through or a blank result is a
failure whose card shows the engine's first error
verbatim, and a partial pass-through offer states its
own count. Offering the passed-through source as a
"new translation" would repeat the masquerade TD-020
abolished.

Memory: station saves harvest into the EXISTING
translation CorrectionMemory with the existing
semantics — TranslationCorrection.harvest is the
segment-list form of CorrectionMemory.harvest, and
the mt101 harness proves the two produce identical
pairs from identical final texts. Suggestions key on
the source segment, as the translation job's own
lookup does, and apply only on tap. On save,
TranslateService.applyCorrectedSegments replaces the
Done result under the same size guard as
transcription's, so exports and the Projects
registration at manual export consume the corrected
cues with byte-identical timing lines.

Verification: host harness mt101 on the real shipped
pure files (WorkDrafts, TranslationCorrection,
TranscriptionCorrection, CorrectionMemory,
SubtitleFormat) — draft round-trips for both tools
including timing fingerprints, station timing lock and
source immutability, the offer/accept state machine,
and harvest equivalence; JUnit twins
(WorkDraftsTest, TranslationCorrectionTest) run in CI.
What only the owner's hands can prove: the restore
feel after a real exit, and re-translating a garbled
cue on a real film — the synthetic guarantees are what
this build claims, nothing more.

## TD-027 — Station exit returns to the tool's result screen; selectable source text in the translation station (0.10.2, 2026-11-28)

Ordered by the owner after his field test of 0.10.1
(verdict: everything good except this): exiting the
correction station — in the transcription tool and in
the translation tool alike — threw him to the app's
main dashboard instead of returning him to the tool
screen he had entered from. One narrow fix; nothing
else was built, plus one item the owner added mid-build
(recorded below).

**Root cause — the same two causes in both tools.** A
correction station is a state branch inside its tool's
screen (TranscribeToolScreen, TranslateToolScreen),
not a navigation destination. (1) The tool's own header
was composed ABOVE the branch: the outer Column first
rendered the tool's back button — bound to
nav.popBackStack(), the dashboard — and only then the
station, so while the station was open two back buttons
were stacked on screen and the first of them, in the
position back occupies on every other screen, left the
tool entirely. (2) The system back was not intercepted:
the app had no BackHandler anywhere, so the Android
back button or gesture popped the whole tool
destination off the back stack, station or not. The
station's internal exits (its own back, its save) were
already correct in code; the two paths above are the
ones that betrayed the exit intent.

**The fix.** The screen decision is now a pure shared
core, media/StationNav: screen(stationOpen, hasResult)
returns STATION only while the station is open and a
finished result stands behind it, and exit(stationOpen)
is the single exit transition — the flag clears, the
tool screen returns. Both tool screens take the
decision FIRST, before their header is composed: while
the station is open only the station is composed, so
the only back on screen is the station's own. A
BackHandler(enabled = correcting) in each tool screen
routes the system back through the same exit. Every
exit path — save, the station's back, the system back —
now lands on the owning tool's result screen, with the
corrected result the service already holds, exports and
station re-entry exactly as before entering. The
defensive case (an open flag with no result behind it)
resolves to the tool screen itself, its home state when
no result exists: never a crash, never a dead screen.
Nothing else changed: station internals, the draft
semantics of TD-026, the memories, the exports and the
services' state contracts are untouched.

**The owner's added item (mid-build, by his explicit
order with a field screenshot).** In the translation
station the read-only source text («النص الأصلي») was
plain display text: a long-press selected nothing. The
source value now sits in a SelectionContainer, wrapped
around that Text alone, so he can long-press, select
and copy it. The translation edit field below keeps its
own text-field selection and is unchanged.

Verification: host harness mt102 on the real shipped
StationNav — the full transition table for the decision
both screens call (station shows only open-with-result;
exit from a result-backed session lands on the tool
screen with the result still standing; exit without a
result falls back to the tool home; exit is idempotent;
re-entry works), with a JUnit twin (StationNavTest) in
CI. The selectable source is a Compose affordance over
an unchanged Text; it carries no logic to harness.
What only the owner's phone can prove: the actual
on-device back behaviour (system gesture and button,
per tool and per exit path) and the long-press copy
feel — the harness proves the state decision the UI
makes, not the platform's delivery of the gesture.
