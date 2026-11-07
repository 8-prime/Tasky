//! Invite codes: 8 characters from Crockford's base32 alphabet, shown as `XXXX-XXXX`.

use crate::util::sha256_hex;

const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

pub fn generate_invite_code() -> String {
    let mut bytes = [0u8; 8];
    rand::fill(&mut bytes[..]);
    let chars: String = bytes
        .iter()
        .map(|b| ALPHABET[(*b % 32) as usize] as char)
        .collect();
    format!("{}-{}", &chars[..4], &chars[4..])
}

/// Forgiving normalization: case, dashes and spaces don't matter, and characters that
/// look alike (O/0, I/L/1) are treated as the same.
pub fn normalize_invite_code(code: &str) -> String {
    code.chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        })
        .collect()
}

pub fn hash_invite_code(code: &str) -> String {
    sha256_hex(&normalize_invite_code(code))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_round_trip_through_normalization() {
        let code = generate_invite_code();
        assert_eq!(code.len(), 9);
        assert_eq!(
            hash_invite_code(&code),
            hash_invite_code(&code.to_lowercase().replace('-', " "))
        );
    }

    #[test]
    fn lookalikes_match() {
        assert_eq!(normalize_invite_code("o1l-i"), "0111");
    }
}
