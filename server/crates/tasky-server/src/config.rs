use std::net::SocketAddr;

use clap::Parser;

/// Tasky server: shared meal planning and shopping lists for households.
#[derive(Debug, Clone, Parser)]
#[command(version, about)]
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
}
