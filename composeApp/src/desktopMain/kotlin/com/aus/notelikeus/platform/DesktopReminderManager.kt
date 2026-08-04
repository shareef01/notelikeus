package com.aus.notelikeus.platform

import com.aus.notelikeus.domain.platform.ReminderManager

class DesktopReminderManager : ReminderManager {
    override fun scheduleReminder(noteId: Long, timestamp: Long) {
        // TODO: Implement desktop notifications
    }

    override fun cancelReminder(noteId: Long) {
    }
}
