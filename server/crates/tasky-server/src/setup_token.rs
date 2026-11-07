use std::sync::Mutex;

use crate::util::{random_token, sha256_hex};

/// Token required to create a new household.
///
/// With no fixed token configured, a random one is generated at startup and written
/// to the log. It works once: after a household is created, a fresh one is generated and logged.
pub struct SetupToken {
    fixed: bool,
    current: Mutex<String>,
}

impl SetupToken {
    pub fn new(fixed: Option<String>) -> Self {
        match fixed.filter(|t| !t.trim().is_empty()) {
            Some(token) => {
                tracing::info!("household creation token: using TASKY_SETUP_TOKEN (reusable)");
                SetupToken {
                    fixed: true,
                    current: Mutex::new(token),
                }
            }
            None => {
                let token = generate();
                log_token(&token);
                SetupToken {
                    fixed: false,
                    current: Mutex::new(token),
                }
            }
        }
    }

    /// Checks the token and, if it matches, uses it up (rotating it unless fixed).
    /// Call it inside the household-creation transaction so a failed creation can [`Self::restore`] it.
    pub fn consume(&self, candidate: &str) -> Option<String> {
        let mut current = self.current.lock().unwrap();
        // Compare digests so the comparison doesn't reveal a matching prefix through timing.
        if sha256_hex(candidate.trim()) != sha256_hex(&current) {
            return None;
        }
        let used = current.clone();
        if !self.fixed {
            *current = generate();
        }
        Some(used)
    }

    /// Puts back a token taken by [`Self::consume`] when household creation failed afterwards.
    pub fn restore(&self, used: String) {
        if !self.fixed {
            *self.current.lock().unwrap() = used;
        }
    }

    /// Logs the current token. Call after a successful creation so the next one is visible.
    pub fn announce(&self) {
        if !self.fixed {
            log_token(&self.current.lock().unwrap());
        }
    }

    /// Current token (used by tests).
    #[doc(hidden)]
    pub fn peek(&self) -> String {
        self.current.lock().unwrap().clone()
    }
}

fn generate() -> String {
    random_token("tsk_", 18)
}

fn log_token(token: &str) {
    tracing::info!("household creation token (single use): {token}");
}
