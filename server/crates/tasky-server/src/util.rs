use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

/// Random URL-safe token with the given prefix, e.g. `tsk_s_…`.
pub fn random_token(prefix: &str, bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::fill(&mut buf[..]);
    format!("{prefix}{}", URL_SAFE_NO_PAD.encode(buf))
}

pub fn sha256_hex(input: &str) -> String {
    hex::encode(Sha256::digest(input.as_bytes()))
}

pub fn parse_uuid(s: &str) -> anyhow::Result<Uuid> {
    Ok(Uuid::parse_str(s)?)
}

/// Lowercase, trimmed, single-spaced, used to compare item names.
pub fn normalize_name(name: &str) -> String {
    name.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

/// Server-side HLC for changes the server makes itself (e.g. the default list).
pub fn server_hlc() -> String {
    format!("{:012x}-0000-server", now_ms())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_names() {
        assert_eq!(normalize_name("  Cherry   Tomatoes "), "cherry tomatoes");
        assert_eq!(normalize_name("MILK"), "milk");
    }
}
