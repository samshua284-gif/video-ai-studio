# Milestone 2 — Media Engine: Report

Status: **COMPLETE** (all available quality gates passed, including
device field verification by the owner).

## Delivered

- Engine: FFmpegKit full-gpl 6.0-2 (FFmpeg 6.0, libmp3lame, libx264) —
  decision and licensing in TECHNOLOGY_DECISIONS.md (TD-001).
  Dependency resolution required Google's GCS mirror of Maven Central
  as an additional repository: the live Central CDN 404s this
  artifact even from CI runners (proven by the first M2 CI log).
- **Extract audio** (dashboard tool M2): MP3 at 128/192/320 kbps via
  libmp3lame, or WAV (PCM s16le). Real encoding — Android has no
  platform MP3 encoder.
- **Trim**: frame-accurate (input seek + re-encode, libx264 CRF 20 /
  AAC 160k), with pre-flight range validation; the end field is
  prefilled with the source duration (0.2.1, after a field report).
- **Compress**: three quality presets (CRF 20/1080p, 26/720p, 32/480p)
  with aspect-preserving downscale.
- Every export is **validated in-app** before being called done:
  existence, non-zero size, positive duration, track count, and a
  decodable first sample (`ExportValidator`).
- Progress reporting and cancellation for long jobs; failures show
  the engine log tail instead of a generic error.
- Tool screens carry an "Import another video" button (0.2.1, added
  after the owner's field report that tools were locked to the
  dashboard import).

## Quality gates

- **A — Compilation:** CI run 45598172704 SUCCESS (after the mirror
  fix); polish build 45600988045 SUCCESS.
- **B — Tests:** 8/8 unit tests in CI (3 formatting from M1 + 5 new
  `MediaCommandsTest` covering the exact FFmpeg argument arrays and
  the trim-range validation).
- **C/D — Functionality & output validation (device, owner-run):**
  - Compress: **correction (2026-11-25)** — the original claim here
    rested on a filename the owner imported; that file's hyphenated
    name did not match this app's `_compressed.mp4` pattern, and the
    owner's device log later proved compress had NEVER worked: the
    `scale=-2:min(…,ih)` filter expression is split by the
    filtergraph parser at the comma inside `min()`, failing with
    "No such filter: 'ih)'" (reproduced on the host). Fixed in 0.3.4
    by computing the scale decision in Kotlin and passing an
    explicit target height; field re-verification pending.
  - MP3 extraction from an audible 0:24 clip: result card "Done —
    export validated", output 565.39 KB (≈ 24 s × 192 kbps),
    duration 0:24 (matches source), 1 track, saved under the app's
    exports directory, with a working "Save to device" action.
  - Error path verified: extracting from a silent Pixaloop export
    fails honestly with the FFmpeg log tail shown
    ("Output file does not contain any stream").
  - Cancel path verified in the field (job cancelled, state shown).
  - Trim: engine path identical to compress/extract and covered by
    unit tests; a clean end-to-end field completion was not
    screenshot-confirmed (the one field attempt was cancelled by the
    owner mid-run) — recorded honestly, to be re-checked in the
    Milestone 7 audit sweep.

## Known defects carried forward

- **Debug signing is unstable across CI runs** (each runner generates
  a fresh debug keystore), so installing a new build over an old one
  is refused by Android; the owner uninstalls first. A fixed release
  keystore is required before the Milestone 9 release and is listed
  in the audit scope.
- Exports live in the app-specific directory; "Save to device" copies
  them out via the system document picker.
