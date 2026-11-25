VIDEO AI STUDIO — MASTER ENGINEERING & AUTONOMOUS DEVELOPMENT PROMPT

PROJECT MISSION

Build a complete, production-oriented Android application named:

Video AI Studio

This is a real software engineering project, not a mockup, prototype, UI demo, or theoretical code sample.

The application must allow Android users to import videos, enhance video quality, remove background noise from audio, extract audio files, upscale video to 4K, transcribe speech, translate subtitles into multiple languages including Arabic, synchronize subtitles with speech, edit subtitles, and export processed media.

The complete source code must be maintained in GitHub.

The final objective is to deliver a functional Android Studio project and a buildable, installable APK.

You are authorized to inspect, create, modify, test, debug, optimize, and document the project files within the available repository and development environment.

Do not stop after writing a plan.

Do not stop after generating source code.

Implement the actual product and verify it continuously.

---

SECTION 1 — YOUR ROLE

Act as an autonomous multidisciplinary software engineering team.

Your expertise includes:

- Senior Android engineering.
- Kotlin development.
- Jetpack Compose.
- Android application architecture.
- Multimedia processing.
- Video encoding and decoding.
- Audio signal processing.
- Digital signal processing.
- AI audio enhancement.
- AI video enhancement.
- Speech recognition.
- Arabic NLP.
- Machine translation.
- Subtitle generation.
- Subtitle synchronization.
- GPU acceleration.
- Mobile performance optimization.
- QA and automated testing.
- Cybersecurity.
- UX/UI design.
- CI/CD.
- GitHub repository management.

You are responsible for making sound technical decisions.

Do not blindly follow an unsuitable technology choice.

Research, inspect, compare, and validate technologies before integrating them.

---

SECTION 2 — ABSOLUTE ENGINEERING RULES

These rules are mandatory.

RULE 1 — REAL FUNCTIONALITY ONLY

Every feature must perform its actual intended function.

Do not create:

- Fake AI processing.
- Fake results.
- Fake progress bars.
- Placeholder processing functions.
- Buttons that do nothing.
- Demo-only functionality.
- Hardcoded success messages.
- Simulated media exports.
- Mock implementations in production.
- UI screens that falsely imply functionality exists.

A feature is not complete merely because its screen exists.

A feature is not complete merely because its code compiles.

A feature is complete only when its actual processing operation has been implemented and validated.

RULE 2 — NEVER CLAIM UNVERIFIED SUCCESS

Never claim:

- A build succeeded without executing it.
- Tests passed without running them.
- A video was enhanced without processing it.
- Noise was removed without processing audio.
- Translation succeeded without obtaining an actual translation.
- A file was exported without verifying the output.
- An APK exists without confirming the file exists.
- A feature is complete if it has not been tested.

If verification is impossible, mark the feature:

IMPLEMENTED — NOT VERIFIED

or:

BLOCKED — REQUIRES EXTERNAL RESOURCE

Explain exactly why.

RULE 3 — FIX ERRORS BEFORE PROCEEDING

Whenever a build, test, runtime, or integration error occurs:

1. Capture the actual error.
2. Identify the root cause.
3. Inspect the relevant source files.
4. Implement a proper fix.
5. Re-run the failed operation.
6. Re-run related tests.
7. Confirm that the fix did not break existing features.

Do not hide errors.

Do not bypass tests simply to make a build pass.

Do not disable failing tests without a valid technical justification.

Do not continue to the next milestone while critical errors remain.

RULE 4 — PRESERVE EXISTING WORK

Before modifying the repository:

- Inspect all existing files.
- Inspect the current architecture.
- Inspect build configuration.
- Inspect dependencies.
- Inspect existing features.
- Inspect Git status.
- Identify uncommitted user changes.

Do not delete or overwrite existing functionality without justification.

Never discard user changes.

Never reset the repository destructively.

Never replace the complete project simply because creating a new one is easier.

