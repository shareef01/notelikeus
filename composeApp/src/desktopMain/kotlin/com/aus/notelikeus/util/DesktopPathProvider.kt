package com.aus.notelikeus.util

import java.io.File

object DesktopPathProvider {
    fun getDataDirectory(): File {
        val os = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")
        
        val dir = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")
                if (appData != null) File(appData, "Notelikeus")
                else File(userHome, "AppData/Roaming/Notelikeus")
            }
            os.contains("mac") -> {
                File(userHome, "Library/Application Support/Notelikeus")
            }
            else -> { // Linux/Unix
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")
                if (xdgConfig != null) File(xdgConfig, "notelikeus")
                else File(userHome, ".config/notelikeus")
            }
        }
        
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
