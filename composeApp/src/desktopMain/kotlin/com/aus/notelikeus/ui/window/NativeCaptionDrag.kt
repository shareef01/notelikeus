package com.aus.notelikeus.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.FrameWindowScope
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** Non-client left-button-down with the caption hit-test code. */
private const val WM_NCLBUTTONDOWN = 0x00A1
private const val WM_LBUTTONUP = 0x0202
private const val WM_SYSCOMMAND = 0x0112
private const val SC_MOVE_HTCAPTION = 0xF012
private const val HTCAPTION = 0x0002
private const val DOUBLE_CLICK_MS = 400L

/** The handful of user32 entry points this file needs. */
private interface Win32 : Library {
    fun SendMessage(
        hWnd: WinDef.HWND,
        msg: Int,
        wParam: WinDef.WPARAM,
        lParam: WinDef.LPARAM
    ): WinDef.LRESULT

    fun ReleaseCapture(): Boolean

    companion object {
        val INSTANCE: Win32 = Native.load("user32", Win32::class.java)
    }
}

/**
 * Native caption behaviour for an undecorated window's top strip.
 *
 * Compose drags undecorated windows by calling [java.awt.Window.setLocation] on every mouse
 * move, which bypasses the native move loop — and with it Aero Snap. Such windows cannot be
 * docked against screen edges or corners, dragged to the top to maximize, or dragged away
 * from the top to restore. Instead, on a left press inside the strip (outside the caption
 * buttons) this sends WM_NCLBUTTONDOWN with HTCAPTION to the real HWND. The OS then owns the
 * drag: docking, snap previews and restore-by-drag all work natively, and the resulting
 * placement is reported back into the Compose [androidx.compose.ui.window.WindowState]
 * through its normal AWT listeners, so maximize/restore glyphs stay in sync.
 *
 * A fast second press counts as a double-click and toggles maximize, matching the native
 * caption. (The OS-side double-click counter never sees the synthetic message, so the timing
 * is tracked here.)
 *
 * @param zoneHeight height of the drag strip, measured from the top of the window
 * @param excludedEndWidth width at the right edge reserved for caption buttons — presses there
 *   are left to the buttons themselves
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FrameWindowScope.NativeCaptionDragSupport(
    zoneHeight: Dp,
    excludedEndWidth: Dp,
    onToggleMaximize: () -> Unit
) {
    val frame = window
    val currentOnToggleMaximize by rememberUpdatedState(onToggleMaximize)

    DisposableEffect(frame, zoneHeight, excludedEndWidth) {
        var lastPressMs = 0L
        // AWT dispatches mouse events to the deepest component (the Skia canvas), never to the
        // frame itself, so a plain MouseListener on the window would never see them. A global
        // listener sees every press; the owner-window check keeps it scoped to this window.
        val listener = AWTEventListener { event ->
            if (event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
            val mouseEvent = event as? MouseEvent ?: return@AWTEventListener
            if (mouseEvent.button != MouseEvent.BUTTON1) return@AWTEventListener
            val source = event.source as? Component ?: return@AWTEventListener
            val owner = SwingUtilities.getWindowAncestor(source)
            if (owner !== frame) return@AWTEventListener

            // Window-relative position from screen coordinates: the source component varies
            // (Skia canvas, panel or the frame itself), each with its own coordinate space.
            val frameX = mouseEvent.xOnScreen - frame.x
            val frameY = mouseEvent.yOnScreen - frame.y
            val density = frame.graphicsConfiguration.defaultTransform.scaleX
            val zoneHeightPx = (zoneHeight.value * density).toInt()
            val excludedWidthPx = (excludedEndWidth.value * density).toInt()
            if (frameY >= zoneHeightPx) return@AWTEventListener
            if (frameX >= frame.width - excludedWidthPx) return@AWTEventListener

            val now = System.currentTimeMillis()
            if (now - lastPressMs < DOUBLE_CLICK_MS) {
                lastPressMs = 0L
                currentOnToggleMaximize()
            } else {
                lastPressMs = now
                startNativeCaptionDrag(frame.windowHandle)
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}

private fun startNativeCaptionDrag(hwnd: Long) {
    val nativeWindow = WinDef.HWND(Pointer.createConstant(hwnd))
    // AWT captures the mouse on the original button-down; the move loop wants to own it.
    Win32.INSTANCE.ReleaseCapture()
    // WM_NCLBUTTONDOWN with HTCAPTION blocks until the user releases the button when the OS
    // accepts the drag. If it returns immediately the move loop never engaged, so fall back
    // to the classic WM_SYSCOMMAND/SC_MOVE entry point.
    val start = System.currentTimeMillis()
    Win32.INSTANCE.SendMessage(
        nativeWindow,
        WM_NCLBUTTONDOWN,
        WinDef.WPARAM(HTCAPTION.toLong()),
        WinDef.LPARAM(0L)
    )
    if (System.currentTimeMillis() - start < 50) {
        Win32.INSTANCE.SendMessage(
            nativeWindow,
            WM_SYSCOMMAND,
            WinDef.WPARAM(SC_MOVE_HTCAPTION.toLong()),
            WinDef.LPARAM(0L)
        )
    }
    // The caption move loop swallows the release, so AWT/Compose never learn the button came
    // up. Synthesize one, otherwise the pointer stays logically pressed and the next click
    // anywhere in the window would be treated as a continuation of this press.
    Win32.INSTANCE.SendMessage(
        nativeWindow,
        WM_LBUTTONUP,
        WinDef.WPARAM(0L),
        WinDef.LPARAM(0L)
    )
}
