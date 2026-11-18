//! Plain HTML pages that app stores ask for: the privacy policy and a way to delete an
//! account without the app. Served by every Tasky server, so they describe that server.

use std::time::Duration;

use axum::{
    Form,
    extract::State,
    http::StatusCode,
    response::{Html, IntoResponse, Response},
};
use serde::Deserialize;

use crate::{
    AppState, auth::verify_password, deletion::delete_user, error::AppResult, util::parse_uuid,
};

fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

fn page(title: &str, body: &str) -> Html<String> {
    Html(format!(
        r#"<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} · Tasky</title>
<style>
  :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; line-height: 1.5; }}
  body {{ max-width: 42rem; margin: 2rem auto; padding: 0 1rem; }}
  h1 {{ font-size: 1.6rem; }}
  h2 {{ font-size: 1.15rem; margin-top: 1.8rem; }}
  form {{ display: grid; gap: .75rem; max-width: 22rem; margin-top: 1.5rem; }}
  input {{ font: inherit; padding: .5rem; }}
  button {{ font: inherit; padding: .6rem; background: #b3261e; color: #fff; border: 0; border-radius: .4rem; cursor: pointer; }}
  .note {{ padding: .75rem 1rem; border-radius: .4rem; background: color-mix(in srgb, currentColor 8%, transparent); }}
</style>
</head>
<body>
<h1>{title}</h1>
{body}
</body>
</html>
"#
    ))
}

fn contact_html(state: &AppState) -> String {
    match state.config.contact.as_deref().map(str::trim) {
        Some(c) if !c.is_empty() => {
            let c = escape(c);
            if c.contains('@') && !c.contains(' ') {
                format!(r#"<a href="mailto:{c}">{c}</a>"#)
            } else {
                c
            }
        }
        _ => "the person who runs this server".to_owned(),
    }
}

/// `GET /privacy`
pub async fn privacy(State(state): State<AppState>) -> Html<String> {
    let contact = contact_html(&state);
    page(
        "Privacy policy",
        &format!(
            r#"<p>Tasky is an app for shared shopping lists, meal plans and household expenses.
It stores your data on a Tasky server: either the default server, or one you or your
household run yourselves. This page describes the server you are reading it on.
Questions: {contact}.</p>

<h2>What the server stores</h2>
<ul>
  <li><b>Your account:</b> username, display name and a salted hash of your password (never the password itself).</li>
  <li><b>Sessions:</b> one per signed-in device, with the device name and when it was last used, so you can see and sign out your devices.</li>
  <li><b>Household content you create:</b> lists, items, notes, meals, meal plans and expenses (descriptions, amounts, who paid and who shares).</li>
  <li><b>Invite codes</b> (hashed) and who used them.</li>
</ul>
<p>The app does not collect your location, contacts, photos, advertising ID or usage analytics, and
contains no ads, trackers or third-party SDKs.</p>

<h2>Why</h2>
<p>Only to run the service: to sign you in and to sync your household's data between its members' devices.
Nothing is sold, shared with third parties or used for advertising.</p>

<h2>Who can see it</h2>
<p>The members of your household see the household's content and each other's display names.
The server's operator can technically access the database to run and back up the service.</p>

<h2>Security</h2>
<p>The app only talks to servers over HTTPS. Passwords are stored as Argon2 hashes, and session
tokens only as SHA-256 hashes.</p>

<h2>How long it is kept, and deleting it</h2>
<p>Your data is kept until you delete your account. You can do that in the app (Household → Delete account)
or on the <a href="/delete-account">account deletion page</a>. Deleting your account removes
right away: your username, display name, password hash, sessions, invites you created and meals planned only for you.
If you were the last member of your household, the whole household and all its content are deleted too.</p>
<p>If other members remain, the shared lists, meals and expenses stay with them, since they are
their data as well. Expenses you were part of keep their amounts so everyone's balance stays correct,
but no longer show your name. If the operator keeps backups, deleted data disappears from them as old backups are replaced.</p>

<h2>Changes</h2>
<p>If this policy changes, the new version is published on this page.</p>
"#
        ),
    )
}

/// `GET /delete-account`
pub async fn delete_account_form(State(state): State<AppState>) -> Html<String> {
    delete_page(&state, None)
}

fn delete_page(state: &AppState, error: Option<&str>) -> Html<String> {
    let error = error
        .map(|e| format!(r#"<p class="note" role="alert">{}</p>"#, escape(e)))
        .unwrap_or_default();
    let contact = contact_html(state);
    page(
        "Delete your Tasky account",
        &format!(
            r#"<p>You can also do this in the app under Household → Delete account.</p>
<p>Deleting your account removes your username, display name, password, signed-in devices,
invites you created and meals planned only for you. If you are the last member of your household,
the household and everything in it are deleted as well. Otherwise the shared lists, meals and
expenses stay with the other members, without your name.
See the <a href="/privacy">privacy policy</a> for details.</p>
<p><b>This cannot be undone.</b> If you can't sign in any more, contact {contact}.</p>
{error}
<form method="post" action="/delete-account">
  <label>Username<br><input name="username" autocomplete="username" required></label>
  <label>Password<br><input name="password" type="password" autocomplete="current-password" required></label>
  <button type="submit">Delete my account</button>
</form>
"#
        ),
    )
}

#[derive(Deserialize)]
pub struct DeleteAccountForm {
    username: String,
    password: String,
}

/// `POST /delete-account`
pub async fn delete_account(
    State(state): State<AppState>,
    Form(form): Form<DeleteAccountForm>,
) -> AppResult<Response> {
    let row: Option<(String, String)> =
        sqlx::query_as("SELECT id, password_hash FROM users WHERE username = ?")
            .bind(form.username.trim())
            .fetch_optional(&state.db.read)
            .await?;
    let verified = match &row {
        Some((_, hash)) => verify_password(form.password, hash.clone()).await?,
        None => false,
    };
    let Some((id, _)) = row.filter(|_| verified) else {
        tokio::time::sleep(Duration::from_millis(500)).await;
        let page = delete_page(&state, Some("Wrong username or password."));
        return Ok((StatusCode::UNAUTHORIZED, page).into_response());
    };

    delete_user(&state, parse_uuid(&id)?).await?;
    Ok(page(
        "Account deleted",
        "<p>Your Tasky account has been deleted. Devices that were signed in will be signed out the next time they connect.</p>",
    )
    .into_response())
}
