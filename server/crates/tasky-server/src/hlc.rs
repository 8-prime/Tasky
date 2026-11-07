//! Hybrid logical clock timestamps used for last-writer-wins.
//!
//! Format: `<12 lowercase hex: unix ms>-<4 lowercase hex: counter>-<node id>`,
//! e.g. `0193a4f1c2d0-0000-7f3a9c01e5b24d88`. The ms and counter parts are fixed width,
//! so plain string comparison orders timestamps by (ms, counter, node).

use tasky_api::RejectCode;

/// Ops stamped further than this into the future (by server time) are rejected, so a phone with
/// a wrong clock can't make its edits win forever.
pub const MAX_SKEW_MS: i64 = 5 * 60 * 1000;

pub fn validate(hlc: &str, now_ms: i64) -> Result<(), (RejectCode, String)> {
    let invalid = || (RejectCode::InvalidHlc, format!("malformed hlc {hlc:?}"));
    let mut parts = hlc.splitn(3, '-');
    let (ms, counter, node) = match (parts.next(), parts.next(), parts.next()) {
        (Some(ms), Some(c), Some(n)) => (ms, c, n),
        _ => return Err(invalid()),
    };
    let is_hex = |s: &str| {
        s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    };
    if ms.len() != 12 || !is_hex(ms) || counter.len() != 4 || !is_hex(counter) {
        return Err(invalid());
    }
    if node.is_empty()
        || node.len() > 32
        || !node
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
    {
        return Err(invalid());
    }
    let ms = i64::from_str_radix(ms, 16).map_err(|_| invalid())?;
    if ms > now_ms + MAX_SKEW_MS {
        return Err((
            RejectCode::ClockSkew,
            format!("hlc is {}s ahead of server time", (ms - now_ms) / 1000),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 0x0193a4f1c2d0;

    #[test]
    fn accepts_valid() {
        assert!(validate("0193a4f1c2d0-0000-7f3a9c01e5b24d88", NOW).is_ok());
        assert!(validate("000000000000-ffff-a", NOW).is_ok());
    }

    #[test]
    fn rejects_malformed() {
        for bad in [
            "",
            "0193a4f1c2d0-0000",
            "0193A4F1C2D0-0000-node",
            "193a4f1c2d0-0000-node",
            "0193a4f1c2d0-000-node",
            "0193a4f1c2d0-0000-",
            "0193a4f1c2d0-0000-No-de",
        ] {
            assert_eq!(
                validate(bad, NOW).unwrap_err().0,
                RejectCode::InvalidHlc,
                "{bad}"
            );
        }
    }

    #[test]
    fn rejects_future() {
        let future = format!("{:012x}-0000-n", NOW + MAX_SKEW_MS + 1);
        assert_eq!(validate(&future, NOW).unwrap_err().0, RejectCode::ClockSkew);
    }

    #[test]
    fn string_order_matches_time_order() {
        let a = format!("{:012x}-{:04x}-n", 255, 3);
        let b = format!("{:012x}-{:04x}-n", 256, 0);
        let c = format!("{:012x}-{:04x}-n", 256, 1);
        assert!(a < b && b < c);
    }
}
