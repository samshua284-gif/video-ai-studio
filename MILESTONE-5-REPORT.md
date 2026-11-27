# Milestone 5 — Translation (0.6.0)

Delivered: offline translation of subtitles, segment by segment,
timestamps untouched, plus the TD-009 merge and the owner's
same-day additions.

## What was built

- **Engine (media/MtEngine.kt + assets/mt/engines/)** — NLLB-200-
  distilled-600M (quantized) via Transformers.js 2.17.2 in a
  hidden WebView; the voice translator's field-proven stack and
  worker rules (sentence-by-sentence, completeness guard, greedy
  decoding). Thirteen binaries fetched at build time by
  `fetchMtDeps`, SHA-256-pinned to the VT-proven artifacts.
  Languages: ar / en / hi / ur / tr (FLORES-mapped).
- **TranslateService** — foreground service, TD-008 survival
  contract (wake lock renewed per segment, progress
  notification, instant-visible cancel), auto-save of the
  translated SRT + TXT to Downloads on completion.
- **TD-006 Layer 1** — per-segment number verification
  (TranslateFix, digit scripts normalised); failures are flagged
  by segment number in the result UI, never rewritten.
- **TD-006 Layer 3** — segment-level correction memory
  (CorrectionMemory): owner edits harvested at export when the
  edited text line-aligns with the segments; stored pairs are
  served verbatim on identical future segments.
- **TD-009 merge** — SrtParser + SubtitleMerge (offsets by exact
  video-part durations, cumulative, in pick order), UI in the
  translation tool with per-part duration entry (mm:ss).
- **Translation tool UI** — sources: latest transcription, an
  imported SRT, or merged parts; source/target language chips;
  editable result; SRT/TXT export.
- **Owner's additions (2026-11-27)** — transcription import now
  accepts video OR audio in any format (MP3 → SRT/TXT through
  the same FFmpeg path); «طريقة الاستخدام» HowToCard with
  numbered steps in both the transcription and translation
  tools.

## Verification

- Pure layer host-run before push: SRT parse/round-trip, merge
  offsets and validation, number extraction/preservation across
  digit scripts, correction-memory lookup/harvest — 20/20 PASS;
  the same cases ship as JUnit (SrtMergeFixTest) in CI.
- CI (GitHub Actions): build + unit tests — see the run for
  head of this milestone.
- NOT verified here (needs the owner's phone): model load time,
  translation speed and quality on film-length material, WebView
  WASM behaviour on his device. The engine stack itself is
  field-proven in his voice translator for spoken sentences.

## Known limits

- APK grows by ~950 MB (NLLB model + JS/WASM engine).
- TD-006 Layer 2 (dual-engine agreement, back-translation, QE)
  is not in this build — recorded reasoning in TD-010.
- Sentence segmentation inside segments is the worker's; the
  subtitle-grade editor remains Milestone 6.
