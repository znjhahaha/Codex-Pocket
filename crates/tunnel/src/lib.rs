//! Inner TLS and length-prefixed JSON objects for Pocket wire v1.
//!
//! Callers must drop the stream after any framing/protocol error (including a
//! cancelled frame operation). Serialize all writes to a connection; concurrent
//! frame writers can interleave prefixes and bodies. TLS terminates only at the
//! Windows helper and paired client, never at the opaque relay.

mod identity;
mod storage;

pub use identity::{ServerIdentity, pinned_client_config, pinned_server_verifier};

use serde::{Serialize, de::DeserializeOwned};
use std::io::{self, Write};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAX_JSON_FRAME: usize = 16 * 1024 * 1024;
pub const SERVER_NAME: &str = "pocket.local";
pub type Result<T> = std::result::Result<T, Error>;

/// Errors deliberately omit JSON contents, certificate/key contents, and paths.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("transport or identity I/O failed")]
    Io(#[from] io::Error),
    #[error("truncated JSON frame")]
    TruncatedFrame,
    #[error("JSON frame length is empty or exceeds the limit")]
    FrameLength,
    #[error("invalid JSON object")]
    InvalidJson,
    #[error("identity is incomplete, corrupt, mismatched, or invalid")]
    InvalidIdentity,
    #[error("identity storage is not private or is not a regular unlinked file/directory")]
    InsecureStorage,
    #[error("invalid certificate pin")]
    InvalidPin,
    #[error("TLS configuration failed")]
    TlsConfiguration,
    #[error("TLS handshake failed")]
    TlsHandshake,
    #[error("TLS handshake timed out")]
    HandshakeTimeout,
}

/// Read a single nonempty UTF-8 JSON object. Only EOF before the first prefix
/// byte is clean EOF; EOF anywhere else is a truncation error.
pub async fn read_json<R, T>(reader: &mut R) -> Result<Option<T>>
where
    R: AsyncRead + Unpin,
    T: DeserializeOwned,
{
    let mut prefix = [0; 4];
    if reader.read(&mut prefix[..1]).await? == 0 {
        return Ok(None);
    }
    read_frame_bytes(reader, &mut prefix[1..]).await?;
    let length = u32::from_be_bytes(prefix) as usize;
    if length == 0 || length > MAX_JSON_FRAME {
        return Err(Error::FrameLength);
    }
    let mut bytes = vec![0; length];
    read_frame_bytes(reader, &mut bytes).await?;
    validate_object(&bytes)?;
    serde_json::from_slice(&bytes)
        .map(Some)
        .map_err(|_| Error::InvalidJson)
}

async fn read_frame_bytes<R: AsyncRead + Unpin>(reader: &mut R, bytes: &mut [u8]) -> Result<()> {
    reader.read_exact(bytes).await.map(|_| ()).map_err(|e| {
        if e.kind() == io::ErrorKind::UnexpectedEof {
            Error::TruncatedFrame
        } else {
            Error::Io(e)
        }
    })
}

fn validate_object(bytes: &[u8]) -> Result<()> {
    std::str::from_utf8(bytes).map_err(|_| Error::InvalidJson)?;
    // IgnoredAny validates the complete JSON without constructing a second
    // in-memory object. Explicit UTF-8 validation above is necessary because
    // serde's IgnoredAny may skip string values without decoding them.
    if bytes.iter().copied().find(|b| !b.is_ascii_whitespace()) != Some(b'{') {
        return Err(Error::InvalidJson);
    }
    serde_json::from_slice::<serde::de::IgnoredAny>(bytes).map_err(|_| Error::InvalidJson)?;
    Ok(())
}

/// Serialize and validate before emitting even the prefix. Serialization is
/// bounded to MAX_JSON_FRAME bytes, including custom Serialize implementations.
pub async fn write_json<W, T>(writer: &mut W, value: &T) -> Result<()>
where
    W: AsyncWrite + Unpin,
    T: Serialize + ?Sized,
{
    let mut buffer = BoundedJson {
        bytes: Vec::new(),
        over_limit: false,
    };
    if serde_json::to_writer(&mut buffer, value).is_err() {
        return Err(if buffer.over_limit {
            Error::FrameLength
        } else {
            Error::InvalidJson
        });
    }
    validate_object(&buffer.bytes)?;
    writer
        .write_all(&(buffer.bytes.len() as u32).to_be_bytes())
        .await?;
    writer.write_all(&buffer.bytes).await?;
    writer.flush().await?;
    Ok(())
}

struct BoundedJson {
    bytes: Vec<u8>,
    over_limit: bool,
}
impl Write for BoundedJson {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if bytes.len() > MAX_JSON_FRAME - self.bytes.len() {
            self.over_limit = true;
            return Err(io::Error::other("JSON frame limit exceeded"));
        }
        // Vec's geometric growth remains bounded by the 16 MiB power-of-two cap.
        self.bytes.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
