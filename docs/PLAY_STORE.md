# Publishing on Google Play

How Tasky gets from a commit to Google Play, and the one-time setup behind it.

| Trigger | What CI does ([`.github/workflows/android.yml`](../.github/workflows/android.yml)) |
|---|---|
| Pull request | Lint, unit tests, debug build |
| Push to `main` (Android changes) | The same, then a signed AAB to the **internal testing** track. Testers' phones update through Play within minutes |
| Tag `android-v1.2.3` | Signed AAB to **closed testing** (`alpha`) as version `1.2.3`, plus a GitHub Release with a signed APK (Obtainium) |
| Actions → android → Run workflow | Build and upload to the track you pick |

The `versionCode` is the workflow run number + 100, so it always grows. Production releases are promoted by hand in Play Console (Release → Production → "Promote release" from closed testing).

Package name: **`dev.eightprime.tasky`**. It is fixed forever once the first bundle is uploaded.

## One-time setup

Do these in order. Steps 1–3 can happen before the `play-store` branch is merged.

### 1. First upload, by hand

Google's API only accepts uploads for an app that already has one bundle, so the first one is manual.

1. Build it locally: `cd android && ./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` (versionCode 5, signed with your key from `~/.config/tasky-android/`).
2. Play Console → Tasky → Testing → **Internal testing** → Create new release.
3. **App signing** (asked once, can't be changed later):
   - **Recommended: use your own key** ("Use a different key" → "Export and upload a key from Java keystore"). Download `pepk.jar` and the encryption key the page offers, then run
     `java -jar pepk.jar --keystore=$HOME/.config/tasky-android/release.jks --alias=tasky --output=encrypted-key.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem`
     and upload `encrypted-key.zip`. Play then signs with the same key as the APKs on GitHub Releases, so people can switch between Play and Obtainium without reinstalling.
   - Or let Google generate the key. That works too, but Play installs and GitHub APKs can no longer update each other (switching means uninstall + sign in again).
4. Upload the AAB. Add yourself under the track's **Testers** tab (an email list), save, and roll out the release.

The old sideloaded app (`dev.tasky.android`) is a different app as far as Android is concerned. Uninstall it and sign in again in the Play version; all data is on the server.

### 2. Service account for CI

1. [Google Cloud Console](https://console.cloud.google.com/) → create a project (e.g. "tasky-play").
2. APIs & Services → Library → enable **Google Play Android Developer API**.
3. IAM & Admin → Service accounts → Create (e.g. `github-play-upload`). No Cloud roles needed.
4. Open it → Keys → Add key → JSON. Keep the file safe; it goes into a GitHub secret below, then delete it locally.
5. Play Console → **Users and permissions** → Invite new users → the service account's email (`…@….iam.gserviceaccount.com`) → App permissions → add Tasky with **Release apps to testing tracks** (and "View app information"). Add "Release to production" only if you ever want CI to publish to production.

New permissions can take a few hours to work. Until then uploads fail with "The caller does not have permission".

### 3. GitHub secrets

The release job runs in the GitHub **environment `play`** (Settings → Environments; it is created on the first run if missing). Put the secrets there so only that job can read them. With the `gh` CLI:

```sh
gh api -X PUT repos/8-prime/Tasky/environments/play
base64 -w0 ~/.config/tasky-android/release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env play
gh secret set ANDROID_KEYSTORE_PASSWORD --env play      # prompts for the value
gh secret set ANDROID_KEY_ALIAS --env play --body tasky
gh secret set ANDROID_KEY_PASSWORD --env play
gh secret set PLAY_SERVICE_ACCOUNT_JSON --env play < path/to/service-account.json
```

Values come from `~/.config/tasky-android/keystore.properties`. They are written into a properties file on the runner, so a password must not contain a backslash.

If `PLAY_SERVICE_ACCOUNT_JSON` is missing, CI still builds and attaches the signed AAB/APK to the run but skips the upload.

Optional: **variable** `PLAY_RELEASE_STATUS` (Settings → Environments → play → Variables). Defaults to `completed` (rolled out to the track). If the upload fails with "Only releases with status draft may be created on draft app", set it to `draft` until the first release has been rolled out in Play Console, then delete the variable.

### 4. Server: privacy policy, deletion page, demo account

After merging, CI publishes the new server image. Update `tasky.8-prime.dev`, set `TASKY_CONTACT` (your email) in the compose file, and check that these work:

- https://tasky.8-prime.dev/privacy
- https://tasky.8-prime.dev/delete-account

Create the reviewer account with a long password:

```sh
docker compose exec -e TASKY_DEMO_PASSWORD='…' tasky tasky-server seed-demo
```

This creates `playreview` ("Alex") and `playreview-partner` ("Sam") in a "Demo household" with lists, a checklist, a note, a meal, a meal plan for the next days and some expenses. Run it again before each submission (or from cron) to undo whatever reviewers changed or deleted. The plan dates are relative to the day you run it.

### 5. Play Console: App content and store listing

**Policy → App content**

| Section | Answer |
|---|---|
| Privacy policy | `https://tasky.8-prime.dev/privacy` |
| Ads | No ads |
| App access | "All or some functionality is restricted" → add instructions: *Keep the prefilled server https://tasky.8-prime.dev, tap "Continue", then "Sign in". Username: `playreview`, password: `…`. All features (lists, meal plan, meals, expenses, household) are available with this account.* |
| Content rating | Category: Utility / Productivity. No violence, sexuality, gambling etc. Users can interact / share content: **yes** (household members share lists with each other; only invited members) |
| Target audience | 18 and over (keeps the app out of the Families policy) |
| News, health, government, COVID apps | No |
| Financial features | None: Tasky only records amounts people enter; no payments, banking, lending or crypto |
| Data safety | See below |

**Data safety**

- Collects or shares any required data types: **Yes**. Encrypted in transit: **Yes** (HTTPS only).
- Account creation: **Username and password**. Delete account URL: `https://tasky.8-prime.dev/delete-account`. Users can request deletion: **Yes**.
- Data shared with third parties: **None**.
- Data collected (all: *not shared*, *not processed ephemerally*, *required*, purposes **App functionality** and **Account management**):
  - Personal info → **Name** (display name) and **User IDs** (username)
  - Financial info → **Other financial info** (expense descriptions and amounts)
  - App activity → **Other user-generated content** (lists, notes, meals, meal plans)

Reasoning: the default server is run by you, so data sent there counts as collected by you. The app has no analytics, crash reporting, ads or Google Play Services.

**Grow → Store presence → Main store listing**

- Short description (max 80 characters), e.g. *Shared shopping lists, meal plans and expenses for your household.*
- Full description: the feature list from the README works, plus a sentence about self-hosting.
- App icon 512×512 PNG, feature graphic 1024×500, at least 2 phone screenshots (sign in as `playreview` on a phone or emulator to take them).
- Category: Productivity (or Food & Drink). Contact email.

## Getting production access

Personal developer accounts must run a closed test first. The current requirement is shown on the Play Console dashboard (at the time of writing: **12 testers opted in for 14 days in a row**).

1. Testing → **Closed testing** → the default track (`alpha`) → Testers: add an email list or a Google Group with your testers.
2. Push a tag to fill the track: `git tag android-v0.4.0 && git push origin android-v0.4.0`. The first closed release goes through Google review (usually hours to a few days).
3. Share the **opt-in link** from the Testers tab. Every tester opens it, accepts, installs Tasky from Play and keeps it installed for the 14 days. Testers who don't have a Tasky account need an invite from you (Household → Invite).
4. Keep shipping tagged updates during the test; Google looks at engagement and whether you acted on feedback.
5. Dashboard → **Apply for production**, answer the questionnaire. Once approved, promote the closed release to production.

## Troubleshooting

| Error | Fix |
|---|---|
| `Package not found: dev.eightprime.tasky` | The first bundle hasn't been uploaded by hand yet (step 1) |
| `The caller does not have permission` | Service account not invited in Play Console, or the permission hasn't propagated yet (wait a few hours) |
| `Only releases with status draft may be created on draft app` | Set the `PLAY_RELEASE_STATUS` variable to `draft` (step 3) |
| `Version code N has already been used` | Re-running an old workflow run reuses its number. Start a new run instead (push, or Run workflow) |
| `ANDROID_KEYSTORE_BASE64 is not set` | Secrets are missing, or they are repository secrets under a different name |
