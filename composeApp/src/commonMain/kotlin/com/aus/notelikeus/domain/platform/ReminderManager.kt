package com.aus.notelikeus.domain.platform

/**
 * What the platform will actually do with a reminder that is scheduled right now.
 *
 * The editor confirms a reminder to the user the moment the note carrying it is saved, and that
 * confirmation is a promise about delivery. Scheduling succeeds regardless — [ReminderManager]
 * hands the alarm to the OS either way — so nothing in the return value of
 * [ReminderManager.scheduleReminder] can tell the two apart. This is what the UI reads instead.
 */
enum class ReminderDelivery {
    /** Fires at the requested moment, and the user will see it. */
    Exact,

    /**
     * Will be seen, but within a window rather than to the second.
     *
     * Android 12+ without `SCHEDULE_EXACT_ALARM`, which this app deliberately does not request
     * (see the note in `androidApp/src/main/AndroidManifest.xml`).
     */
    Approximate,

    /**
     * Will not reach the user at all: notifications are turned off for the app.
     *
     * The alarm still fires and the reminder is still stored, so this is not a scheduling
     * failure — it is a delivery one, and the only place it can be noticed is here.
     */
    Blocked,
}

interface ReminderManager {
    fun scheduleReminder(noteId: Long, timestamp: Long)
    fun cancelReminder(noteId: Long)

    /**
     * How a reminder scheduled now would be delivered.
     *
     * Defaults to [ReminderDelivery.Exact] so a platform with no such distinction — and the test
     * doubles — need say nothing. Android overrides it, because Android is where a reminder can
     * be silently swallowed.
     */
    fun reminderDelivery(): ReminderDelivery = ReminderDelivery.Exact
}
