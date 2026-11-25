# Technology Decisions

## TD-001 — Media engine for Milestone 2 (2026-11-25)

**Decision:** FFmpegKit `com.arthenica:ffmpeg-kit-full-gpl:6.0-2`
from Maven Central, restricted to `arm64-v8a` + `armeabi-v7a` ABIs.
Verified published 2026-11-25 via the Maven Central search index
(.aar/.pom/.module all listed) and Google's mirror of Central
(POM served, HTTP 200). Note: this sandbox's proxy answers 404 for
arthenica paths on repo.maven.apache.org itself — a local proxy
quirk, not a publishing problem; CI runners resolve from Central
directly.

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
