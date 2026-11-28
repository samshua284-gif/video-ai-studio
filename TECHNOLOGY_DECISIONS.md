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