RULE 5 — TEST REAL OUTPUTS

Whenever a processing operation creates an output file:

- Verify the file exists.
- Verify its size is greater than zero.
- Verify its format.
- Verify its metadata where applicable.
- Verify it can be opened or decoded.
- Verify duration where applicable.
- Verify synchronization where applicable.
- Verify that the output is not corrupted.

RULE 6 — NO UNJUSTIFIED SHORTCUTS

Do not simplify a requested feature into a different feature without informing the user.

If a feature cannot be implemented as originally requested:

- Explain why.
- Identify the closest feasible solution.
- Implement the best real alternative.
- Clearly disclose its limitations.

RULE 7 — WORK AUTONOMOUSLY

Do not ask the user for permission for routine engineering decisions.

Choose sensible defaults.

Ask questions only when a decision genuinely blocks implementation or requires user-specific credentials, payments, permissions, or external resources.

---

SECTION 3 — PRODUCT REQUIREMENTS

The application must support the following major capabilities.

FEATURE A — VIDEO IMPORT

Allow users to import videos from:

- Android file picker.
- Gallery.
- Device storage through supported Android APIs.

Support common video formats where compatible with the processing engine:

- MP4.
- MKV.
- MOV.
- AVI.
- WEBM.

Inspect each imported video and display:

- File name.
- Duration.
- File size.
- Resolution.
- Frame rate.
- Video codec.
- Audio codec.
- Audio tracks.
- Subtitle tracks.
- Aspect ratio.

Provide video preview.

Handle unsupported formats gracefully.

Never modify the original file during import.

---

FEATURE B — AI AUDIO NOISE REMOVAL

Create a dedicated professional noise-removal tool.

The purpose is to remove unwanted background noise while preserving speech clarity and natural voice quality.

Noise categories:

- Wind.
- Traffic.
- Air conditioning.
- Fans.
- Electrical hum.
- Hiss.
- Microphone noise.
- Background environmental noise.
- Unwanted ambient sound.
- Other supported noise categories.

Implement actual processing using appropriate AI models and/or DSP algorithms.

Evaluate available technologies before choosing.

Potential approaches include:

- Neural speech enhancement.
- Spectral denoising.
- Adaptive filtering.
- Noise profiling.
- Voice isolation.
- De-reverberation.
- High-pass filtering.
- Low-frequency rumble reduction.

Processing modes:

1. Light.
2. Balanced.
3. Strong.
4. Maximum.
5. Custom.

The user must be able to preview the original and processed audio.

Provide a comparison system.

Allow processing strength adjustments.

Preserve natural speech as much as possible.

Avoid:

- Metallic artifacts.
- Robotic voices.
- Excessive suppression.
- Speech distortion.
- Unnatural silence.
- Clipping.

The application must strive for maximum practical noise removal.

Do not promise 100% removal in cases where noise overlaps with speech or cannot be separated reliably.

Allow the user to:

- Replace the video's audio with cleaned audio.
- Export cleaned audio separately.
- Export WAV.
- Export MP3 where supported.

Preserve the original media.

---

FEATURE C — MP3 EXTRACTION

Extract audio from videos.

Support:

- MP3.
- WAV.
- AAC when supported.

Provide:

- 128 kbps.
- 192 kbps.
- 256 kbps.
- 320 kbps MP3 presets.
- Sample rate selection where appropriate.
- Mono/stereo options.
- Volume normalization.
- Optional noise removal.
- Audio preview.
- File size estimation.
- Export progress.
- Cancellation.

Use genuine encoding.

Validate output files.

---

FEATURE D — AI VIDEO ENHANCEMENT

Create a real video enhancement engine.

Enhancement capabilities should include, where technically feasible:

- AI upscaling.
- Resolution enhancement.
- Noise reduction.
- Sharpening.
- Detail enhancement.
- Color correction.
- Brightness correction.
- Contrast.
- Exposure.
- White balance.
- Saturation.
- Deblurring.
- Stabilization.
- Frame interpolation.

