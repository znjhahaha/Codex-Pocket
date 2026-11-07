use anyhow::{bail,Context,Result};
use serde_json::Value;
use std::path::{Path,PathBuf};
use crate::storage;

/// Read only the helper's known private pairing payload.
pub fn read_payload(state:&Path,response:&Value,now:u64)->Result<Value>{
    let expected=state.join("pairing-qr.json");
    let path=PathBuf::from(response["qrPath"].as_str().context("Missing QR path")?);
    if path!=state.join("pairing-qr.svg"){bail!("Unexpected QR path")}
    if let Some(path)=response.get("payloadPath") {
        if path.as_str().map(Path::new)!=Some(expected.as_path()){bail!("Unexpected payload path")}
    }
    let payload:Value=storage::read(&expected)?;
    let expires=response["expiresAt"].as_u64().context("Missing expiry")?;
    if expires<=now||expires>now+600{bail!("Invalid expiry")}
    if payload["v"]!=1||payload["expiresAt"].as_u64()!=Some(expires){bail!("Pairing payload changed; refresh the code")}
    Ok(payload)
}
