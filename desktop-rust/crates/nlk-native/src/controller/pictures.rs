//! A note's pictures: adding one (the Windows file picker, or bytes from a test), showing them on cards
//! and in the editor's strip, the full-size viewer, and removing one.
//!
//! Pixels are decoded on a worker thread and kept in memory per image; an uploaded picture this device
//! has not got yet is fetched from the attachments Worker and kept sealed for next time.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;

use notelikeus_app::{ImageView, NoteView};
use notelikeus_core::attachments::{MAX_ATTACHMENT_BYTES, can_preview};
use slint::{Image, Rgba8Pixel, SharedPixelBuffer, SharedString};

use super::{Confirm, Controller, model};
use crate::Picture;
use crate::images::{self, THUMBNAIL_SIDE, VIEWER_SIDE};

enum Pic {
    Loading,
    Ready(Image),
    Failed(String),
}

#[derive(Default)]
pub(super) struct PicturesState {
    thumbnails: RefCell<HashMap<String, Pic>>,
    /// The note whose pictures the viewer shows, its pictures, and which one.
    viewer: RefCell<Option<(String, Vec<ImageView>, usize)>>,
    /// A picture arrived while the grid was being built: build it again once that is done.
    dirty: Cell<bool>,
}

impl PicturesState {
    pub(super) fn clear(&self) {
        self.thumbnails.borrow_mut().clear();
        self.viewer.borrow_mut().take();
        self.dirty.set(false);
    }
}

fn picture(id: &str, pic: Option<&Pic>) -> Picture {
    let (image, ready, problem) = match pic {
        Some(Pic::Ready(image)) => (image.clone(), true, SharedString::new()),
        Some(Pic::Failed(problem)) => (Image::default(), false, problem.as_str().into()),
        Some(Pic::Loading) | None => (Image::default(), false, SharedString::new()),
    };
    Picture { id: id.into(), image, ready, problem }
}

fn unshowable(mime_type: &str) -> String {
    let kind = mime_type.strip_prefix("image/").unwrap_or(mime_type).to_uppercase();
    format!("{kind} pictures can't be shown here yet.")
}

impl Controller {
    /// How `image` (on note `note_id`) shows now, starting to load it if it has not been asked for.
    pub(super) fn picture(&self, note_id: &str, image: &ImageView) -> Picture {
        if let Some(pic) = self.pictures.thumbnails.borrow().get(&image.id) {
            return picture(&image.id, Some(pic));
        }
        if !can_preview(&image.mime_type) {
            let failed = Pic::Failed(unshowable(&image.mime_type));
            let shown = picture(&image.id, Some(&failed));
            self.pictures.thumbnails.borrow_mut().insert(image.id.clone(), failed);
            return shown;
        }
        self.pictures.thumbnails.borrow_mut().insert(image.id.clone(), Pic::Loading);
        let source = self.svc().image_source();
        let (note, id) = (note_id.to_string(), image.id.clone());
        let key = id.clone();
        self.in_background(
            move || source.bytes(&note, &id).and_then(|bytes| images::decode(&bytes, THUMBNAIL_SIDE)),
            move |c, decoded: Result<SharedPixelBuffer<Rgba8Pixel>, String>| {
                let pic = match decoded {
                    Ok(pixels) => Pic::Ready(Image::from_rgba8(pixels)),
                    Err(problem) => Pic::Failed(problem),
                };
                c.pictures.thumbnails.borrow_mut().insert(key, pic);
                c.pictures_arrived();
            },
        );
        // Whatever it is now: a job that ran at once has already finished.
        picture(&image.id, self.pictures.thumbnails.borrow().get(&image.id))
    }

    /// A picture finished loading: show it on its card and in the editor.
    fn pictures_arrived(&self) {
        if self.refreshing.get() {
            self.pictures.dirty.set(true);
            return;
        }
        self.refresh();
        if let Some(note) = self.editing_id().and_then(|id| self.svc().note(&id))
            && let Some(ui) = self.ui()
        {
            self.show_pictures(&ui, &note);
        }
    }

    /// Whether a picture arrived during the grid build that just ended (and clears it).
    pub(super) fn take_pictures_dirty(&self) -> bool {
        self.pictures.dirty.replace(false)
    }

    pub(super) fn mark_pictures_dirty(&self) {
        self.pictures.dirty.set(true);
    }

    pub(super) fn show_pictures(&self, ui: &crate::AppWindow, note: &NoteView) {
        ui.set_editor_pictures(model(note.images.iter().map(|image| self.picture(&note.id, image)).collect()));
    }