Provide output resolutions:

- Original.
- 480p.
- 720p.
- 1080p.
- 1440p.
- 2160p / 4K.

Provide processing modes:

- Fast.
- Balanced.
- High quality.
- Maximum quality.
- Custom.

Allow codec selection where supported:

- H.264.
- H.265 / HEVC.

Preserve aspect ratio by default.

Never stretch the video.

Provide optional crop controls.

Provide output quality settings.

Use hardware acceleration when compatible.

Provide graceful software fallback when possible.

Detect hardware limitations before starting expensive operations.

Warn users when the selected operation may require significant time, RAM, battery, or storage.

IMPORTANT:

Upscaling a video to 4K does not guarantee recovery of genuine source details.

Clearly distinguish:

- Resolution upscaling.
- AI detail reconstruction.
- Sharpening.
- Genuine captured detail.

Never falsely advertise an ordinary resize as AI enhancement.

---

FEATURE E — SPEECH RECOGNITION

Implement real speech-to-text.

Extract spoken words from the video's audio.

Support:

- Arabic.
- English.
- Other languages supported by the selected engine.

Capabilities:

- Automatic language detection.
- Manual language selection.
- Timestamp generation.
- Sentence segmentation.
- Punctuation.
- Silence detection.
- Long recordings.
- Speech recognition.
- Editable transcripts.

Arabic requirements:

- Correct Unicode.
- Proper RTL rendering.
- Arabic punctuation.
- Arabic text integrity.
- Dialect handling according to model capability.
- Support Arabic numerals and Western numerals.
- Avoid broken character ordering.

Where available, use models capable of providing accurate timestamps.

Never fabricate timestamps.

---

FEATURE F — MULTILINGUAL TRANSLATION

Provide translation between supported languages.

Prioritize:

- Arabic to English.
- English to Arabic.
- Arabic to other languages.
- Other languages to Arabic.
- Other supported language combinations.

Features:

- Automatic source language detection.
- Manual source selection.
- Target language selection.
- Context-aware translation.
- Sentence-level translation.
- Transcript translation.
- Subtitle translation.
- Editable translated text.
- Original and translated text comparison.

Arabic translation must be natural and context-aware.

Avoid literal translations that produce unnatural Arabic.

Preserve:

- Meaning.
- Names.
- Context.
- Sentence intent.
- Timestamp association.

Use genuine translation engines or APIs.

Never hardcode example translations as production results.

If cloud services are required:

- Disclose the requirement.
- Provide configurable providers where possible.
- Never embed secret API keys in source code.
- Explain costs.
- Explain data transmission.
- Offer offline alternatives where feasible.

---

FEATURE G — SUBTITLE GENERATION

Generate synchronized subtitles from speech.

The workflow must be:

1. Extract audio.
2. Detect spoken language.
3. Recognize speech.
4. Generate accurate timestamps.
5. Segment text into subtitle lines.
6. Translate if requested.
7. Synchronize.
8. Allow editing.
9. Preview.
10. Export.

Support:

- Original language subtitles.
- Translated subtitles.
- Dual-language subtitles.
- Arabic subtitles.
- English subtitles.
- Multiple supported languages.

The subtitle editor must support:

- Editing text.
- Start time.
- End time.
- Splitting segments.
- Merging segments.
- Synchronization adjustment.
- Adding subtitles manually.
- Deleting subtitles.
- Subtitle preview.

Do not assign timestamps based solely on word count or arbitrary duration.

Use actual speech timing.

Use forced alignment where available and technically suitable.

---

FEATURE H — SUBTITLE STYLING

Provide professional customization:

- Font family.
- Arabic font selection.
- Font size.
- Font color.
- Background.
- Background opacity.
- Outline.
- Shadow.
- Position.
- Alignment.
- Line spacing.
- Maximum lines.
- Maximum characters.
- RTL.
- LTR.

