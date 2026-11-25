# Video AI Studio

A production-oriented Android application for video enhancement, audio
cleaning, transcription, translation, and subtitle generation — built
milestone by milestone under the master engineering prompt in
[AGENTS.md](AGENTS.md), with real functionality only and honestly
reported verification.

## Status

- **Milestone 0 — Repository audit:** done. See
  [MILESTONE-0-AUDIT.md](MILESTONE-0-AUDIT.md).
- **Milestone 1 — Foundation:** project structure, navigation, premium
  dark UI, Arabic/English localization with RTL, video import through
  the Storage Access Framework, Media3 preview, and real metadata
  inspection (duration, resolution, codecs, audio/subtitle tracks)
  via `MediaMetadataRetriever` + `MediaExtractor`.

Later milestones (media engine, audio enhancement, speech recognition,
translation, subtitles, video enhancement, batch/queue/history) are
implemented in the order defined in AGENTS.md, Section 13.

## Build

Requirements: JDK 17, Android SDK 34, Gradle 8.9.

```bash
gradle :app:assembleDebug :app:testDebugUnitTest
```

Every push also builds in GitHub Actions (see
`.github/workflows/build.yml`); the debug APK is uploaded as the
`video-ai-studio-debug-apk` artifact of each successful run.

## Honest limitations (current)

- Gradle cannot run in the lead developer's sandbox (its daemon talks
  over loopback TCP, which the sandbox intercepts — proven by a
  controlled socket experiment), so canonical build verification runs
  in GitHub Actions; Kotlin sources are additionally compile-checked
  locally in-process.
- Runtime behavior is verified on a real device by the project owner;
  there is no emulator in the development environment.
- "AI upscaling" will be delivered as truthfully labelled high-quality
  scaling + denoise + sharpening; genuine detail reconstruction models
  are not realistic on a phone (AGENTS.md, Feature D).

## Privacy

Milestone 1 requests no broad permissions: file access happens only
through the system picker, per file, and originals are never modified.
