use bytes::{BufMut, Bytes, BytesMut};
use uuid::Uuid;

pub const MAX_CHUNK: usize = 65_536;
pub const ENVELOPE_HEADER: usize = 17;

#[derive(Clone, PartialEq, Eq)]
pub enum Envelope {
    Connect(Uuid),
    Disconnect(Uuid),
    Data(Uuid, Bytes),
}

// Do not leak TLS payload bytes through diagnostics or assertion output.
impl std::fmt::Debug for Envelope {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Connect(_) => "Connect(..)",
            Self::Disconnect(_) => "Disconnect(..)",
            Self::Data(_, _) => "Data(..)",
        })
    }
}

#[derive(Debug, thiserror::Error)]
#[error("invalid channel envelope")]
pub struct EnvelopeError;

impl Envelope {
    pub fn decode(bytes: &[u8]) -> Result<Self, EnvelopeError> {
        if bytes.len() < ENVELOPE_HEADER || bytes.len() > ENVELOPE_HEADER + MAX_CHUNK {
            return Err(EnvelopeError);
        }
        let id = Uuid::from_slice(&bytes[1..17]).map_err(|_| EnvelopeError)?;
        if id.is_nil() {
            return Err(EnvelopeError);
        }
        match (bytes[0], bytes.len()) {
            (1, ENVELOPE_HEADER) => Ok(Self::Connect(id)),
            (2, ENVELOPE_HEADER) => Ok(Self::Disconnect(id)),
            (3, n) if n > ENVELOPE_HEADER => {
                Ok(Self::Data(id, Bytes::copy_from_slice(&bytes[17..])))
            }
            _ => Err(EnvelopeError),
        }
    }

    pub fn decode_helper(bytes: &[u8]) -> Result<Self, EnvelopeError> {
        match Self::decode(bytes)? {
            Self::Connect(_) => Err(EnvelopeError),
            event => Ok(event),
        }
    }

    /// Encoding is used for relay-generated notifications and validated data.
    pub fn encode(&self) -> Bytes {
        let (kind, id, data): (u8, &Uuid, &[u8]) = match self {
            Self::Connect(id) => (1, id, &[]),
            Self::Disconnect(id) => (2, id, &[]),
            Self::Data(id, data) => (3, id, data),
        };
        let mut bytes = BytesMut::with_capacity(ENVELOPE_HEADER + data.len());
        bytes.put_u8(kind);
        bytes.extend_from_slice(id.as_bytes());
        bytes.extend_from_slice(data);
        bytes.freeze()
    }
}