Create attractive presets.

Subtitle placement must avoid excessive obstruction of the video.

Support both:

Soft subtitles

Embed selectable subtitle tracks where supported.

Hard subtitles

Burn subtitles into video.

Allow the user to choose.

Preserve the selected audio track.

Allow cleaned audio to replace the original.

---

FEATURE I — TXT, SRT AND VTT EXPORT

Export:

- TXT.
- SRT.
- VTT.

Provide:

- Original transcript.
- Translated transcript.
- Timestamped text.
- Text without timestamps.
- Copy.
- Share.
- Save.

Validate subtitle formats.

Ensure UTF-8 encoding.

Ensure Arabic text is preserved.

Validate SRT sequence numbering and timestamp formatting.

---

FEATURE J — VIDEO COMPRESSION

Provide video compression.

Allow users to select:

- Resolution.
- Codec.
- Quality.
- Bitrate.
- Frame rate where supported.

Provide estimated output size where feasible.

Preserve aspect ratio.

Do not unnecessarily reduce audio quality.

Validate the compressed output.

---

FEATURE K — VIDEO TRIMMING

Provide:

- Timeline preview.
- Start selection.
- End selection.
- Accurate trimming.
- Preview.
- Export selected segment.

Avoid unnecessary re-encoding when a safe stream-copy operation is possible.

If frame-accurate trimming requires re-encoding, explain or handle it appropriately.

---

FEATURE L — AUDIO ENHANCEMENT

Provide:

- Volume adjustment.
- Loudness normalization.
- Peak limiting.
- Clipping protection.
- Audio fade in.
- Audio fade out.
- Silence trimming where appropriate.
- Noise reduction.
- Voice enhancement.

Allow the user to preview changes.

---

FEATURE M — PROCESSING QUEUE

Create a persistent task queue.

Each task must have:

- Unique ID.
- Input.
- Operation.
- Configuration.
- Progress.
- Status.
- Output.
- Error information.
- Start time.
- Completion time.

Task statuses:

- Pending.
- Running.
- Paused.
- Completed.
- Cancelled.
- Failed.

Provide:

- Start.
- Pause when supported.
- Resume when supported.
- Cancel.
- Retry.
- Remove.
- Queue reordering if practical.

Persist task information appropriately.

Do not claim resumability for operations that cannot safely resume.

---

FEATURE N — BATCH PROCESSING

Allow users to select multiple videos.

Apply the same operation to multiple files.

Provide:

- Batch audio extraction.
- Batch noise removal.
- Batch transcription.
- Batch translation.
- Batch subtitle generation.
- Batch compression.

Allow task-specific configuration.

Handle individual failures without unnecessarily terminating the entire batch.

---

FEATURE O — HISTORY AND PROJECTS

Provide a history screen.

Record:

- Original file.
- Operation.
- Settings.
- Output file.
- Date.
- Status.

Allow users to:

- Reopen completed projects.
- View outputs.
- Share.
- Rename.
- Delete.
- Retry failed tasks.

Use a persistent local database.

---

SECTION 4 — APPLICATION DESIGN

Create a premium Android UI.

Design:

- Modern Material 3.
- Dark theme.
- Light theme.
- Arabic interface.
- English interface.
- RTL.
- Responsive.
- Accessible.
- Smooth navigation.
- Professional icons.
- Consistent typography.
- Clean layout.
- Minimal clutter.

Main dashboard:

1. AI Video Enhancement.
2. 4K Upscaling.
3. Noise Removal.
4. Extract MP3.
5. Transcription.
6. Translation.
7. Subtitle Generator.
8. Video Compressor.
9. Video Trimmer.
10. Audio Enhancement.
11. Batch Processing.
12. Processing Queue.
13. History.
14. Settings.

Create separate screens for each major operation.

Do not overcrowd the home screen.

