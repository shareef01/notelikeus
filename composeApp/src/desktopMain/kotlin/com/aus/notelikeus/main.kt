package com.aus.notelikeus

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.aus.notelikeus.di.initKoin

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun main() = application {
    initKoin()
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Notelikeus",
    ) {
        val windowSizeClass = calculateWindowSizeClass()
        
        App(
            windowSizeClass = windowSizeClass,
            onShowBiometricPrompt = { _, onSuccess, _ ->
                // TODO: Implement Windows Hello
                onSuccess()
            }
        )
    }
}
