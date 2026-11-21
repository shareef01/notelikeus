//! Encryption at rest: AES-256-GCM with a per-install data key, and the key wrapped by Windows DPAPI
//! for the current user (the same protection the Kotlin desktop client gives its SQLCipher key).
//!
//! Pure Rust (RustCrypto `aes-gcm`), so it builds without a C compiler.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};

use crate::Error;

pub const KEY_LEN: usize = 32;
const NONCE_LEN: usize = 12;

/// Wraps and unwraps the data key for storage on disk.
pub trait KeyProtector {
    fn protect(&self, key: &[u8; KEY_LEN]) -> Result<Vec<u8>, Error>;
    fn unprotect(&self, blob: &[u8]) -> Result<[u8; KEY_LEN], Error>;
}

/// For tests only: stores the key unwrapped. Never used by a real build of the client.
pub struct PlaintextKeyProtectorForTests;

impl KeyProtector for PlaintextKeyProtectorForTests {
    fn protect(&self, key: &[u8; KEY_LEN]) -> Result<Vec<u8>, Error> {
        Ok(key.to_vec())
    }
    fn unprotect(&self, blob: &[u8]) -> Result<[u8; KEY_LEN], Error> {
        blob.try_into().map_err(|_| Error::Crypto("key blob has the wrong length".into()))
    }
}

pub fn random_bytes<const N: usize>() -> Result<[u8; N], Error> {
    let mut out = [0u8; N];
    getrandom::getrandom(&mut out).map_err(|e| Error::Crypto(format!("no randomness: {e}")))?;
    Ok(out)
}

/// `nonce || ciphertext+tag`. `aad` binds the blob to its owner, so one account's file copied over
/// another's fails to open instead of being read as that account's notes.
pub fn seal(key: &[u8; KEY_LEN], aad: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, Error> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| Error::Crypto("bad key".into()))?;
    let nonce_bytes = random_bytes::<NONCE_LEN>()?;
    let sealed = cipher
        .encrypt(Nonce::from_slice(&nonce_bytes), Payload { msg: plaintext, aad })
        .map_err(|_| Error::Crypto("encryption failed".into()))?;
    let mut out = Vec::with_capacity(NONCE_LEN + sealed.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&sealed);
    Ok(out)
}

pub fn open(key: &[u8; KEY_LEN], aad: &[u8], blob: &[u8]) -> Result<Vec<u8>, Error> {
    if blob.len() < NONCE_LEN + 16 {
        return Err(Error::Crypto("ciphertext too short".into()));
    }
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| Error::Crypto("bad key".into()))?;
    let (nonce, body) = blob.split_at(NONCE_LEN);
    cipher
        .decrypt(Nonce::from_slice(nonce), Payload { msg: body, aad })
        .map_err(|_| Error::Crypto("decryption failed: wrong key, wrong owner, or a damaged file".into()))
}

#[cfg(windows)]
pub use dpapi::DpapiKeyProtector;

#[cfg(windows)]
mod dpapi {
    use super::{KEY_LEN, KeyProtector};
    use crate::Error;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    };

    /// Current-user DPAPI. Only this Windows account on this machine can unwrap the key.
    pub struct DpapiKeyProtector;

    /// Fixed secondary entropy: another app running as the same user cannot unwrap the blob by
    /// calling DPAPI without also knowing it. Not a secret, only a namespace.
    const ENTROPY: &[u8] = b"notelikeus-rust-experimental/data-key/v1";

    fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
        CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr() as *mut u8 }
    }

    fn take(out: CRYPT_INTEGER_BLOB) -> Vec<u8> {
        // SAFETY: DPAPI returned a buffer of cbData bytes that we own and free with LocalFree.
        unsafe {
            let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
            LocalFree(out.pbData as _);
            bytes
        }
    }

    impl KeyProtector for DpapiKeyProtector {
        fn protect(&self, key: &[u8; KEY_LEN]) -> Result<Vec<u8>, Error> {
            let input = blob(key);
            let entropy = blob(ENTROPY);
            let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
            // SAFETY: every pointer refers to a live blob for the duration of the call.
            let ok = unsafe {
                CryptProtectData(
                    &input,
                    std::ptr::null(),
                    &entropy,
                    std::ptr::null(),
                    std::ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut out,
                )
            };
            if ok == 0 {
                return Err(Error::Crypto(format!("CryptProtectData failed: {}", std::io::Error::last_os_error())));
            }
            Ok(take(out))
        }

        fn unprotect(&self, data: &[u8]) -> Result<[u8; KEY_LEN], Error> {
            let input = blob(data);
            let entropy = blob(ENTROPY);
            let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
            // SAFETY: as above.
            let ok = unsafe {
                CryptUnprotectData(
                    &input,
                    std::ptr::null_mut(),
                    &entropy,
                    std::ptr::null(),
                    std::ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut out,
                )
            };
            if ok == 0 {
                return Err(Error::Crypto(format!("CryptUnprotectData failed: {}", std::io::Error::last_os_error())));
            }
            let mut bytes = take(out);
            let key: Result<[u8; KEY_LEN], _> = bytes.as_slice().try_into();
            bytes.iter_mut().for_each(|b| *b = 0);
            key.map_err(|_| Error::Crypto("unwrapped key has the wrong length".into()))
        }
    }
}
