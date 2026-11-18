use clap::{Parser, Subcommand};
use tasky_server::Config;
use tracing_subscriber::EnvFilter;

/// Tasky server: shared meal planning and shopping lists for households.
#[derive(Parser)]
#[command(version, about)]
struct Cli {
    #[command(flatten)]
    config: Config,
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// Create or reset a demo household with sample data, for app-store reviewers.
    ///
    /// Deletes the accounts USERNAME and USERNAME-partner (and their household) if they exist,
    /// then creates them again. Run it while the server is up or down; run it again to undo
    /// whatever a reviewer changed.
    SeedDemo {
        /// Username of the demo account. Reserved for the demo: an existing account with this
        /// name is deleted.
        #[arg(long, default_value = "playreview")]
        username: String,
        /// Password of the demo account (at least 8 characters).
        #[arg(long, env = "TASKY_DEMO_PASSWORD", hide_env_values = true)]
        password: String,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let Cli { config, command } = Cli::parse();
    if let Some(Command::SeedDemo { username, password }) = command {
        let db = tasky_server::db::Db::open(&config.database).await?;
        tasky_server::demo::seed_demo(&db, &username, &password).await?;
        println!("demo account ready: sign in as {username}");
        return Ok(());
    }

    let bind = config.bind;
    tracing::info!(database = %config.database, "starting tasky {}", env!("CARGO_PKG_VERSION"));

    let (app, _state, shutdown_tx) = tasky_server::build(config).await?;
    let listener = tokio::net::TcpListener::bind(bind).await?;
    tracing::info!("listening on http://{bind}");

    axum::serve(listener, app)
        .with_graceful_shutdown(async move {
            shutdown_signal().await;
            tracing::info!("shutting down");
            let _ = shutdown_tx.send(true);
        })
        .await?;
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = tokio::signal::ctrl_c();
    #[cfg(unix)]
    {
        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("install SIGTERM handler");
        tokio::select! {
            _ = ctrl_c => {}
            _ = term.recv() => {}
        }
    }
    #[cfg(not(unix))]
    let _ = ctrl_c.await;
}
