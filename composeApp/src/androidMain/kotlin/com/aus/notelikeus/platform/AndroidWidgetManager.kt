package com.aus.notelikeus.platform

import android.content.Context
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.util.WidgetUpdater

class AndroidWidgetManager(
    private val context: Context
) : PlatformWidgetManager {
    override suspend fun refreshWidgets() {
        WidgetUpdater.refresh(context)
    }
}
