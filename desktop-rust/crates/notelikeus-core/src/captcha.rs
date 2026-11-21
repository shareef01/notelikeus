//! The Turnstile step the hosted project requires before it emails a sign-in code (D30).
//!
//! There is no Turnstile SDK for a desktop app, so, like Android, the client opens the web app's
//! challenge page (`web/captcha.html`) with `?return=app&state=<random>` and catches the page's final
//! navigation to `notelikeus-captcha://token?state=…&token=…`. The contract is
//! `web/src/lib/auth/captchaReturn.ts`; the Kotlin twin is `captchaTokenFromReturnQuery`.
//!
//! The state is what makes the token trustworthy: a page this app did not open cannot know it, so it
//! cannot hand the app a token of its choosing.

use crate::Error;
use crate::http::checked_base_url;

/// Must match `APP_RETURN_SCHEME` in `web/src/lib/auth/captchaReturn.ts`.
pub const RETURN_SCHEME: &str = "notelikeus-captcha";
/// A Turnstile token is about 2 KB; anything far larger is not one.
const MAX_TOKEN_LEN: usize = 4096;

/// 24 random bytes, URL-safe base64 without padding: 32 characters, inside the 16 to 128 of
/// `[A-Za-z0-9_-]` the challenge page accepts.
pub fn new_state() -> Result<String, Error> {
    Ok(base64url(&crate::crypto::random_bytes::<24>()?))
}

fn base64url(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = chunk.iter().enumerate().fold(0u32, |acc, (i, b)| acc | (u32::from(*b) << (16 - 8 * i)));
        for i in 0..=chunk.len() {
            out.push(ALPHABET[((n >> (18 - 6 * i)) & 63) as usize] as char);
        }
    }
    out
}

/// The challenge page URL, if it is HTTPS (or plain HTTP to this machine, for a local test page).
pub fn checked_page_url(page_url: &str) -> Result<String, String> {
    checked_base_url(page_url)
}

/// `page_url` with the query that asks the page to hand the token back to this app.
pub fn challenge_url(page_url: &str, state: &str) -> String {
    let separator = if page_url.contains('?') { '&' } else { '?' };
    format!("{page_url}{separator}return=app&state={state}")
}

/// Whether `url` is the page's hand-back navigation (whatever it carries).
pub fn is_return_url(url: &str) -> bool {
    url.get(..RETURN_SCHEME.len() + 1).is_some_and(|prefix| prefix.eq_ignore_ascii_case(&format!("{RETURN_SCHEME}:")))
}

fn percent_decode(value: &str) -> Option<String> {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                let hex = value.get(i + 1..i + 3)?;
                out.push(u8::from_str_radix(hex, 16).ok()?);
                i += 3;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8(out).ok()
}

/// The token from the page's hand-back URL, or None unless it is exactly
/// `notelikeus-captcha://token?…` with `state` equal to `expected_state` and a plausible token.
pub fn token_from_return_url(url: &str, expected_state: &str) -> Option<String> {
    if !is_return_url(url) || expected_state.is_empty() {
        return None;
    }
    let rest = &url[RETURN_SCHEME.len() + 1..];
    let query = rest.strip_prefix("//token?")?;
    let query = query.split('#').next().unwrap_or_default();
    let (mut state, mut token) = (None, None);
    for pair in query.split('&') {
        let (key, value) = pair.split_once('=')?;
        match percent_decode(key)?.as_str() {
            "state" if state.is_none() => state = Some(percent_decode(value)?),
            "token" if token.is_none() => token = Some(percent_decode(value)?),
            // A repeated key is not a request this app made.
            "state" | "token" => return None,
            _ => {}
        }
    }
    let token = token?;
    let plausible = !token.is_empty() && token.len() <= MAX_TOKEN_LEN && token.bytes().all(|b| b.is_ascii_graphic());
    (state.as_deref() == Some(expected_state) && plausible).then_some(token)
}
