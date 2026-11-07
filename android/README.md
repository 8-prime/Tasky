# Tasky for Android

Native Kotlin + Jetpack Compose (Material 3) client for the Tasky server. Offline-first:
everything you see comes from a local Room database, every edit is applied locally at once
and queued, and a sync engine exchanges the queue with the server
(see [docs/API.md](../docs/API.md)). No Firebase or Google Play Services.

- Application id `dev.tasky.android` (debug builds: `dev.tasky.android.debug`), minSdk 26, compile/target SDK 37.
- Default server: `https://tasky.8-prime.dev`, changeable on the first screen ("Change server").

## Build

Needs JDK 21 and an Android SDK with platform `android-37.0` and build-tools `37.0.0`.
Point Gradle at the SDK with `ANDROID_HOME` or `android/local.properties` (`sdk.dir=/path/to/sdk`, not committed).

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM + Robolectric tests
./gradlew lint
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk (signed, see below)
```

Build options (Gradle properties):

| Property | Default | |
|---|---|---|
| `-PtaskyServerUrl=…` | `https://tasky.8-prime.dev` | Server prefilled in onboarding (`BuildConfig.DEFAULT_SERVER_URL`) |
| `-PtaskyKeystoreProperties=…` | `~/.config/tasky-android/keystore.properties` | Release signing config |

### Release signing

The keystore never lives in the repo. If the properties file exists, `assembleRelease` signs with it;
otherwise the release APK is unsigned (use the debug APK instead). Format:

```properties
storeFile=/home/you/.config/tasky-android/release.jks
storePassword=…
keyAlias=tasky
keyPassword=…
```

Create one with
`keytool -genkeypair -keystore ~/.config/tasky-android/release.jks -storetype PKCS12 -alias tasky -keyalg RSA -keysize 4096 -validity 10000`.
Keep a backup: updates must be signed with the same key.

## Install

Copy the APK to the phone and open it (allow "install unknown apps" for the file manager or browser),
or use `adb install -r app/build/outputs/apk/release/app-release.apk`. Obtainium can track GitHub releases.

Debug builds allow plain `http://` so you can use a local server, e.g. from the emulator:
`cd server && cargo run -- --bind 0.0.0.0:8080 --database /tmp/tasky-dev.db`, then
"Change server" → `http://10.0.2.2:8080`. Release builds require HTTPS.

## Tests

- `api/WireFormatTest`: the JSON shapes from `server/crates/tasky-api/tests/wire_format.rs`.
- `sync/*`: HLC, UUIDv5/v7 (incl. the contribution-ID test vector), op application, and
  `SyncEngineTest` (Robolectric + in-memory Room) for outbox replay, `has_more`, `full_resync`,
  aliases, rejections and stale ops against a scripted server.
- `domain/*`: unit conversion, list-line aggregation and sorting, meal scaling.
- `ui/ScreensTest`: Compose screens on Robolectric (list, meal editor, planner add-to-list).
- `e2e/ServerEndToEndTest` and `ScreensTest.onboardingCreatesAHousehold`: opt-in, against a real server:

  ```sh
  (cd ../server && cargo run -- --bind 127.0.0.1:8080 --database /tmp/tasky-e2e.db --setup-token tsk_test) &
  TASKY_TEST_SERVER=http://127.0.0.1:8080 TASKY_TEST_SETUP_TOKEN=tsk_test ./gradlew testDebugUnitTest
  ```

  Two simulated devices (separate databases, clocks and sync engines) create and join a household, merge
  same-named items, check off concurrently, add planned meals twice without doubling, and listen on the
  live socket. Without the variables these tests are skipped.

## Architecture

```
app/src/main/java/dev/tasky/android/
  TaskyApp, AppContainer     manual DI; foreground/background lifecycle (live socket, periodic sync)
  MainActivity               Compose host; handles tasky://join?server=…&code=… invite links
  api/                       OkHttp REST client (ApiClient), TaskyJson, server URL normalisation
  data/
    SessionStore             session (token AES-GCM encrypted with an Android Keystore key), HLC state
    AccountRepository        create/join/login, household details, invites, display name
    TaskyRepository          every user edit → ops (optimistic Room update + outbox)
    db/                      Room: typed visible tables, server_entities, outbox, members, sync_state
  sync/
    Hlc, Uuids               clocks and IDs from docs/API.md
    OpApplier                applies ops to entity state (optimistic edits and replay)
    LocalStore               visible = server state + pending ops; applies sync responses atomically
    SyncEngine               POST /sync loop (outbox, has_more, full_resync, aliases, rejected)
    SyncCoordinator          debounced/periodic triggers; hands off to WorkManager when offline
    SyncWorker               network-constrained background flush
    LiveConnection           /live WebSocket with backoff while in the foreground
  domain/                    Units, ShoppingList (lines), MealPlanning (weeks, servings, proposals)
  ui/                        onboarding, list, meals, plan, household screens
```

Generated wire models are compiled straight from `../api/kotlin` (a source directory in
`app/build.gradle.kts`); don't edit them, regenerate with `scripts/gen-api.sh`.

**Sync model.** For each entity, the row the UI reads equals the last server state
(`server_entities`) with the pending outbox ops for that entity applied on top. A local edit applies its
op to the visible row and appends it to the outbox in one transaction. A sync sends unsent ops
(marking them in flight), stores `changes` as server state, drops rejected ops at once and the in-flight
batch after the last page, rewrites queued ops that mention an aliased item, and recomputes the visible
rows it touched. Sends that fail are simply resent later (ops are idempotent).

Triggers: app start/resume, 300 ms after edits, `changed` live events with a newer cursor, every
2 minutes in the foreground, and WorkManager (network required) when a sync fails or the app goes to the
background with queued edits. A 401 signs out and wipes local data.
