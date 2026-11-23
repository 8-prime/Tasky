//! API version negotiation: apps send the protocol version they speak, and the server refuses
//! apps that are too old with `426 client_outdated` instead of sending data they can't read.
//! Every response states the server's own versions, so apps also notice when the server is
//! older than they need.

use axum::{
    extract::Request,
    http::HeaderValue,
    middleware::Next,
    response::{IntoResponse, Response},
};
use tasky_api::{
    API_VERSION, API_VERSION_HEADER, ErrorCode, MIN_CLIENT_API_VERSION,
    MIN_CLIENT_API_VERSION_HEADER, SERVER_API_VERSION_HEADER,
};

use crate::error::AppError;

/// API version an app speaks, from its request header. Apps from before the header are version 1.
pub fn client_api_version(req: &Request) -> Option<i32> {
    match req.headers().get(API_VERSION_HEADER) {
        None => Some(1),
        Some(v) => v.to_str().ok()?.trim().parse().ok(),
    }
}

/// Middleware for `/api/v1`. `GET /server-info` always answers, so apps can find out why they
/// were refused.
pub async fn check(req: Request, next: Next) -> Response {
    let exempt = req.uri().path().ends_with("/server-info");
    let mut response = match client_api_version(&req) {
        _ if exempt => next.run(req).await,
        None => AppError::new(
            ErrorCode::BadRequest,
            format!("{API_VERSION_HEADER} must be a number"),
        )
        .into_response(),
        Some(v) if v < MIN_CLIENT_API_VERSION => AppError::new(
            ErrorCode::ClientOutdated,
            format!(
                "This version of the Tasky app is too old for this server. Please update the app. \
                 (app API version {v}, server needs at least {MIN_CLIENT_API_VERSION})"
            ),
        )
        .into_response(),
        Some(_) => next.run(req).await,
    };
    let headers = response.headers_mut();
    headers.insert(SERVER_API_VERSION_HEADER, HeaderValue::from(API_VERSION));
    headers.insert(
        MIN_CLIENT_API_VERSION_HEADER,
        HeaderValue::from(MIN_CLIENT_API_VERSION),
    );
    response
}
