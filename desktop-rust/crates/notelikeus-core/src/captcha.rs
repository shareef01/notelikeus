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
    token_from_query(query.split('#').next().unwrap_or_default(), expected_state)
}

/// The token from a hand-back query string (`state=…&token=…`), when its state is `expected_state`:
/// Kotlin `captchaTokenFromReturnQuery`.
pub fn token_from_query(query: &str, expected_state: &str) -> Option<String> {
    if expected_state.is_empty() {
        return None;
    }
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

// --- The desktop's loopback return ------------------------------------------------------------------

/// Desktop's way back (Kotlin `LoopbackCaptchaTokenProvider`): the system browser opens the challenge
/// page with `?port=<n>&state=<s>`, and the page sends the token to `http://127.0.0.1:<n>/?state=&token=`.
///
/// It listens on 127.0.0.1 only, on a port the OS picks. The first request to `/` with the right state
/// yields the token. A request with a wrong or missing state gets a 400 and the listener keeps waiting,
/// so a stray visit does not spoil the check, but after [`MAX_BAD_ATTEMPTS`] of them it fails.
pub struct Loopback {
    listener: std::net::TcpListener,
    state: String,
}

/// Wrong-state requests tolerated before the check fails.
pub const MAX_BAD_ATTEMPTS: u32 = 5;

impl Loopback {
    pub fn start() -> Result<Loopback, String> {
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0))
            .map_err(|e| format!("Couldn't start the security check: {e}"))?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let state = new_state().map_err(|e| e.to_string())?;
        Ok(Loopback { listener, state })
    }

    pub fn port(&self) -> u16 {
        self.listener.local_addr().map(|a| a.port()).unwrap_or(0)
    }

    /// The challenge page asked to hand its token back to this listener.
    pub fn challenge_url(&self, page_url: &str) -> String {
        let separator = if page_url.contains('?') { '&' } else { '?' };
        format!("{page_url}{separator}port={}&state={}", self.port(), self.state)
    }

    /// Waits for the token until `timeout`, or until `cancelled` turns true.
    pub fn wait(
        &self,
        timeout: std::time::Duration,
        cancelled: &std::sync::atomic::AtomicBool,
    ) -> Result<String, String> {
        use std::sync::atomic::Ordering;
        let deadline = std::time::Instant::now() + timeout;
        let mut bad = 0;
        loop {
            if cancelled.load(Ordering::Relaxed) {
                return Err("The security check was cancelled.".into());
            }
            if std::time::Instant::now() >= deadline {
                return Err("The security check in your browser timed out. Try again.".into());
            }
            match self.listener.accept() {
                Ok((stream, _)) => match self.answer(stream) {
                    Answer::Token(token) => return Ok(token),
                    Answer::WrongState => {
                        bad += 1;
                        if bad >= MAX_BAD_ATTEMPTS {
                            return Err("The security check didn't go through. Try again.".into());
                        }
                    }
                    Answer::Ignored => {}
                },
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
                Err(e) => return Err(format!("The security check failed: {e}")),
            }
        }
    }

    fn answer(&self, mut stream: std::net::TcpStream) -> Answer {
        use std::io::{Read, Write};
        let _ = stream.set_nonblocking(false);
        let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(5)));
        let mut request = Vec::new();
        let mut buffer = [0u8; 2048];
        // The request line and headers; a GET has no body. Capped, since anything may connect.
        while !request.windows(4).any(|w| w == b"\r\n\r\n") && request.len() < 16 * 1024 {
            match stream.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(n) => request.extend_from_slice(&buffer[..n]),
            }
        }
        let text = String::from_utf8_lossy(&request);
        let line = text.lines().next().unwrap_or_default();
        let mut parts = line.split(' ');
        let (method, target) = (parts.next().unwrap_or_default(), parts.next().unwrap_or_default());
        let (path, query) = target.split_once('?').unwrap_or((target, ""));
        // The browser may also ask for /favicon.ico; only the page's redirect to / counts.
        let (status, message, answer) = if method != "GET" || path != "/" {
            ("404 Not Found", "Not found.", Answer::Ignored)
        } else if let Some(token) = token_from_query(query, &self.state) {
            ("200 OK", "Done. You can close this tab and return to Notelikeus.", Answer::Token(token))
        } else {
            (
                "400 Bad Request",
                "This security check is not the one Notelikeus started. Return to the app and try again.",
                Answer::WrongState,
            )
        };
        let body = format!(
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Notelikeus</title></head>\
             <body style=\"font-family:system-ui,sans-serif;text-align:center;padding:3rem\"><p>{message}</p></body></html>"
        );
        let _ = write!(
            stream,
            "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\n\
             Referrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n\
             Connection: close\r\n\r\n{body}",
            body.len()
        );
        answer
    }
}

enum Answer {
    Token(String),
    WrongState,
    Ignored,
}

#[cfg(test)]
mod loopback_tests {
    use super::*;
    use std::io::{Read, Write};
    use std::sync::atomic::AtomicBool;

    fn get(port: u16, target: &str) -> String {
        let mut stream = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        write!(stream, "GET {target} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").unwrap();
        let mut reply = String::new();
        stream.read_to_string(&mut reply).unwrap();
        reply
    }

    #[test]
    fn the_token_comes_back_only_with_the_right_state() {
        let loopback = Loopback::start().unwrap();
        let port = loopback.port();
        let url = loopback.challenge_url("https://notelikeus.example/captcha.html");
        assert!(url.starts_with(&format!("https://notelikeus.example/captcha.html?port={port}&state=")));
        let state = url.split("state=").nth(1).unwrap().to_string();
        let client = std::thread::spawn(move || {
            let favicon = get(port, "/favicon.ico");
            let wrong = get(port, "/?state=someone-elses-state-value&token=forged");
            let right = get(port, &format!("/?state={state}&token=tok%2Ben"));
            (favicon, wrong, right)
        });
        let token = loopback.wait(std::time::Duration::from_secs(10), &AtomicBool::new(false)).unwrap();
        let (favicon, wrong, right) = client.join().unwrap();
        assert_eq!(token, "tok+en", "percent-decoded, as the other clients do");
        assert!(favicon.starts_with("HTTP/1.1 404"));
        assert!(wrong.starts_with("HTTP/1.1 400"));
        assert!(right.starts_with("HTTP/1.1 200") && right.contains("close this tab"));
    }

    #[test]
    fn too_many_wrong_states_fail_and_cancel_stops_waiting() {
        let loopback = Loopback::start().unwrap();
        let port = loopback.port();
        let client = std::thread::spawn(move || {
            for _ in 0..MAX_BAD_ATTEMPTS {
                get(port, "/?state=wrong-state-value-0123456&token=x");
            }
        });
        let result = loopback.wait(std::time::Duration::from_secs(10), &AtomicBool::new(false));
        client.join().unwrap();
        assert_eq!(result.unwrap_err(), "The security check didn't go through. Try again.");

        let result = Loopback::start().unwrap().wait(std::time::Duration::from_secs(10), &AtomicBool::new(true));
        assert_eq!(result.unwrap_err(), "The security check was cancelled.");
        let result = Loopback::start().unwrap().wait(std::time::Duration::from_millis(100), &AtomicBool::new(false));
        assert!(result.unwrap_err().contains("timed out"));
    }
}
