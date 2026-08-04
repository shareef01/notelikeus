package com.aus.notelikeus.domain.platform

interface ReminderManager {
    fun scheduleReminder(noteId: Long, timestamp: Long)
    fun cancelReminder(noteId: Long)
}
