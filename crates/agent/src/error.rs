//! Stable public failures are independent of native and filesystem diagnostics.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Code {
    Unauthorized,
    Revoked,
    Expired,
    Unsupported,
    NotFound,
    InvalidParams,
    ProtocolMismatch,
    Rejected,
    Unknown,
    RuntimeOffline,
    RuntimeUnavailable,
    ThreadOwnedElsewhere,
    VersionConflict,
    OperationInProgress,
    RequestTimeout,
}
impl Code {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Unauthorized => "unauthorized",
            Self::Revoked => "revoked",
            Self::Expired => "expired",
            Self::Unsupported => "unsupported",
            Self::NotFound => "not_found",
            Self::InvalidParams => "invalid_params",
            Self::ProtocolMismatch => "protocol_mismatch",
            Self::Rejected => "rejected",
            Self::Unknown => "unknown",
            Self::RuntimeOffline => "runtime_offline",
            Self::RuntimeUnavailable => "runtime_unavailable",
            Self::ThreadOwnedElsewhere => "thread_owned_elsewhere",
            Self::VersionConflict => "version_conflict",
            Self::OperationInProgress => "operation_in_progress",
            Self::RequestTimeout => "request_timeout",
        }
    }
    pub fn message(self) -> &'static str {
        match self {
            Self::Unauthorized => "Device authentication required",
            Self::Revoked => "Device access revoked",
            Self::Expired => "Pairing invitation expired or unavailable",
            Self::Unsupported => "Operation is not supported",
            Self::NotFound => "Command not found",
            Self::InvalidParams => "Invalid request parameters",
            Self::ProtocolMismatch => "Unsupported protocol version",
            Self::Rejected => "Request rejected",
            Self::Unknown => "Command outcome unknown; reconcile before sending another command",
            Self::RuntimeOffline => "The selected native runtime is offline",
            Self::RuntimeUnavailable => "The installed native runtime could not be started",
            Self::ThreadOwnedElsewhere => "This thread is held by another runtime; check ownership before continuing",
            Self::VersionConflict => "Settings changed; refresh before trying again",
            Self::OperationInProgress => "A previous operation is still being reconciled",
            Self::RequestTimeout => "The read operation timed out; the connection may still be available",
        }
    }
}
#[derive(Debug)]
struct Failure {
    code: Code,
    cause: anyhow::Error,
}
impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.cause.fmt(f)
    }
}
impl std::error::Error for Failure {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.cause.as_ref())
    }
}
pub fn classify(cause: anyhow::Error, code: Code) -> anyhow::Error {
    Failure { code, cause }.into()
}
pub fn code(error: &anyhow::Error) -> Code {
    // Never infer dispatch certainty from error text or an unknown error type.
    classification(error).unwrap_or(Code::Unknown)
}
pub fn classification(error: &anyhow::Error) -> Option<Code> {
    error.downcast_ref::<Failure>().map(|e| e.code)
}
