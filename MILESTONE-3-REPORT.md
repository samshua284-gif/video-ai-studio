# Milestone 3 Report — Audio Enhancement & Noise Removal

Status: **COMPLETE in 0.4.0**, with the neural engine replaced
during the milestone (TD-004). Device verdict on the GTCRN mode is
the one gate that remains the owner's, as with every milestone
(no emulator exists in the development environment).

## What shipped

- **Noise removal screen** with four modes: three spectral modes
  (Light / Balanced / Strong — FFmpeg `afftdn` chains, TD-003) and
  a neural mode. Start-mute control (0–2 s, full mute + 0.15 s
  ramp), soften-start fade, and — for the spectral modes — a
  dry/wet clarity blend with measured 10 ms / 25 ms path alignment.
- **Enhance screen**: high-pass + presence EQ + loudnorm polish,
  with an RNNoise pre-clean for noisy sources and a residue gate
  for already-cleaned ones.
- Before/after compare player, export validation with the five
  checks itemized, save-to-Gallery flow (0.3.2 owner requirement).

## Field defects found by the owner and fixed (all 2026-11-25)

1. **Strong mode failed** — `afftdn` noise-floor −14 dB is outside
   FFmpeg 6.0's [−80, −20] range. Fixed (0.3.1), ranges now in
   TD-003.
2. **Enhance failed on a real file** — an `aresample` tail after
   `loudnorm` broke channel-layout negotiation. Removed; loudnorm
   stays last (0.3.4).
3. **Validator false negative** — the sample probe read track 0
   only; phone files often carry a metadata track there. The probe
   now iterates all tracks, and the result card itemizes the five
   checks (0.3.8).
4. **Start thump in neural denoise** — a recording-start event in
   the source itself, exposed by cleaning. A 0.8 s fade was shipped
   as the fix (0.3.7) and **the owner corrected the record**: the
   sound persisted; the fade only softened its attack. The real
   fix is the start-mute (0.3.12/0.3.13), host-measured at digital
   silence inside the window, and the owner confirmed the thump
   gone by ear. Lesson recorded: a process-success message is not
   an artifact-success verdict.
5. **Enhance distortion on an already-denoised file** — loudnorm
   lifted the neural residue in the silences (−62.6 dB). A gate
   before the polish measured the residue at −86.3 dB with speech
   lift unchanged; shipped always-on (0.3.10). A start fade after
   loudnorm followed (0.3.11) after loudnorm's start surge was
   measured resurrecting a buried thump (−44.6 dB → −1.5 dB plain,
   −15.7 dB faded).

## The engine replacement (0.4.0, TD-004)

The owner's remaining complaint — smeared articulation across
whole utterances in neural mode — is RNNoise v0.1's ceiling. The
clarity blend built to compensate was **field-rejected** (it
re-admits the dry path's noise by construction). The neural engine
was therefore replaced with **GTCRN (2024)** via ONNX Runtime,
measured on the build host against RNNoise on real speech before
integration: LSD-to-clean 11.50 vs 13.11, noise tail −61.0 dB vs
−28.2 dB. The app's own STFT port reproduced the reference at
LSD 11.46 and matched its golden STFT frame to 1.0e-5. The blend
control is hidden in neural mode (GTCRN needs no crutch) and kept
for the spectral modes, where it was measured constructive.

## Open items carried forward (honest)

- **Light spectral mode**: a "not saved" report from 0.3 was never
  re-tested with a full result-card screenshot; possibly the same
  validator defect fixed in 0.3.8 — unproven either way.
- **Enhance** never received a clean end-to-end field PASS on a
  clean input file (its field history is entangled with the
  denoise defects above).
- A "did not pass validation" card in the owner's 0.3.14
  screenshot could not be attributed to a specific run/check from
  the cropped image.
- The `denoiseNeural` (RNNoise) command builder remains in the
  codebase, unreferenced by the UI; removal is deferred to the
  final audit (Command 7).
- The CI debug-signing defect (each run signs with a fresh key;
  reinstall requires uninstall first) remains scheduled for the
  release-keystore work before Milestone 9.
