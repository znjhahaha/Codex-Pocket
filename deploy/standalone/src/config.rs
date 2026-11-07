use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use serde::{Deserialize, Serialize};

pub const MAX_ROOMS: usize = 32;
pub const MAX_PHONES: usize = 8;

/// Hashes are deployment secrets too: intentionally does not implement Debug.
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RoomConfig {
    pub id: String,
    pub helper_token_sha256: String,
    pub phone_token_sha256: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct RelayConfig {
    pub bind: SocketAddr,
    pub queue_capacity: usize,
    pub rooms: Vec<RoomConfig>,
}

impl Default for RelayConfig {
    fn default() -> Self {
        Self {
            bind: SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 8080),
            queue_capacity: 8,
            rooms: Vec::new(),
        }
    }
}

#[derive(Debug, thiserror::Error)]
#[error("invalid relay configuration")]
pub struct ConfigError;

pub(crate) fn valid_room(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

pub(crate) fn parse_hash(hash: &str) -> Result<[u8; 32], ConfigError> {
    if hash.len() != 64
        || !hash
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(ConfigError);
    }
    let mut bytes = [0; 32];
    hex::decode_to_slice(hash, &mut bytes).map_err(|_| ConfigError)?;
    Ok(bytes)
}
