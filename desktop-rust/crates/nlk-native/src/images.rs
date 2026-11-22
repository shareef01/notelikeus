//! Turning a note's image bytes into pixels the window can draw, off the window's thread.

use std::io::Cursor;

use image::{ImageReader, Limits};
use slint::{Rgba8Pixel, SharedPixelBuffer};

/// Cards and the editor's strip show at most this many pixels on the long side.
pub const THUMBNAIL_SIDE: u32 = 640;
/// The viewer shows at most this many.
pub const VIEWER_SIDE: u32 = 2400;

/// Decodes `bytes` and shrinks the picture to fit `max_side`. Refuses pictures whose stated size would
/// take more memory than a phone photo ever needs, so a hostile file cannot exhaust it.
pub fn decode(bytes: &[u8], max_side: u32) -> Result<SharedPixelBuffer<Rgba8Pixel>, String> {
    let mut reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| "This picture can't be read.".to_string())?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(16_384);
    limits.max_image_height = Some(16_384);
    limits.max_alloc = Some(512 * 1024 * 1024);
    reader.limits(limits);
    let picture = reader.decode().map_err(|_| "This picture can't be shown here.".to_string())?;
    let picture = if picture.width() > max_side || picture.height() > max_side {
        picture.thumbnail(max_side, max_side)
    } else {
        picture
    };
    let rgba = picture.to_rgba8();
    let (width, height) = rgba.dimensions();
    Ok(SharedPixelBuffer::clone_from_slice(rgba.as_raw(), width, height))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(width: u32, height: u32) -> Vec<u8> {
        let picture = image::RgbaImage::from_pixel(width, height, image::Rgba([20, 120, 220, 255]));
        let mut bytes = Vec::new();
        picture.write_to(&mut Cursor::new(&mut bytes), image::ImageFormat::Png).unwrap();
        bytes
    }

    #[test]
    fn pictures_are_shrunk_to_fit_and_bad_files_say_so() {
        let small = decode(&png(40, 20), THUMBNAIL_SIDE).unwrap();
        assert_eq!((small.width(), small.height()), (40, 20), "a small picture is left as it is");
        let big = decode(&png(1600, 800), THUMBNAIL_SIDE).unwrap();
        assert_eq!((big.width(), big.height()), (640, 320), "shrunk, keeping its shape");
        assert!(decode(b"not a picture", THUMBNAIL_SIDE).unwrap_err().starts_with("This picture can't"));
        let mut truncated = png(50, 50);
        truncated.truncate(truncated.len() / 2);
        assert!(decode(&truncated, THUMBNAIL_SIDE).is_err());
    }
}
