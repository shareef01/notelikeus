package com.aus.notelikeus.platform

import android.content.Context
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.ui.widget.WidgetUpdater

class AndroidWidgetManager(
    private val context: Context
) : PlatformWidgetManager {
    override fun refreshWidgets() {
        WidgetUpdater.refresh(context)
    }
}