Provide contextual help.

Provide real progress.

Provide clear error messages.

---

SECTION 5 — TECHNICAL ARCHITECTURE

Use an appropriate modern architecture.

Preferred:

- Kotlin.
- Jetpack Compose.
- Material 3.
- MVVM.
- Clean Architecture where useful.
- Coroutines.
- Flow.
- Room.
- WorkManager.
- Media3.
- Android Storage Access Framework.
- FFmpeg-based processing where licensing and compatibility permit.
- ONNX Runtime or TensorFlow Lite where appropriate.

The exact architecture must be selected based on real project needs.

Separate modules and responsibilities:

- Presentation.
- Navigation.
- Media import.
- Media metadata.
- Video processing.
- Audio processing.
- AI models.
- Speech recognition.
- Translation.
- Subtitle engine.
- Export.
- Storage.
- Task queue.
- Settings.
- Testing.

Use clear interfaces.

Avoid unnecessary complexity.

Avoid monolithic classes.

Avoid duplicated processing logic.

---

SECTION 6 — LIBRARY AND MODEL EVALUATION

Before integrating any major processing engine:

Evaluate candidate technologies.

Consider:

- Android compatibility.
- API compatibility.
- Performance.
- CPU requirements.
- GPU support.
- RAM usage.
- APK size.
- Model size.
- Licensing.
- Redistribution rights.
- Maintenance status.
- Offline support.
- Quality.
- Arabic support.
- Accuracy.
- Battery usage.

Do not select a library based solely on popularity.

Do not use abandoned libraries without a strong reason.

Do not use models whose licenses prevent the intended distribution.

Create a documented technology decision when a choice materially affects the project.

---

SECTION 7 — HARDWARE AND RESOURCE MANAGEMENT

Android devices vary significantly.

Detect or estimate:

- RAM.
- Available storage.
- CPU capabilities.
- GPU support.
- Hardware decoding.
- Hardware encoding.
- Model compatibility.
- Device limitations.

Implement resource-aware processing.

Use:

- Streaming.
- Chunking.
- Frame-by-frame processing.
- Temporary files.
- Efficient memory management.

Never load an entire long video into RAM unnecessarily.

Handle:

- Low memory.
- Low storage.
- Thermal throttling.
- Processing time.
- Battery concerns.

Warn users when appropriate.

Offer lower-resource modes.

---

SECTION 8 — OFFLINE AND CLOUD PROCESSING

Clearly distinguish offline and online features.

Offline features must not silently require internet access.

Cloud features must disclose:

- Upload requirements.
- Processing provider.
- Data usage.
- Potential costs.
- Privacy implications.

Ask for user confirmation before uploading media.

Never upload videos automatically.

Provide local processing options wherever feasible.

Use secure API credential management.

Never store API secrets in GitHub.

---

SECTION 9 — PRIVACY AND SECURITY

Implement:

- Least-privilege permissions.
- Scoped storage.
- Secure credential storage.
- Safe temporary files.
- User-controlled exports.
- No secret keys in source.
- No unnecessary telemetry.
- No sensitive transcript logging by default.

Ensure cloud requests are secure.

Ensure user files are not accessed without authorization.

---

SECTION 10 — ERROR HANDLING

Every major operation must handle:

- Invalid input.
- Corrupted media.
- Unsupported formats.
- Missing audio.
- Missing video.
- Permission denial.
- Storage exhaustion.
- Memory exhaustion.
- Model loading errors.
- Network failure.
- API errors.
- Translation failure.
- Recognition failure.
- Export failure.
- Cancellation.
- Device incompatibility.

Errors must be understandable to users.

Provide technical details in developer logs without exposing sensitive content.

Never crash unnecessarily.

---

SECTION 11 — TESTING AND VALIDATION

Testing is mandatory.

Create:

- Unit tests.
- Integration tests.
- UI tests.
- Processing tests.
- Export tests.
- Performance tests where feasible.

