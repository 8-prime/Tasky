use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use tasky_api::{ErrorBody, ErrorCode};

/// Error returned by HTTP handlers. Turned into an [`ErrorBody`] JSON response.
#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("{1}")]
    Api(ErrorCode, String),
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

pub type AppResult<T> = Result<T, AppError>;

impl AppError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        AppError::Api(code, message.into())
    }
    pub fn validation(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Validation, message)
    }
    pub fn unauthorized() -> Self {
        Self::new(ErrorCode::Unauthorized, "missing or invalid session token")
    }
    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::NotFound, message)
    }
}

impl From<sqlx::Error> for AppError {
    fn from(e: sqlx::Error) -> Self {
        AppError::Internal(e.into())
    }
}

impl From<serde_json::Error> for AppError {
    fn from(e: serde_json::Error) -> Self {
        AppError::Internal(e.into())
    }
}

fn status_for(code: ErrorCode) -> StatusCode {
    match code {
        ErrorCode::BadRequest | ErrorCode::Validation => StatusCode::BAD_REQUEST,
        ErrorCode::Unauthorized | ErrorCode::InvalidCredentials => StatusCode::UNAUTHORIZED,
        ErrorCode::InvalidSetupToken | ErrorCode::InvalidInvite | ErrorCode::Forbidden => {
            StatusCode::FORBIDDEN
        }
        ErrorCode::NotFound => StatusCode::NOT_FOUND,
        ErrorCode::UsernameTaken => StatusCode::CONFLICT,
        ErrorCode::Internal => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (code, message) = match self {
            AppError::Api(code, message) => (code, message),
            AppError::Internal(err) => {
                tracing::error!(error = ?err, "internal error");
                (ErrorCode::Internal, "internal server error".to_owned())
            }
        };
        (
            status_for(code),
            Json(ErrorBody {
                error: code,
                message,
            }),
        )
            .into_response()
    }
}
