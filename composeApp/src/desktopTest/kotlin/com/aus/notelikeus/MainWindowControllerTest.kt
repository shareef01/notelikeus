package com.aus.notelikeus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** NL-01: what the main window's close button does, with and without a reminder waiting. */
class MainWindowControllerTest {

    private var pending = false
    private var exits = 0
    private var notices = 0
    private var restores = 0

    private val controller = MainWindowController(
        hasPendingReminders = { pending },
        exit = { exits++ },
        explainTray = { notices++ },
        restore = { restores++ },
    )

    @Test
    fun closingWithNothingPendingExits() {
        controller.close()

        assertEquals(1, exits)
        assertEquals(0, notices)
        assertTrue(controller.isVisible)
    }

    @Test
    fun closingWithAReminderPendingHidesToTheTrayAndExplainsOnce() {
        pending = true

        controller.close()
        assertFalse(controller.isVisible)
        assertEquals(0, exits)
        assertEquals(1, notices)

        controller.show()
        controller.close()
        assertEquals(1, notices, "the tray notice is shown once per session, not on every close")
    }

    @Test
    fun showingRestoresTheWindowAndBringsItForward() {
        pending = true
        controller.close()

        controller.show()

        assertTrue(controller.isVisible)
        assertEquals(1, restores)
        assertEquals(1L, controller.bringToFrontRequest)
    }
}
