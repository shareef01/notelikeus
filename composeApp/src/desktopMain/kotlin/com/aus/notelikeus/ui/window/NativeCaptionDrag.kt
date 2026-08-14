package com.aus.notelikeus.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.FrameWindowScope
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** Non-client left-button-down with the caption hit-test code. */
private const val WM_NCLBUTTONDOWN = 0x00A1
private const val WM_LBUTTONUP = 0x0202
private const val HTCAPTION = 0x0002
private const val DOUBLE_CLICK_MS = 400L

/**
 * The handful of user32 entry points this file needs.
 *
 * [W32APIOptions.DEFAULT_OPTIONS] is not optional here. `user32.dll` exports `SendMessageA` and
 * `SendMessageW`, never a plain `SendMessage`, and a bare [Native.load] has no function mapper to
 * append the suffix — the lookup fails with `UnsatisfiedLinkError` on the first call. JNA resolves
 * per function and lazily, so nothing catches that at compile time or at load: the caption bar
 * simply throws the first time it is pressed, leaving the window undraggable. (`ReleaseCapture`
 * has no A/W variants and resolves either way, which is what makes the failure partial and
 * confusing rather than obvious.) [StdCallLibrary] pairs with it for the __stdcall convention
 * these functions use on 32-bit Windows; on x64 there is only one convention, but declaring it
 * keeps the interface honest.
 */
private interface Win32 : StdCallLibrary {
    fun SendMessage(
        hWnd: WinDef.HWND,
        msg: Int,
        wParam: WinDef.WPARAM,
        lParam: WinDef.LPARAM
    ): WinDef.LRESULT

    fun ReleaseCapture(): Boolean

    companion object {
        val INSTANCE: Win32 =
            Native.load("user32", Win32::class.java, W32APIOptions.DEFAULT_OPTIONS)
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
            // Compared in user space, with no density multiply. Every value on both sides of these
            // comparisons is already user-space: AWT reports component bounds and pointer positions
            // in user space on Java 9+ Windows, and Compose lays a 40.dp bar out to 40 user-space
            // units whatever the display scale. Scaling the dp values by
            // `defaultTransform.scaleX` applied the display scale a second time, so at 150% the
            // 40dp strip was hit-tested as 60 units tall (starting drags on content below the bar)
            // and the 138dp button exclusion as 207 (swallowing presses that should reach them).
            val zoneHeightUnits = zoneHeight.value
            val excludedWidthUnits = excludedEndWidth.value
            if (frameY >= zoneHeightUnits) return@AWTEventListener
            if (frameX >= frame.width - excludedWidthUnits) return@AWTEventListener

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
    // WM_NCLBUTTONDOWN with HTCAPTION runs the OS move loop inline and returns once the user
    // releases the button. Returning quickly is the correct outcome for a plain click, not a
    // signal that the drag failed: an earlier WM_SYSCOMMAND/SC_MOVE fallback keyed off a 50ms
    // threshold started a *second* move loop that tracks the pointer with no button held, so a
    // click on the caption left the window following the cursor until the user clicked again or
    // pressed Escape.
    Win32.INSTANCE.SendMessage(
        nativeWindow,
        WM_NCLBUTTONDOWN,
        WinDef.WPARAM(HTCAPTION.toLong()),
        WinDef.LPARAM(0L)
    )
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
