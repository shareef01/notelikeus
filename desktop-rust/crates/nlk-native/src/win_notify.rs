//! Small Windows notification-area balloons for reminders. The payload is deliberately generic so
//! note text never appears on a lock screen or shared display.

use std::mem::{size_of, zeroed};

use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use slint::ComponentHandle;
use windows_sys::Win32::Foundation::HWND;
use windows_sys::Win32::UI::Shell::{
    NIF_ICON, NIF_INFO, NIF_TIP, NIIF_INFO, NIM_ADD, NIM_DELETE, NIM_MODIFY, NOTIFYICONDATAW, Shell_NotifyIconW,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{IDI_INFORMATION, LoadIconW};

const REMINDER_ICON_ID: u32 = 0x4E4C4B;

fn copy_text(buffer: &mut [u16], text: &str) {
    buffer.fill(0);
    let capacity = buffer.len().saturating_sub(1);
    for (slot, unit) in buffer.iter_mut().take(capacity).zip(text.encode_utf16()) {
        *slot = unit;
    }
}

fn icon_data(hwnd: HWND) -> NOTIFYICONDATAW {
    // The Windows shell structure is a C ABI record whose size is selected by `cbSize`.
    let mut data: NOTIFYICONDATAW = unsafe { zeroed() };
    data.cbSize = size_of::<NOTIFYICONDATAW>() as u32;
    data.hWnd = hwnd;
    data.uID = REMINDER_ICON_ID;
    data
}

pub fn show_notice_for_window(ui: &crate::AppWindow, title: &str, body: &str) -> Option<HWND> {
    let window_handle = ui.window().window_handle();
    let handle = window_handle.window_handle().ok()?;
    let RawWindowHandle::Win32(raw_handle) = handle.as_raw() else { return None };
    let hwnd = raw_handle.hwnd.get() as HWND;
    let mut data = icon_data(hwnd);
    data.uFlags = NIF_ICON | NIF_TIP;
    // SAFETY: IDI_INFORMATION is a Windows stock icon and does not need to be destroyed.
    data.hIcon = unsafe { LoadIconW(std::ptr::null_mut(), IDI_INFORMATION) };
    copy_text(&mut data.szTip, "Notelikeus");

    // Reuse this app-owned notification icon if an earlier notice is already visible.
    unsafe { Shell_NotifyIconW(NIM_DELETE, &data) };
    if unsafe { Shell_NotifyIconW(NIM_ADD, &data) } == 0 {
        return None;
    }

    data.uFlags = NIF_INFO;
    copy_text(&mut data.szInfoTitle, title);
    copy_text(&mut data.szInfo, body);
    data.dwInfoFlags = NIIF_INFO;
    if unsafe { Shell_NotifyIconW(NIM_MODIFY, &data) } == 0 {
        unsafe { Shell_NotifyIconW(NIM_DELETE, &data) };
        return None;
    }
    Some(hwnd)
}

pub fn show_for_window(ui: &crate::AppWindow) -> Option<HWND> {
    show_notice_for_window(ui, "Reminder", "You have a note reminder")
}

pub fn remove(hwnd: HWND) {
    let data = icon_data(hwnd);
    unsafe { Shell_NotifyIconW(NIM_DELETE, &data) };
}