    /// The editor's Add image: the Windows file picker, then [`Controller::add_image_bytes`].
    pub fn pick_image(&self) {
        let Some(ui) = self.ui() else { return };
        if ui.get_editor_mode() == crate::View::Trash {
            return;
        }
        let picked = rfd::FileDialog::new()
            .set_title("Add a picture")
            .add_filter("Pictures", &["png", "jpg", "jpeg", "gif", "webp"])
            .pick_file();
        let Some(path) = picked else { return };
        let too_big = std::fs::metadata(&path).map(|m| m.len() > MAX_ATTACHMENT_BYTES as u64).unwrap_or(false);
        if too_big {
            return self.show_toast("That image is larger than 10 MB.", None, None);
        }
        match std::fs::read(&path) {
            Ok(bytes) => self.add_image_bytes(&bytes),
            Err(error) => self.show_toast(&format!("Couldn't read that file: {error}"), None, None),
        }
    }

    /// Adds a picture to the open note (a draft is saved first, so it has somewhere to go).
    pub fn add_image_bytes(&self, bytes: &[u8]) {
        let Some(ui) = self.ui() else { return };
        if self.save_editor(true).is_none() && self.editing_id().is_none() {
            return;
        }
        let Some(id) = self.editing_id() else { return };
        let added = self.svc().add_image(&id, bytes);
        match added {
            Ok(note) => {
                ui.set_editor_saved_once(true);
                self.show_note_details(&ui, &note);
                self.refresh();
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    // --- The viewer -----------------------------------------------------------------------------------

    pub fn open_picture(&self, index: usize) {
        let Some(note) = self.editing_id().and_then(|id| self.svc().note(&id)) else { return };
        if index >= note.images.len() {
            return;
        }
        *self.pictures.viewer.borrow_mut() = Some((note.id.clone(), note.images, index));
        self.show_viewer();
    }

    pub fn move_viewer(&self, step: i32) {
        if let Some((_, images, index)) = self.pictures.viewer.borrow_mut().as_mut() {
            let count = images.len() as i32;
            *index = (*index as i32 + step).rem_euclid(count.max(1)) as usize;
        }
        self.show_viewer();
    }

    fn show_viewer(&self) {
        let Some(ui) = self.ui() else { return };
        let Some((note_id, images, index)) = self.pictures.viewer.borrow().clone() else { return };
        let Some(image) = images.get(index).cloned() else { return };
        ui.set_viewer_index(index as i32);
        ui.set_viewer_count(images.len() as i32);
        ui.set_viewer_read_only(ui.get_editor_mode() == crate::View::Trash);
        ui.set_viewer_open(true);
        if !can_preview(&image.mime_type) {
            ui.set_viewer_picture(picture(&image.id, Some(&Pic::Failed(unshowable(&image.mime_type)))));
            return;
        }
        ui.set_viewer_picture(picture(&image.id, None));
        let source = self.svc().image_source();
        let shown = image.id.clone();
        self.in_background(
            move || source.bytes(&note_id, &image.id).and_then(|bytes| images::decode(&bytes, VIEWER_SIDE)),
            move |c, decoded: Result<SharedPixelBuffer<Rgba8Pixel>, String>| {
                let Some(ui) = c.ui() else { return };
                // Only if the viewer still shows this picture.
                let current =
                    c.pictures.viewer.borrow().as_ref().and_then(|(_, images, i)| images.get(*i).map(|a| a.id.clone()));
                if !ui.get_viewer_open() || current.as_deref() != Some(shown.as_str()) {
                    return;
                }
                let pic = match decoded {
                    Ok(pixels) => Pic::Ready(Image::from_rgba8(pixels)),
                    Err(problem) => Pic::Failed(problem),
                };
                ui.set_viewer_picture(picture(&shown, Some(&pic)));
            },
        );
    }

    pub fn ask_remove_picture(&self) {
        let Some((note_id, images, index)) = self.pictures.viewer.borrow().clone() else { return };
        let Some(image) = images.get(index) else { return };
        let body = if image.pending {
            "It's removed from this note."
        } else {
            "It's removed from this note on every device at the next sync."
        };
        self.ask("Remove this image?", body, "Remove", Confirm::RemoveImage(note_id, image.id.clone()));
    }

    pub(super) fn remove_picture(&self, note_id: &str, image_id: &str) {
        let removed = self.svc().remove_image(note_id, image_id);
        if let Some(ui) = self.ui() {
            ui.set_viewer_open(false);
        }
        self.pictures.viewer.borrow_mut().take();
        match removed {
            Ok(note) => {
                self.pictures.thumbnails.borrow_mut().remove(image_id);
                if let Some(ui) = self.ui() {
                    self.show_note_details(&ui, &note);
                }
                self.refresh();
                self.show_toast("Image removed", None, None);
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }
}
