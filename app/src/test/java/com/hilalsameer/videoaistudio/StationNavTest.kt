package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.StationNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** TD-027: the correction-station screen decision shared
 *  by the transcription and translation tools. The station
 *  shows only while open with a result behind it; every
 *  exit clears the open flag and returns the tool screen;
 *  an open flag without a result falls back to the tool
 *  screen, never a dead state. Host twin:
 *  vas-host-test/mt102. */
class StationNavTest {

    @Test
    fun stationShowsOnlyWhenOpenWithResult() {
        assertEquals(
            StationNav.Screen.STATION,
            StationNav.screen(stationOpen = true, hasResult = true)
        )
        assertEquals(
            StationNav.Screen.TOOL,
            StationNav.screen(stationOpen = true, hasResult = false)
        )
        assertEquals(
            StationNav.Screen.TOOL,
            StationNav.screen(stationOpen = false, hasResult = true)
        )
        assertEquals(
            StationNav.Screen.TOOL,
            StationNav.screen(stationOpen = false, hasResult = false)
        )
    }

    @Test
    fun exitFromDoneBackedStationLandsOnToolScreen() {
        var open = true
        assertEquals(StationNav.Screen.STATION, StationNav.screen(open, true))
        open = StationNav.exit(open)
        assertFalse(open)
        // The result still stands behind the tool screen:
        // the exit changed the flag, nothing else.
        assertEquals(StationNav.Screen.TOOL, StationNav.screen(open, true))
    }

    @Test
    fun exitWithoutResultFallsBackToToolHome() {
        var open = true
        // Defensive case: open flag, no result — the tool
        // screen (its home state) is what shows, before and
        // after the exit.
        assertEquals(StationNav.Screen.TOOL, StationNav.screen(open, false))
        open = StationNav.exit(open)
        assertFalse(open)
        assertEquals(StationNav.Screen.TOOL, StationNav.screen(open, false))
    }

    @Test
    fun exitIsIdempotentAndReentryWorks() {
        assertFalse(StationNav.exit(false))
        assertFalse(StationNav.exit(StationNav.exit(true)))
        // After an exit, the station can be entered again
        // from the result that still stands behind the tool.
        val reopened = true
        assertEquals(
            StationNav.Screen.STATION,
            StationNav.screen(reopened, true)
        )
    }
}
