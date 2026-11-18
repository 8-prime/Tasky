use std::net::SocketAddr;

use clap::Args;

/// Server settings (command-line flags or `TASKY_*` environment variables).
#[derive(Debug, Clone, Args)]
pub struct Config {
    /// Address to listen on.
    #[arg(long, env = "TASKY_BIND", default_value = "0.0.0.0:8080")]
    pub bind: SocketAddr,

    /// Path to the SQLite database file (created if missing).
    #[arg(long, env = "TASKY_DATABASE", default_value = "tasky.db")]
    pub database: String,

    /// Fixed household-creation token. If unset, a random single-use token is
    /// generated at startup (and after each use) and written to the log.
    #[arg(long, env = "TASKY_SETUP_TOKEN", hide_env_values = true)]
    pub setup_token: Option<String>,

    /// How long invite codes stay valid, in hours.
    #[arg(long, env = "TASKY_INVITE_TTL_HOURS", default_value_t = 48)]
    pub invite_ttl_hours: i64,

    /// How to reach whoever runs this server (e.g. an email address). Shown on the
    /// `/privacy` and `/delete-account` pages.
    #[arg(long, env = "TASKY_CONTACT")]
    pub contact: Option<String>,
}
