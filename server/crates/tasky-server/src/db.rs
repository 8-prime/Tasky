use std::{str::FromStr, time::Duration};

use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
};

/// SQLite allows only one writer at a time, so all writes go through a
/// single-connection pool. Reads use a separate pool (WAL lets readers run alongside the writer).
#[derive(Clone)]
pub struct Db {
    pub write: SqlitePool,
    pub read: SqlitePool,
}

impl Db {
    pub async fn open(path: &str) -> anyhow::Result<Self> {
        let opts = SqliteConnectOptions::from_str(&format!("sqlite://{path}"))?
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Normal)
            .foreign_keys(true)
            .busy_timeout(Duration::from_secs(10));

        let write = SqlitePoolOptions::new()
            .max_connections(1)
            .connect_with(opts.clone())
            .await?;
        sqlx::migrate!("./migrations").run(&write).await?;

        let read = SqlitePoolOptions::new()
            .max_connections(8)
            .connect_with(opts.read_only(true))
            .await?;

        Ok(Db { write, read })
    }
}
