package com.aus.notelikeus.ui.editor

import com.aus.notelikeus.util.DateUtils

internal fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long {
    return DateUtils.combineDateAndTime(dateMillis, hour, minute)
}
