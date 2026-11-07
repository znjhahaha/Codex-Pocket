use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
#[derive(Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub secret_hash: String,
    pub created_at: u64,
}
#[derive(Default, Serialize, Deserialize)]
pub struct DeviceStore {
    pub devices: BTreeMap<String, Device>,
}
pub fn hash(secret: &str) -> String {
    use sha2::Digest;
    hex::encode(sha2::Sha256::digest(secret.as_bytes()))
}
impl DeviceStore {
    pub fn verify(&self, id: &str, secret: &str) -> bool {
        use subtle::ConstantTimeEq;
        let candidate = hash(secret);
        self.devices
            .get(id)
            .is_some_and(|d| bool::from(d.secret_hash.as_bytes().ct_eq(candidate.as_bytes())))
    }
    pub fn add(&mut self, id: String, name: String, secret: &str, now: u64) -> Result<(), String> {
        if id.is_empty()
            || id.len() > 128
            || name.is_empty()
            || name.chars().count() > 64
            || name.chars().any(char::is_control)
        {
            return Err("Invalid device identity".into());
        }
        if self.devices.contains_key(&id) {
            return Err("Device identity already paired".into());
        }
        if self.devices.len() >= 8 {
            return Err("Maximum paired devices reached".into());
        }
        self.devices.insert(
            id.clone(),
            Device {
                id,
                name,
                secret_hash: hash(secret),
                created_at: now,
            },
        );
        Ok(())
    }
    pub fn revoke(&mut self, id: &str) -> bool {
        self.devices.remove(id).is_some()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paired_device_auth_and_revocation() {
        let mut s = DeviceStore::default();
        s.add("phone".into(), "Mate".into(), "a-secret", 1).unwrap();
        assert!(s.verify("phone", "a-secret"));
        assert!(!s.verify("phone", "wrong"));
        assert!(!s.verify("other", "a-secret"));
        s.revoke("phone");
        assert!(!s.verify("phone", "a-secret"));
    }
    #[test]
    fn pairing_cannot_replace_an_existing_device() {
        let mut s = DeviceStore::default();
        s.add("phone".into(), "Mate".into(), "first", 1).unwrap();
        assert!(s.add("phone".into(), "Other".into(), "second", 2).is_err());
    }
}