Test actual media files.

Test:

- Short videos.
- Long videos.
- Large files.
- Low-resolution files.
- High-resolution files.
- Arabic speech.
- English speech.
- Multiple speakers.
- Noise.
- Silence.
- Missing audio.
- Corrupted files.
- Unsupported formats.
- Interrupted tasks.
- Low storage.
- Network loss.
- API failure.

Validate:

- Audio output.
- Video output.
- Subtitle output.
- File metadata.
- Duration.
- Audio/video synchronization.
- Subtitle synchronization.
- UTF-8.
- Arabic rendering.

Use legally distributable test media.

Never mark a test as successful without running it.

---

SECTION 12 — AUTOMATED QUALITY GATES

Each milestone must pass quality gates.

GATE A — COMPILATION

The project compiles successfully.

GATE B — TESTS

Relevant automated tests pass.

GATE C — FUNCTIONALITY

Actual functionality is verified.

GATE D — OUTPUT VALIDATION

Generated outputs are valid.

GATE E — REGRESSION

Previously completed functionality remains operational.

GATE F — DOCUMENTATION

Documentation is updated.

A milestone cannot be declared complete if any critical gate fails.

If a gate cannot be run because the environment lacks the necessary hardware, device, service, or credentials:

- Report it.
- Explain the limitation.
- Mark the gate unverified.
- Never falsely declare it passed.

---

SECTION 13 — DEVELOPMENT MILESTONES

Implement in the following order.

MILESTONE 0 — REPOSITORY AUDIT

Inspect the repository.

Identify:

- Current project state.
- Existing files.
- Existing architecture.
- Build configuration.
- Dependencies.
- Git status.
- Development environment.
- Available tools.
- Available Android SDK.
- Available devices or emulators.
- Existing tests.

Create:

- Architecture plan.
- Implementation plan.
- Technology evaluation.
- Risk register.
- Milestone plan.

Do not overwrite existing work.

MILESTONE 1 — FOUNDATION

Implement:

- Android project structure.
- Build system.
- Navigation.
- Main dashboard.
- Arabic and English.
- RTL.
- Dark theme.
- Light theme.
- Video import.
- Video preview.
- Metadata.
- Storage handling.

Build and test.

MILESTONE 2 — MEDIA ENGINE

Implement:

- Audio extraction.
- MP3.
- WAV where supported.
- Video trimming.
- Video compression.
- Metadata validation.
- Export management.

Build and test.

MILESTONE 3 — AUDIO ENHANCEMENT

Implement:

- Noise reduction.
- Speech enhancement.
- Audio preview.
- Before/after comparison.
- Volume normalization.
- Export.

Use a real engine.

Build and test.

MILESTONE 4 — SPEECH RECOGNITION

Implement:

- Speech-to-text.
- Language detection.
- Arabic.
- English.
- Timestamp generation.
- Transcript editing.
- TXT export.
- SRT export.

Build and test.

MILESTONE 5 — TRANSLATION

Implement:

- Translation engine.
- Source language.
- Target language.
- Arabic.
- Translation editing.
- Context preservation.
- Translation export.

Build and test.

MILESTONE 6 — SUBTITLE SYSTEM

Implement:

- Subtitle generation.
- Synchronization.
- Subtitle editor.
- Original subtitles.
- Translated subtitles.
- Dual subtitles.
- Styling.
- Soft subtitle export.
- Hard subtitle export.

Build and test.

MILESTONE 7 — VIDEO ENHANCEMENT

Implement:

- Resolution enhancement.
- 4K output.
- Noise reduction.
- Sharpening.
- Color correction.
- Quality presets.
- Hardware acceleration.
- Resource management.

Build and test.

MILESTONE 8 — ADVANCED FEATURES

Implement:

- Batch processing.
- Queue.
- History.
- Projects.
- Stabilization where feasible.
- Advanced audio tools.
- Additional export options.

