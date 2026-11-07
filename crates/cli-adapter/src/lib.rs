//! Passive discovery and attachment to the native Codex CLI shared daemon.
//!
//! Discovery reads filesystem metadata only. Attachment never launches a process,
//! initializes a thread, changes configuration, or performs a JSON-RPC handshake
//! implicitly. Send initialize, verify its result against the discovered home,
//! then send initialized before business requests.
use futures_util::{SinkExt, StreamExt};
use serde_json::Value;
use std::{
    io,
    path::{Path, PathBuf},
    time::Duration,
};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_tungstenite::{
    WebSocketStream,
    tungstenite::{
        self, Message,
        protocol::{Role, WebSocketConfig},
    },
};

mod local;
#[cfg(windows)]
mod windows_peer;
#[cfg(windows)]
mod windows_security;
#[cfg(windows)]
mod windows_socket_validation;
pub use local::LocalStream;

/// Source contract, not an observed binary version.
pub const SOURCE_VERSION: &str = "0.164.0";

/// Metadata presence says nothing about whether the endpoint is listening.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Availability {
    Absent,
    Present,
}

/// Canonical config-store identity and its native daemon rendezvous.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Discovery {
    home: PathBuf,
    socket: PathBuf,
    availability: Availability,
}

impl Discovery {
    /// Resolve an existing CODEX_HOME. Does not create directories or connect.
    pub fn for_home(home: &Path) -> io::Result<Self> {
        if home.as_os_str().is_empty() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        if !std::fs::metadata(home)?.is_dir() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        let home = dunce::canonicalize(home)?;
        let socket = home
            .join("app-server-control")
            .join("app-server-control.sock");
        #[cfg(windows)]
        validate_windows_socket_path(&socket)?;
        let availability = match std::fs::symlink_metadata(&socket) {
            Ok(_) => {
                local::validate_endpoint(&socket)?;
                Availability::Present
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Availability::Absent,
            Err(error) => return Err(error),
        };
        Ok(Self {
            home,
            socket,
            availability,
        })
    }

    /// Native precedence: nonempty Unicode CODEX_HOME, then OS home/.codex.
    /// A missing default store is None; explicit invalid homes fail.
    pub fn from_environment() -> io::Result<Option<Self>> {
        if let Some(home) = std::env::var("CODEX_HOME")
            .ok()
            .filter(|value| !value.is_empty())
        {
            return Self::for_home(Path::new(&home)).map(Some);
        }
        let home = dirs::home_dir()
            .ok_or(io::ErrorKind::NotFound)?
            .join(".codex");
        match Self::for_home(&home) {
            Err(error) if error.kind() == io::ErrorKind::NotFound && !home.try_exists()? => {
                Ok(None)
            }
            result => result.map(Some),
        }
    }

    pub fn codex_home(&self) -> &Path {
        &self.home
    }
    pub fn socket_path(&self) -> &Path {
        &self.socket
    }
    pub fn availability(&self) -> Availability {
        self.availability
    }

    /// Validate only identity metadata from a correlated initialize result.
    pub fn verify_initialize_result(&self, result: &Value) -> io::Result<ServerIdentity> {
        let string = |key: &str| {
            result
                .get(key)
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty())
                .ok_or_else(|| invalid_data("initialize identity metadata missing"))
        };
        let path = Path::new(string("codexHome")?);
        if !path.is_absolute() || dunce::canonicalize(path)? != self.home {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "daemon CODEX_HOME identity mismatch",
            ));
        }
        let (_, rest) = string("userAgent")?
            .split_once('/')
            .ok_or_else(|| invalid_data("initialize version missing"))?;
        let version = rest
            .split_whitespace()
            .next()
            .ok_or_else(|| invalid_data("initialize version missing"))?
            .to_owned();
        Ok(ServerIdentity {
            version,
            platform_family: string("platformFamily")?.to_owned(),
            platform_os: string("platformOs")?.to_owned(),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerIdentity {
    pub version: String,
    pub platform_family: String,
    pub platform_os: String,
}

/// Check a Windows AF_UNIX address, including its NUL byte. Extended drive
/// prefixes do not consume sun_path space. Reject UNC, pipes and relative paths.
pub fn validate_windows_socket_path(path: &Path) -> io::Result<()> {
    let text = path.to_str().ok_or(io::ErrorKind::InvalidInput)?;
    let text = text.strip_prefix(r"\\?\").unwrap_or(text);
    let bytes = text.as_bytes();
    if bytes.len() < 3
        || !bytes[0].is_ascii_alphabetic()
        || bytes[1] != b':'
        || !matches!(bytes[2], b'\\' | b'/')
        || text.contains('\0')
        || bytes.len() >= 108
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid or overlong Windows AF_UNIX address",
        ));
    }
    Ok(())
}

#[derive(Debug, Clone, Copy)]
pub struct Limits {
    /// Bound both frame and reassembled JSON message sizes in bytes.
    pub max_message_bytes: usize,
    /// Total deadline for validation, AF_UNIX connection and WebSocket upgrade.
    pub connect_timeout: Duration,
}
impl Default for Limits {
    fn default() -> Self {
        Self {
            max_message_bytes: 16 * 1024 * 1024,
            connect_timeout: Duration::from_secs(5),
        }
    }
}
impl Limits {
    fn config(self) -> io::Result<WebSocketConfig> {
        if self.max_message_bytes == 0 || self.connect_timeout.is_zero() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        Ok(WebSocketConfig::default()
            .max_message_size(Some(self.max_message_bytes))
            .max_frame_size(Some(self.max_message_bytes)))
    }
}

