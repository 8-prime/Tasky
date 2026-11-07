# Tasky

Weekly meal planning and shared shopping lists for a household. A self-hosted Rust server and an Android app.

- Plan breakfast, lunch and dinner per person or for the whole household.
- Keep a catalog of known meals with ingredients (amount + unit).
- Add planned meals to the shared shopping list. Amounts of the same item add up across meals.
- Several people can edit the list at once without losing anything: entities are identified by ID and merged field by field, never by position.

Docs: [architecture & decisions](docs/ARCHITECTURE.md) · [API & sync protocol](docs/API.md)

## Run the server

### Docker

```sh
docker compose up -d
docker compose logs tasky | grep "creation token"
```

Images are published to `ghcr.io/8-prime/tasky` from `main` by CI.

### Binary

```sh
cd server
cargo build --release      # or: cargo zigbuild --release --target x86_64-unknown-linux-musl  (static binary)
./target/release/tasky-server --database /var/lib/tasky/tasky.db
```

| Flag / env | Default | |
|---|---|---|
| `--bind` / `TASKY_BIND` | `0.0.0.0:8080` | Listen address |
| `--database` / `TASKY_DATABASE` | `tasky.db` | SQLite file (created if missing) |
| `--setup-token` / `TASKY_SETUP_TOKEN` | random | Fixed, reusable household-creation token. If unset, a single-use token is logged at startup and after each use |
| `--invite-ttl-hours` / `TASKY_INVITE_TTL_HOURS` | `48` | Invite code lifetime |
| `RUST_LOG` | `info` | Log filter |

On startup the server logs:

```
INFO tasky_server::setup_token: household creation token (single use): tsk_…
```

Use that token in the app to create your household, then invite everyone else from the app.

**TLS:** Android blocks plain HTTP by default, so put the server behind a reverse proxy with HTTPS (WebSockets must be passed through). For example, with Caddy:

```
tasky.example.com {
    reverse_proxy localhost:8080
}
```

**Backups:** everything is in the one SQLite file. Use `sqlite3 tasky.db ".backup tasky-backup.db"` while the server is running, or copy the file while it's stopped.

## Development

```sh
cd server
cargo test                 # unit + integration tests (real server over TCP)
cargo clippy --all-targets
../scripts/gen-api.sh      # regenerate Kotlin models after changing tasky-api (needs: cargo install typeshare-cli)
```

Layout: `server/` (Rust workspace) · `api/kotlin/` (generated models) · `docs/` · `android/` (coming next).