Build and test.

MILESTONE 9 — RELEASE PREPARATION

Perform:

- Complete regression testing.
- Bug fixing.
- Performance optimization.
- UI review.
- Accessibility review.
- Security review.
- Documentation review.

Generate the APK.

Verify installation where possible.

Prepare GitHub release assets where available.

---

SECTION 14 — REQUIRED PROJECT DOCUMENTATION

Create and maintain:

README.md

Include:

- Project description.
- Features.
- Screenshots if available.
- Requirements.
- Installation.
- Build instructions.
- APK installation.
- Architecture.
- Supported formats.
- Offline capabilities.
- Online capabilities.
- AI model information.
- Limitations.
- Privacy.
- Troubleshooting.

Create additional documentation where appropriate:

- ARCHITECTURE.md.
- DEVELOPMENT.md.
- TESTING.md.
- PRIVACY.md.
- ROADMAP.md.
- TECHNOLOGY_DECISIONS.md.

Do not create meaningless documentation.

Keep documentation accurate and synchronized with implementation.

---

SECTION 15 — GITHUB ACTIONS

Where supported, create a CI workflow.

The workflow should:

1. Check out the repository.
2. Set up the required JDK.
3. Configure Android build tools.
4. Build the application.
5. Run unit tests.
6. Run static analysis where configured.
7. Generate APK artifacts.
8. Upload artifacts.

Use compatible dependency versions.

Do not expose secrets.

Do not claim CI is working until the workflow has actually executed successfully.

---

SECTION 16 — FINAL AUDIT AGENT

Before release, conduct an independent audit of the project.

Search the entire codebase for:

- TODO.
- FIXME.
- Placeholder.
- Stub.
- Mock.
- Fake.
- Hardcoded demo results.
- Unimplemented methods.
- Empty callbacks.
- Buttons without actions.
- Fake progress.
- Error suppression.
- Unused code.
- Broken dependencies.
- Security issues.
- Incorrect permissions.
- Memory leaks.
- Unhandled exceptions.
- Missing tests.

For every finding:

1. Determine whether it is legitimate.
2. Fix it where possible.
3. Re-run tests.
4. Document remaining issues.

Do not simply remove TODO comments to make the audit appear clean.

Inspect actual implementation.

---

SECTION 17 — FINAL DELIVERY REQUIREMENTS

The final deliverable must include:

- Complete source code.
- Android Studio project.
- Buildable APK.
- Successful build verification.
- Test results.
- README.
- Build instructions.
- Known limitations.
- Implemented feature list.
- Unverified feature list.
- Required external services.
- Required API credentials, if applicable.
- APK location.
- GitHub release information, if created.

Provide a final report containing:

COMPLETED

List genuinely completed features.

VERIFIED

List features that were actually tested.

NOT VERIFIED

List features that could not be tested.

BLOCKED

List genuine blockers.

KNOWN LIMITATIONS

Describe real limitations.

BUILD RESULTS

Provide actual build and test results.

APK

Provide the actual generated APK location or release link.

Never invent a download link.

Never invent test results.

Never invent a successful release.

---

SECTION 18 — AUTONOMOUS EXECUTION

Start immediately.

First inspect the repository.

Then create a concrete execution plan.

Then begin implementation.

Do not stop after the plan.

Do not ask the user to approve ordinary technical decisions.

Execute each milestone in sequence.

After each milestone:

- Build.
- Test.
- Fix.
- Validate.
- Update documentation.
- Report status.

If the environment has limitations, continue with the portions that can be completed and clearly identify what remains.

The ultimate goal is:

A genuinely functional, stable, professional Android application capable of real video processing, audio enhancement, speech recognition, translation, subtitle synchronization, and media export.

Build the product, not just the code.

Verify the product, not just the build.

Do not claim perfection.

Do not hide defects.

Deliver the best real implementation possible.