/// Revalidate the endpoint and authenticate its Windows peer before any HTTP or
/// RPC bytes. Retain the private directory handle for the connection's lifetime.
/// No process is started. The caller performs initialize/initialized explicitly.
pub async fn connect(
    discovery: &Discovery,
    limits: Limits,
) -> io::Result<JsonRpcConnection<LocalStream>> {
    let config = limits.config()?;
    tokio::time::timeout(limits.connect_timeout, async {
        let stream = local::connect(&discovery.socket).await?;
        let (stream, _) =
            tokio_tungstenite::client_async_with_config("ws://localhost/", stream, Some(config))
                .await
                .map_err(ws_error)?;
        Ok(JsonRpcConnection {
            stream,
            limits,
            closed: false,
        })
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "daemon attachment timed out"))?
}

/// One JSON object per WebSocket text message. Preserve native JSON IDs exactly.
/// Keep writes serialized; do not cancel a write mid-flight. Use one owner task
/// when several callers send RPCs. Idle reads can be selected/cancelled.
pub struct JsonRpcConnection<S> {
    stream: WebSocketStream<S>,
    limits: Limits,
    closed: bool,
}
#[cfg(windows)]
impl JsonRpcConnection<LocalStream> {
    /// Kernel identity of this authenticated connection's native daemon.
    /// Query before initialize; capture creation time to guard against PID reuse.
    pub fn peer_process_id(&self) -> io::Result<u32> {
        self.stream.get_ref().peer_process_id()
    }
}
impl<S: AsyncRead + AsyncWrite + Unpin> JsonRpcConnection<S> {
    /// Low-level constructor for an already authenticated, upgraded transport.
    /// Bypasses path/peer validation; normal integrations must use connect.
    pub async fn from_raw_socket(stream: S, limits: Limits) -> io::Result<Self> {
        let config = limits.config()?;
        Ok(Self {
            stream: WebSocketStream::from_raw_socket(stream, Role::Client, Some(config)).await,
            limits,
            closed: false,
        })
    }

    /// None means WebSocket close; unannounced EOF is an error. Malformed JSON,
    /// binary messages and size violations are terminal errors.
    pub async fn read(&mut self) -> io::Result<Option<Value>> {
        if self.closed {
            return Ok(None);
        }
        loop {
            match self.stream.next().await {
                Some(Ok(Message::Text(text))) => {
                    let result = if text.len() > self.limits.max_message_bytes {
                        Err(invalid_data("JSON-RPC message exceeds limit"))
                    } else {
                        serde_json::from_str::<Value>(&text)
                            .map_err(|_| invalid_data("invalid JSON-RPC JSON"))
                            .and_then(|value| {
                                if value.is_object() {
                                    Ok(value)
                                } else {
                                    Err(invalid_data("JSON-RPC message must be an object"))
                                }
                            })
                    };
                    if result.is_err() {
                        self.closed = true;
                    }
                    return result.map(Some);
                }
                Some(Ok(Message::Ping(_))) => self.stream.flush().await.map_err(ws_error)?,
                Some(Ok(Message::Pong(_))) => {}
                Some(Ok(Message::Close(_))) => {
                    self.closed = true;
                    let _ = self.stream.flush().await;
                    return Ok(None);
                }
                Some(Ok(_)) => {
                    self.closed = true;
                    return Err(invalid_data("unexpected binary WebSocket message"));
                }
                Some(Err(error)) => {
                    self.closed = true;
                    return Err(ws_error(error));
                }
                None => {
                    self.closed = true;
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "daemon transport ended without close",
                    ));
                }
            }
        }
    }

    pub async fn write(&mut self, message: &Value) -> io::Result<()> {
        if self.closed {
            return Err(io::ErrorKind::BrokenPipe.into());
        }
        if !message.is_object() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        let text = serde_json::to_string(message)
            .map_err(|_| invalid_data("cannot encode JSON-RPC message"))?;
        if text.len() > self.limits.max_message_bytes {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "JSON-RPC message exceeds limit",
            ));
        }
        if let Err(error) = self.stream.send(Message::Text(text.into())).await {
            self.closed = true;
            return Err(ws_error(error));
        }
        Ok(())
    }

    /// Close this client connection; never shuts down the daemon.
    pub async fn close(&mut self) -> io::Result<()> {
        self.closed = true;
        self.stream.close(None).await.map_err(ws_error)
    }
}

fn invalid_data(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}
fn ws_error(error: tungstenite::Error) -> io::Error {
    match error {
        tungstenite::Error::Io(error) => error,
        tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed => {
            io::ErrorKind::BrokenPipe.into()
        }
        tungstenite::Error::Protocol(
            tungstenite::error::ProtocolError::ResetWithoutClosingHandshake,
        ) => io::ErrorKind::UnexpectedEof.into(),
        _ => invalid_data("invalid or oversized WebSocket transport message"),
    }
}
