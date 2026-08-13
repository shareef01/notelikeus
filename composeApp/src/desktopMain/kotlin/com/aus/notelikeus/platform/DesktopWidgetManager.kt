package com.aus.notelikeus.platform

import com.aus.notelikeus.domain.platform.PlatformWidgetManager

class DesktopWidgetManager : PlatformWidgetManager {
    override suspend fun refreshWidgets() {
        // No widgets on desktop yet
    }
}
