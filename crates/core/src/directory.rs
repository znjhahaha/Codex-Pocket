use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ThreadKey {
    pub computer_id: String,
    pub config_store_id: String,
    pub native_thread_id: String,
}
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RuntimeId {
    pub computer_id: String,
    pub runtime_id: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum RuntimeSource {
    Desktop,
    Cli,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeAdvertisement {
    pub id: RuntimeId,
    pub source: RuntimeSource,
    pub config_store_id: String,
    pub online: bool,
    pub loaded_native_thread_ids: BTreeSet<String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ResolveError {
    InvalidIdentity,
    ConflictingAdvertisement(RuntimeId),
    NoOnlineOwner,
    UncertainOwner,
    AmbiguousOwners,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HistoryThread {
    pub key: ThreadKey,
    pub write_owner: Result<RuntimeId, ResolveError>,
}
/// An immutable, caller-refreshed advertisement snapshot. Config identities must
/// already be canonical: this module never guesses equivalence from paths.
pub struct RuntimeDirectory {
    runtimes: BTreeMap<RuntimeId, RuntimeAdvertisement>,
}
impl RuntimeDirectory {
    /// Identical adverts are idempotent; any disagreement for one runtime ID
    /// invalidates construction, including disagreements about offline state.
    pub fn new(
        adverts: impl IntoIterator<Item = RuntimeAdvertisement>,
    ) -> Result<Self, ResolveError> {
        let mut runtimes = BTreeMap::new();
        for advert in adverts {
            if !advert.id.is_valid()
                || advert.config_store_id.is_empty()
                || advert.loaded_native_thread_ids.iter().any(String::is_empty)
            {
                return Err(ResolveError::InvalidIdentity);
            }
            if let Some(old) = runtimes.get(&advert.id) {
                if old != &advert {
                    return Err(ResolveError::ConflictingAdvertisement(advert.id));
                }
            } else {
                runtimes.insert(advert.id.clone(), advert);
            }
        }
        Ok(Self { runtimes })
    }
    pub fn resolve_write(&self, key: &ThreadKey) -> Result<RuntimeId, ResolveError> {
        if key.computer_id.is_empty()
            || key.config_store_id.is_empty()
            || key.native_thread_id.is_empty()
        {
            return Err(ResolveError::InvalidIdentity);
        }
        let claims: Vec<_> = self
            .runtimes
            .values()
            .filter(|a| {
                a.id.computer_id == key.computer_id
                    && a.config_store_id == key.config_store_id
                    && a.loaded_native_thread_ids.contains(&key.native_thread_id)
            })
            .collect();
        // Transport loss is not evidence that the native engine released a
        // thread. Keep the claim until an authoritative inventory releases it.
        if claims.iter().any(|a| !a.online) {
            return Err(ResolveError::UncertainOwner);
        }
        let mut owners = claims.into_iter();
        let owner = owners.next().ok_or(ResolveError::NoOnlineOwner)?;
        if owners.next().is_some() {
            return Err(ResolveError::AmbiguousOwners);
        }
        Ok(owner.id.clone())
    }
    /// History membership never implies ownership; each row resolves separately.
    pub fn list_history(&self, keys: impl IntoIterator<Item = ThreadKey>) -> Vec<HistoryThread> {
        keys.into_iter()
            .map(|key| HistoryThread {
                write_owner: self.resolve_write(&key),
                key,
            })
            .collect()
    }
}
impl RuntimeId {
    pub(crate) fn is_valid(&self) -> bool {
        !self.computer_id.is_empty() && !self.runtime_id.is_empty()
    }
}
