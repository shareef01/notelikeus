//! Turning a note's image bytes into pixels the window can draw, off the window's thread.

use std::io::Cursor;

use image::{DynamicImage, ImageDecoder, ImageReader, Limits};
use slint::{Rgba8Pixel, SharedPixelBuffer};

/// Cards and the editor's strip show at most this many pixels on the long side.
pub const THUMBNAIL_SIDE: u32 = 640;
/// The viewer shows at most this many.
pub const VIEWER_SIDE: u32 = 2400;

/// Decodes `bytes`, honors embedded EXIF orientation, and shrinks the picture to fit `max_side`.
/// Refuses pictures whose stated size would take more memory than a phone photo ever needs, so a
/// hostile file cannot exhaust it.
pub fn decode(bytes: &[u8], max_side: u32) -> Result<SharedPixelBuffer<Rgba8Pixel>, String> {
    let mut reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| "This picture can't be read.".to_string())?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(16_384);
    limits.max_image_height = Some(16_384);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits);
    let mut decoder = reader.into_decoder().map_err(|_| "This picture can't be shown here.".to_string())?;
    let orientation = decoder.orientation().ok();
    let mut picture =
        DynamicImage::from_decoder(decoder).map_err(|_| "This picture can't be shown here.".to_string())?;
    if let Some(orientation) = orientation {
        picture.apply_orientation(orientation);
    }
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

    #[test]
    fn exif_orientation_6_rotates_image_upright() {
        let raw = image::RgbImage::from_pixel(40, 20, image::Rgb([180, 90, 40]));
        let mut jpeg = Vec::new();
        raw.write_to(&mut Cursor::new(&mut jpeg), image::ImageFormat::Jpeg).unwrap();
        assert_eq!(&jpeg[..2], &[0xFF, 0xD8]);

        // Minimal APP1 EXIF segment: TIFF LE header + 1 IFD0 tag (Orientation = 6, rotate 90° CW).
        let exif_app1: &[u8] = &[
            0xFF, 0xE1, 0x00, 0x22, // APP1 + length 34
            b'E', b'x', b'i', b'f', 0x00, 0x00, // Exif\0\0
            b'I', b'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, // TIFF LE, IFD0 at 8
            0x01, 0x00, // 1 tag
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, // Orientation = 6
            0x00, 0x00, 0x00, 0x00, // next IFD = 0
        ];
        let mut with_exif = Vec::with_capacity(jpeg.len() + exif_app1.len());
        with_exif.extend_from_slice(&jpeg[..2]);
        with_exif.extend_from_slice(exif_app1);
        with_exif.extend_from_slice(&jpeg[2..]);

        let decoded = decode(&with_exif, THUMBNAIL_SIDE).unwrap();
        assert_eq!((decoded.width(), decoded.height()), (20, 40));
    }
}
