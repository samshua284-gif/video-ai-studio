package com.hilalsameer.videoaistudio.media

/**
 * 0.10.2 (TD-027) — the correction-station screen decision,
 * shared by the transcription tool (0.10.0) and the
 * translation tool (0.10.1).
 *
 * A correction station is a state branch inside its tool's
 * screen, not a navigation destination of its own. Two
 * field failures followed from that shape, and this object
 * is the contract that closes both:
 *
 * 1. WHICH SCREEN SHOWS. The station shows only while it
 *    is open AND a finished result stands behind it. The
 *    decision is taken before the tool's own header is
 *    composed, so while the station is open the tool's
 *    dashboard-bound back button is not on screen at all —
 *    the only back in front of the owner is the station's.
 *    An open flag without a result behind it (a defensive
 *    case; the entry buttons only exist on a result) falls
 *    back to the tool screen itself: never a dead screen,
 *    never a crash.
 *
 * 2. HOW THE STATION EXITS. Every exit path — the save
 *    button, the station's own back, and the system back
 *    (a BackHandler in each tool screen) — is the same
 *    transition: the open flag clears and the tool screen
 *    returns, showing the result the service already
 *    holds. The exit never touches the result, the draft
 *    or the memory; those contracts live in the services
 *    (TD-025, TD-026) and are unchanged.
 *
 * Both tool screens call these two functions for their
 * branch decision and for every exit transition, so the
 * host harness (vas-host-test/mt102) tests the exact
 * decision the shipped UI makes.
 */
object StationNav {

    /** Which of the tool's two screens is composed. */
    enum class Screen { STATION, TOOL }

    /**
     * The screen decision: the station owns the screen
     * only while it is open and a result stands behind it;
     * in every other state the tool's own screen shows
     * (its result section when a result exists, its home
     * state when none does).
     */
    fun screen(stationOpen: Boolean, hasResult: Boolean): Screen =
        if (stationOpen && hasResult) Screen.STATION else Screen.TOOL

    /**
     * The station-exit transition, applied to the open
     * flag: after any exit the flag is cleared, so
     * [screen] returns the tool screen. Idempotent —
     * exiting an already-closed station changes nothing.
     */
    fun exit(stationOpen: Boolean): Boolean = false
}
