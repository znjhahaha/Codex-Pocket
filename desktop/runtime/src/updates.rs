//! Signed, immutable staging plus the shared checkpoint coordinator. Never
//! launches an installer, kills a process, or sends native/model requests.
use crate::{launcher, storage, supervisor::upgrade};
use anyhow::{ensure, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    io::{Read, Write},
    path::{Path, PathBuf},
    time::Duration,
};

pub(crate) const MAX_OFFER: u64 = 2 * 1024 * 1024;
const MAX_FILE: u64 = 512 * 1024 * 1024;
const MAX_TOTAL: u64 = 2 * 1024 * 1024 * 1024;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TrustPolicy {
    pub schema: u32,
    pub feed: String,
    pub public_key: String,
    pub allowed_hosts: Vec<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    payload: String,
    signature: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Offer {
    schema: u32,
    version: String,
    published_at: u64,
    expires_at: u64,
    windows_manifest: String,
    files: Vec<Location>,
    #[serde(default)]
    package: Option<PackageDownload>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct PackageDownload {
    pub url:String,
    pub bytes:u64,
    pub sha256:String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Location {
    path: String,
    url: String,
}
#[derive(Clone, Debug)]
pub struct Download {
    pub path: String,
    pub url: String,
    pub bytes: u64,
    pub sha256: String,
}
#[derive(Clone, Debug)]
pub struct VerifiedOffer {
    pub version: String,
    pub generation: String,
    pub manifest: Vec<u8>,
    pub files: Vec<Download>,
    pub package: Option<PackageDownload>,
}

fn hash(b: &[u8]) -> String {
    format!("{:x}", Sha256::digest(b))
}
fn digest(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn url_allowed(policy: &TrustPolicy, value: &str) -> bool {
    url::Url::parse(value).ok().is_some_and(|u| {
        u.scheme() == "https"
            && u.username().is_empty()
            && u.password().is_none()
            && u.fragment().is_none()
            && u.port_or_known_default() == Some(443)
            && u.host_str().is_some_and(|h| {
                policy
                    .allowed_hosts
                    .iter()
                    .any(|v| v.eq_ignore_ascii_case(h))
            })
    })
}
pub(crate) fn safe_path(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 240
        && !s.contains(['\\', ':'])
        && s.split('/').all(|p| {
            let stem = p.split('.').next().unwrap_or("").to_ascii_uppercase();
            !p.is_empty()
                && !p.ends_with(['.', ' '])
                && p != "."
                && p != ".."
                && p.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
                && !matches!(
                    stem.as_str(),
                    "CON"
                        | "PRN"
                        | "AUX"
                        | "NUL"
                        | "COM1"
                        | "COM2"
                        | "COM3"
                        | "COM4"
                        | "COM5"
                        | "COM6"
                        | "COM7"
                        | "COM8"
                        | "COM9"
                        | "LPT1"
                        | "LPT2"
                        | "LPT3"
                        | "LPT4"
                        | "LPT5"
                        | "LPT6"
                        | "LPT7"
                        | "LPT8"
                        | "LPT9"
                )
        })
}
pub fn verify_offer(
    policy: &TrustPolicy,
    bytes: &[u8],
    current_version: &str,
    now: u64,
) -> Result<VerifiedOffer> {
    verify_offer_for(policy,bytes,current_version,now,false)
}
pub(crate) fn verify_offer_for(policy:&TrustPolicy,bytes:&[u8],current_version:&str,now:u64,explicit_local:bool)->Result<VerifiedOffer> {
    ensure!(
        policy.schema == 1 && policy.allowed_hosts.len() <= 16 && url_allowed(policy, &policy.feed),
        "Invalid update trust policy"
    );
    ensure!(
        bytes.len() as u64 <= MAX_OFFER,
        "Update offer exceeds limit"
    );
    let e: Envelope = serde_json::from_slice(bytes)?;
    let payload = STANDARD.decode(e.payload)?;
    let signature = STANDARD.decode(e.signature)?;
    let key = STANDARD.decode(&policy.public_key)?;
    ensure!(
        key.len() == 32 && signature.len() == 64,
        "Invalid update signature encoding"
    );
    ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, &key)
        .verify(&payload, &signature)
        .map_err(|_| anyhow::anyhow!("Update signature rejected"))?;
    let offer: Offer = serde_json::from_slice(&payload)?;
    ensure!(
        offer.schema == 1
            && offer.published_at <= now.saturating_add(300)
            && (explicit_local || offer.expires_at > now)
            && offer.expires_at > offer.published_at
            && offer.expires_at - offer.published_at <= 31 * 86400,
        "Update offer is expired or invalid"
    );
    let version = semver::Version::parse(&offer.version)?;
    if let Some(package)=&offer.package {
        ensure!(url_allowed(policy,&package.url)&&package.bytes>0&&package.bytes<=MAX_TOTAL&&digest(&package.sha256),"Invalid signed Velopack package");
    }
    ensure!(
        version > semver::Version::parse(current_version)? && (explicit_local || version.pre.is_empty()),
        "Update must be newer; prereleases require an explicit local installer"
    );
    let manifest = STANDARD.decode(offer.windows_manifest)?;
    ensure!(manifest.len() <= 1024 * 1024, "Manifest exceeds limit");
    let inventory: Value = serde_json::from_slice(&manifest)?;
    ensure!(
        inventory["version"].as_str() == Some(&offer.version),
        "Signed version differs from inventory"
    );
    let entries = inventory["files"]
        .as_array()
        .context("Missing update inventory")?;
    ensure!(
        !entries.is_empty() && entries.len() <= 2048 && entries.len() == offer.files.len(),
        "Update inventory is incomplete"
    );
    let mut names = BTreeSet::new();
    let mut total = 0u64;
    let mut downloads = Vec::new();
    for entry in entries {
        let path = entry["path"].as_str().context("Missing inventory path")?;
        ensure!(
            safe_path(path)
                && path != "windows-manifest.json"
                && names.insert(path.to_ascii_lowercase()),
            "Unsafe or duplicate inventory path"
        );
        let length = entry["bytes"]
            .as_u64()
            .filter(|n| *n > 0 && *n <= MAX_FILE)
            .context("Invalid inventory size")?;
        total = total.checked_add(length).context("Update size overflow")?;
        ensure!(total <= MAX_TOTAL, "Update exceeds total size limit");
        let sha = entry["sha256"]
            .as_str()
            .filter(|s| digest(s))
            .context("Invalid inventory digest")?;
        let locations: Vec<_> = offer.files.iter().filter(|f| f.path == path).collect();
        ensure!(
            locations.len() == 1 && url_allowed(policy, &locations[0].url),
            "Download origin is not trusted"
        );
        downloads.push(Download {
            path: path.into(),
            url: locations[0].url.clone(),
            bytes: length,
            sha256: sha.into(),
        });
    }
    for name in [
        "pocket-desktop.exe",
        "pocket-supervisor.exe",
        "pocket-agent.exe",
        "pocket-bridge.exe",
        "pocket-upgrade.exe",
    ] {
        ensure!(names.contains(name), "Required update component is missing");
    }
    Ok(VerifiedOffer {
        version: offer.version,
        generation: hash(&manifest),
        manifest,
        files: downloads,
        package:offer.package,
    })
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub automatic: bool,
}
impl Default for Settings {
    fn default() -> Self {
        Self { automatic: true }
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UpdateStatus {
    pub schema: u32,
    pub operation_id: String,
    pub phase: String,
    pub updated_at: u64,
    pub version: Option<String>,
    pub generation: Option<String>,
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    pub failure: Option<String>,
    pub handoff_operation_id: Option<String>,
    pub previous_record: Option<Value>,
    pub candidate_record_hash: Option<String>,
    pub previous_record_hash: Option<String>,
    #[serde(default)]
    pub explicit_install: bool,
}
impl Default for UpdateStatus {
    fn default() -> Self {
        Self {
            schema: 1,
            operation_id: uuid::Uuid::new_v4().to_string(),
            phase: "idle".into(),
            updated_at: 0,
            version: None,
            generation: None,
            downloaded_bytes: 0,
            total_bytes: 0,
            failure: None,
            handoff_operation_id: None,
            previous_record: None,
            candidate_record_hash: None,
            previous_record_hash: None,
            explicit_install: false,
        }
    }
}
pub(crate) fn directory(state: &Path) -> PathBuf {
    storage::control(state).join("updates")
}
fn load_optional<T: serde::de::DeserializeOwned + Default>(p: &Path) -> Result<T> {
    storage::unredirected(p)?;
    if !p.try_exists()? {
        return Ok(T::default());
    }
    storage::read(p)
}
pub fn settings(state: &Path) -> Result<Settings> {
    load_optional(&directory(state).join("settings.json"))
}
pub fn set_automatic(state: &Path, automatic: bool) -> Result<()> {
    let dir = directory(state);
    storage::private_dir(&dir)?;
    storage::atomic(&dir.join("settings.json"), &Settings { automatic })
}
pub fn read_status(state: &Path) -> Result<UpdateStatus> {
    let s: UpdateStatus = load_optional(&directory(state).join("status.json"))?;
    ensure!(
        s.schema == 1 && upgrade::local::canonical_uuid(&s.operation_id),
        "Invalid update status"
    );
    Ok(s)
}
pub(crate) fn save(state: &Path, s: &mut UpdateStatus, phase: &str) -> Result<()> {
    s.phase = phase.into();
    s.updated_at = storage::now();
    storage::private_dir(&directory(state))?;
    storage::atomic(&directory(state).join("status.json"), s)
}
pub fn public_status(state: &Path) -> Value {
    match read_status(state) {
        Ok(mut s) => {
            let native = crate::native_update::read_status(state);
            if native.is_err() {
                s.phase = "failed".into();
                s.failure = Some("native_update_state_invalid".into());
            }
            if let Ok(Some(native)) = native {
                if s.handoff_operation_id.as_deref() == Some(&native.handoff_id) {
                    match native.phase.as_str() {
                        "succeeded" => s.phase = "succeeded".into(),
                        "rolled_back" => {
                            s.phase = "rolled_back".into();
                            s.failure = Some("native_restart_rolled_back".into());
                        }
                        "waiting_idle" => {
                            s.phase = "waiting_idle".into();
                            s.failure = Some("native_restart_required".into());
                        }
                        "recovery_required" => {
                            s.phase = "failed".into();
                            s.failure = Some("native_restart_recovery_required".into());
                        }
                        _ => s.phase = "switching".into(),
                    }
                }
            }
            let restart_requested=crate::local_update::restart_requested(state,&s);
            serde_json::json!({"restartRequested":restart_requested,"automatic":settings(state).map(|v|v.automatic).unwrap_or(false),"phase":s.phase,"operationId":s.operation_id,"updatedAt":s.updated_at,"version":s.version,"generation":s.generation,"downloadedBytes":s.downloaded_bytes,"totalBytes":s.total_bytes,"failure":s.failure.filter(|reason|!matches!(reason.as_str(), "cold_launch_pending" | "cold_rollback_pending")),"check":feed_check(state),"manager":crate::manager_update::public_status(state),"coordinator":crate::local_update::public_status(state,&s.operation_id)})
        }
        Err(_) => {
            serde_json::json!({"phase":"failed","failure":"update_state_invalid","automatic":false})
        }
    }
}
pub(crate) fn trust(bin: &Path, state: &Path) -> Result<TrustPolicy> {
    let inventory: Value = storage::read(&bin.join("windows-manifest.json"))?;
    if inventory["files"].as_array().is_some_and(|files| {
        files
            .iter()
            .any(|f| f["path"] == "windows-update-trust.json")
    }) {
        // A corrupt installed anchor must never fall back to another policy.
        return storage::read(&launcher::verified_exe(bin, "windows-update-trust.json")?);
    }
    let mut file = crate::supervisor::maintenance::open_agent_private(
        &directory(state).join("bootstrap-trust.json"),
    )?;
    let mut bytes = Vec::new();
    Read::by_ref(&mut file)
        .take(16385)
        .read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= 16384, "Bootstrap trust exceeds limit");
    let record: BootstrapTrust = serde_json::from_slice(&bytes)?;
    ensure!(
        record.schema == 1 && record.policy.public_key == record.authorized_public_key,
        "Invalid local trust authorization"
    );
    Ok(record.policy)
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct BootstrapTrust {
    schema: u32,
    policy: TrustPolicy,
    authorized_public_key: String,
    signed_offer_sha256: String,
}

/// The anchor is supplied by a trusted LOCAL installer/operator, never extracted
/// from an offer or HTTPS response. The signed manifest must bind the policy too.
pub fn verify_local_candidate(
    source: &Path,
    bytes: &[u8],
    authorized_key: &str,
    current: &str,
    now: u64,
) -> Result<VerifiedOffer> {
    ensure!(
        STANDARD.decode(authorized_key)?.len() == 32,
        "Explicit local update key required"
    );
    let path = source.join("windows-update-trust.json");
    storage::unredirected(&path)?;
    let policy_bytes = std::fs::read(path)?;
    ensure!(policy_bytes.len() <= 16384, "Trust policy exceeds limit");
    let policy: TrustPolicy = serde_json::from_slice(&policy_bytes)?;
    ensure!(
        policy.public_key == authorized_key,
        "Local update key is not authorized"
    );
    // An explicitly opened, pinned installer remains usable offline after the
    // network feed's freshness window. Signature, version and inventory remain
    // mandatory; network checks always use the strict expiring entry point.
    let offer = verify_offer_for(&policy, bytes, current, now, true)?;
    let signed_policy = offer
        .files
        .iter()
        .find(|f| f.path == "windows-update-trust.json")
        .context("Signed policy missing")?;
    ensure!(
        signed_policy.bytes == policy_bytes.len() as u64
            && signed_policy.sha256 == hash(&policy_bytes),
        "Signed policy differs"
    );
    ensure!(
        std::fs::read(source.join("windows-manifest.json"))? == offer.manifest,
        "Local manifest differs from signed offer"
    );
    Ok(offer)
}

/// Stages a locally supplied signed payload and provisions its initial public
/// anchor under current-user ACLs. No process/registry action and no network.
pub fn stage_local(
    root: &Path,
    state: &Path,
    source: &Path,
    offer_path: &Path,
    explicit_key: Option<&str>,
) -> Result<()> {
    storage::private_dir(&directory(state))?;
    // A predecessor coordinator may be in its short critical section. Wait for
    // a safe staging boundary instead of failing the Velopack installation hook.
    let deadline = std::time::Instant::now() + Duration::from_secs(90);
    let lock_path = directory(state).join("worker.lock");
    let (_lock, previous) = loop {
        match storage::Lock::acquire(&lock_path) {
            Ok(lock) => {
                let previous = read_status(state)?;
                if previous.phase != "restarting_legacy" { break (lock, previous) }
                // The old setup can be cancelling a fence. It must finish before
                // the new signed operation can replace its intent and offer.
                drop(lock);
                ensure!(std::time::Instant::now() < deadline, "Previous Pocket restart is still active");
            }
            Err(e) if crate::local_update::lock_contended(&e) && std::time::Instant::now() < deadline => {}
            Err(e) => return Err(e),
        }
        std::thread::sleep(Duration::from_millis(150));
    };
    let (bin, s) = launcher::resolve_release(root)?;
    ensure!(s == state, "Installation changed");
    let recover_build = previous.phase=="cold_starting"
        && crate::local_update::cold_build_mismatch(root,state,&previous).unwrap_or(false);
    ensure!(
        previous.phase!="switching" && (previous.phase!="cold_starting" || recover_build),
        "Handoff already pending"
    );
    if let Some(native) = crate::native_update::read_status(state)? {
        ensure!(
            ["succeeded", "rolled_back"].contains(&native.phase.as_str()),
            "Native recovery is still pending"
        );
    }
    let inventory: Value = storage::read(&bin.join("windows-manifest.json"))?;
    let has_anchor = inventory["files"].as_array().is_some_and(|files| {
        files
            .iter()
            .any(|f| f["path"] == "windows-update-trust.json")
    }) || directory(state).join("bootstrap-trust.json").try_exists()?;
    let existing = if has_anchor {
        Some(trust(&bin, state)?)
    } else {
        None
    };
    let key = match (existing.as_ref(), explicit_key) {
        (Some(policy), Some(key)) => {
            ensure!(
                policy.public_key == key,
                "Installed key rotation requires its existing signature"
            );
            key
        }
        (Some(policy), None) => &policy.public_key,
        (None, Some(key)) => key,
        _ => anyhow::bail!("An independently authorized local public key is required"),
    };
    storage::unredirected(offer_path)?;
    let bytes = std::fs::read(offer_path)?;
    let manifest: Value = storage::read(&bin.join("windows-manifest.json"))?;
    let offer = verify_local_candidate(
        source,
        &bytes,
        key,
        manifest["version"]
            .as_str()
            .context("Installed version missing")?,
        storage::now(),
    )?;
    if recover_build {ensure!(previous.generation.as_deref()!=Some(&offer.generation),"The mismatched build cannot repair itself");}
    stage_with(root, &offer, |file, path| {
        let original = source.join(&file.path);
        storage::unredirected(&original)?;
        std::fs::copy(original, path)?;
        Ok(())
    })?;
    if previous.explicit_install
        && matches!(previous.phase.as_str(), "staged" | "waiting_idle" | "waiting_cold_start")
        && previous.generation.as_deref() == Some(offer.generation.as_str())
    {
        // The package was re-verified above. Keep the existing coordinator and
        // its journal, including the most recent wait reason.
        return Ok(());
    }
    let policy: TrustPolicy = storage::read(
        &root
            .join("bin")
            .join(&offer.generation)
            .join("windows-update-trust.json"),
    )?;
    if existing.is_none() {
        storage::atomic(
            &directory(state).join("bootstrap-trust.json"),
            &BootstrapTrust {
                schema: 1,
                policy,
                authorized_public_key: key.into(),
                signed_offer_sha256: hash(&bytes),
            },
        )?;
    }
    if recover_build {
        archive_failed_build(state, &previous)?;
    }
    storage::atomic(
        &directory(state).join("offer.json"),
        &serde_json::from_slice::<Value>(&bytes)?,
    )?;
    let mut s = UpdateStatus {
        explicit_install: true,
        version: Some(offer.version),
        generation: Some(offer.generation),
        downloaded_bytes: offer.files.iter().map(|f| f.bytes).sum(),
        total_bytes: offer.files.iter().map(|f| f.bytes).sum(),
        ..Default::default()
    };
    // Reserve the restart coordinator before an older supervisor can observe
    // this explicit install and publish a hot-handoff pointer.
    save(state, &mut s, "waiting_cold_start")
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct FailedBuildArchive {
    reason: String,
    status: UpdateStatus,
    offer: Value,
}

fn validate_failed_build_archive(archive: &FailedBuildArchive, previous: &UpdateStatus) -> Result<()> {
    let generation = previous.generation.as_deref().filter(|g| digest(g))
        .context("Missing failed build generation")?;
    let version = previous.version.as_deref().filter(|v| !v.is_empty())
        .context("Missing failed build version")?;
    ensure!(archive.reason == "cold_build_identity_mismatch"
        && archive.status.schema == previous.schema
        && archive.status.operation_id == previous.operation_id
        && archive.status.generation == previous.generation
        && archive.status.version == previous.version
        && archive.status.phase == "cold_starting"
        && archive.status.failure.as_deref() == Some("cold_launch_pending"),
        "Failed build archive belongs to another update");
    // Bind the saved envelope to the old manifest identity. New candidate signature
    // admission remains in verify_local_candidate; archival never admits a release.
    let envelope: Envelope = serde_json::from_value(archive.offer.clone())?;
    let offer: Offer = serde_json::from_slice(&STANDARD.decode(envelope.payload)?)?;
    let manifest = STANDARD.decode(offer.windows_manifest)?;
    let inventory: Value = serde_json::from_slice(&manifest)?;
    ensure!(offer.version == version && inventory["version"] == version && hash(&manifest) == generation,
        "Failed build archive offer differs from its generation");
    Ok(())
}

/// stage_local holds worker.lock. Preserve the first complete archive across a
/// crash after publishing offer.json but before committing the new status.json.
fn archive_failed_build(state: &Path, previous: &UpdateStatus) -> Result<()> {
    let operation = uuid::Uuid::parse_str(&previous.operation_id)?;
    let path = directory(state).join(format!("failed-build-{operation}.json"));
    storage::unredirected(&path)?;
    if path.try_exists()? {
        return validate_failed_build_archive(&storage::read(&path)?, previous);
    }
    let archive = FailedBuildArchive {
        reason: "cold_build_identity_mismatch".into(),
        status: previous.clone(),
        offer: storage::read(&directory(state).join("offer.json"))?,
    };
    validate_failed_build_archive(&archive, previous)?;
    let mut file = tempfile::NamedTempFile::new_in(directory(state))?;
    storage::unredirected(file.path())?;
    file.write_all(&serde_json::to_vec_pretty(&archive)?)?;
    file.as_file().sync_all()?;
    match file.persist_noclobber(&path) {
        Ok(_) => Ok(()),
        Err(error) if error.error.kind() == std::io::ErrorKind::AlreadyExists => {
            validate_failed_build_archive(&storage::read(&path)?, previous)
        }
        Err(error) => Err(error.error.into()),
    }
}

/// Explicit local installation dispatches the authenticated Pocket restart
/// fence. Automatic updates retain the checkpoint handoff path.
pub fn apply_local(root: &Path, state: &Path) -> Result<bool> {
    if crate::local_update::apply_requested(state, &read_status(state)?) {
        crate::local_update::resume_from_manager(root, state)?;
        return Ok(false);
    }
    apply_local_observed(root, state).map(|(applied, _)| applied)
}

pub(crate) fn apply_local_observed(root: &Path, state: &Path) -> Result<(bool, Option<&'static str>)> {
    let h = crate::process::local_control(state, "status", serde_json::json!({}))?;
    let legacy_background_only = h.get("lifecycle").is_none();
    let reason = crate::diagnostics::update_wait_reason(&h, storage::now());
    // Old brokers cannot checkpoint ambiguous receipts. A new coordinator owns
    // cold activation at next logon without replaying those receipts.
    if explicit_cold_required(&h, storage::now()) {
        let _lock = storage::Lock::acquire(&directory(state).join("worker.lock"))?;
        let mut status = read_status(state)?;
        if crate::local_update::apply_requested(state,&status) && matches!(status.phase.as_str(), "staged" | "waiting_idle" | "waiting_cold_start") {
            if status.phase != "waiting_cold_start" { save(state, &mut status, "waiting_cold_start")?; }
            return Ok((false, Some("legacy_restart_pending")));
        }
    }
    let applied = switch(root, state, &h, legacy_background_only)?;
    Ok((applied, reason.filter(|_| !applied)))
}
pub(crate) fn explicit_cold_required(health: &Value, now: u64) -> bool {
    let live = &health["lifecycle"];
    health["protocol"] == 1 && health["acceptingWrites"] == true
        && crate::connection::fresh(health["updatedAt"].as_u64().unwrap_or(0), now)
        && crate::connection::fresh(live["observedAt"].as_u64().unwrap_or(0), now)
        && live["observedAt"].as_u64() >= health["updatedAt"].as_u64()
        && live["nativeTasksPreserved"] == true && live["idle"] == false
        && live.get("checkpointableUnknownOperations").is_none()
        && live["unknownOperations"].as_u64().is_some_and(|n| n > 0)
        && ["activeTurns", "pendingQuestions", "pendingWrites"].iter().all(|field| live[field].as_u64() == Some(0))
}
fn client(policy: &TrustPolicy) -> Result<reqwest::blocking::Client> {
    let policy = policy.clone();
    Ok(reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(120))
        .redirect(reqwest::redirect::Policy::custom(move |attempt| {
            if attempt.previous().len() >= 5 || !url_allowed(&policy, attempt.url().as_str()) {
                attempt.error("Untrusted update redirect")
            } else {
                attempt.follow()
            }
        }))
        .build()?)
}
fn response(
    client: &reqwest::blocking::Client,
    url: &str,
    limit: u64,
) -> Result<reqwest::blocking::Response> {
    let r = client
        .get(url)
        .header("User-Agent", "Codex-Pocket-Updater")
        .send()?
        .error_for_status()?;
    ensure!(
        r.content_length().is_none_or(|n| n <= limit),
        "Download exceeds limit"
    );
    Ok(r)
}
/// Each file is hashed while streaming and the whole exact inventory is reread
/// before promotion. Staging never modifies the installed record or user state.
pub fn stage_with(
    root: &Path,
    offer: &VerifiedOffer,
    mut download: impl FnMut(&Download, &Path) -> Result<()>,
) -> Result<PathBuf> {
    let parent = root.join("updates");
    storage::private_dir(&parent)?;
    let staged = tempfile::Builder::new()
        .prefix("stage-")
        .tempdir_in(&parent)?;
    storage::unredirected(staged.path())?;
    for file in &offer.files {
        let path = staged.path().join(&file.path);
        storage::private_dir(path.parent().context("Missing staging parent")?)?;
        download(file, &path)?;
        storage::unredirected(&path)?;
        let mut f = std::fs::File::open(&path)?;
        ensure!(
            f.metadata()?.len() == file.bytes,
            "Downloaded length mismatch"
        );
        let mut h = Sha256::new();
        let mut buf = [0; 65536];
        loop {
            let n = f.read(&mut buf)?;
            if n == 0 {
                break;
            }
            h.update(&buf[..n]);
        }
        ensure!(
            format!("{:x}", h.finalize()) == file.sha256,
            "Downloaded digest mismatch"
        );
    }
    let mut manifest = std::fs::File::create(staged.path().join("windows-manifest.json"))?;
    manifest.write_all(&offer.manifest)?;
    manifest.sync_all()?;
    drop(manifest);
    upgrade::local::verify_generation_inventory(staged.path())?;
    let bin = root.join("bin");
    storage::private_dir(&bin)?;
    let destination = bin.join(&offer.generation);
    storage::unredirected(&destination)?;
    if destination.try_exists()? {
        ensure!(
            crate::installation::generation(&destination)? == offer.generation,
            "Existing staged generation changed"
        );
        upgrade::local::verify_generation_inventory(&destination)?;
        return Ok(destination);
    }
    std::fs::rename(staged.path(), &destination).context("Promote verified update staging")?;
    Ok(destination)
}

fn feed_check(state: &Path) -> Value {
    storage::read::<Value>(&directory(state).join("check.json"))
        .map(|s|serde_json::json!({"phase":s["phase"],"updatedAt":s["updatedAt"],"failure":s["failure"],"httpStatus":s["httpStatus"]}))
        .unwrap_or(Value::Null)
}
fn save_feed_check(state: &Path, phase: &str, failure: Option<&str>, error: Option<&anyhow::Error>) -> Result<()> {
    let http_status=error.and_then(|e|e.chain().find_map(|cause|cause.downcast_ref::<reqwest::Error>()
        .and_then(reqwest::Error::status).map(|status|status.as_u16())));
    storage::atomic(&directory(state).join("check.json"),&serde_json::json!({"phase":phase,
        "updatedAt":storage::now(),"failure":failure,"httpStatus":http_status}))
}

pub fn check_and_stage(root: &Path, state: &Path) -> Result<()> {
    storage::private_dir(&directory(state))?;
    let _lock = storage::Lock::acquire(&directory(state).join("worker.lock"))?;
    let mut status = read_status(state)?;
    if matches!(
        status.phase.as_str(),
        "staged" | "waiting_idle" | "waiting_cold_start" | "switching" | "restarting_legacy" | "cold_starting"
    ) {
        return Ok(());
    }
    let prior_operation=status.operation_id.clone();
    let retain_outcome=status.generation.is_some()
        && matches!(status.phase.as_str(),"succeeded"|"current"|"rolled_back"|"failed");
    let (bin, recorded) = launcher::resolve_release(root)?;
    ensure!(recorded == state, "Installation state changed");
    save_feed_check(state,"checking",None,None)?;
    let policy = match trust(&bin, state) {
        Ok(v) => v,
        Err(error) => {
            save_feed_check(state,"failed",Some("update_trust_unavailable"),Some(&error))?;
            if retain_outcome { return Err(error); }
            status.failure = Some("update_trust_unavailable".into());
            return save(state, &mut status, "unavailable");
        }
    };
    let current: Value = storage::read(&bin.join("windows-manifest.json"))?;
    let previous_failed = status
        .generation
        .clone()
        .filter(|_| matches!(status.phase.as_str(), "rolled_back" | "failed"));
    let mut check_failure=None;
    let result = (|| {
        // A feed lookup is not a retry of the last installed generation. Keep
        // its terminal receipt until a different signed candidate is admitted.
        if !retain_outcome { save(state, &mut status, "checking")?; }
        ensure!(url_allowed(&policy, &policy.feed), "Feed is not trusted");
        let http = client(&policy)?;
        let mut bytes = Vec::new();
        response(&http, &policy.feed, MAX_OFFER)?
            .take(MAX_OFFER + 1)
            .read_to_end(&mut bytes)?;
        let envelope: Envelope = serde_json::from_slice(&bytes)?;
        // An equal signed stable offer is up-to-date, still signature checked.
        let current_version = current["version"]
            .as_str()
            .context("Installed version missing")?;
        let installed = semver::Version::parse(current_version)?;
        let baseline = if installed.patch > 0 {
            semver::Version::new(installed.major, installed.minor, installed.patch - 1).to_string()
        } else {
            "0.0.0".into()
        };
        let signed = envelope_value(envelope);
        let candidate = verify_offer(
            &policy,
            &serde_json::to_vec(&signed)?,
            &baseline,
            storage::now(),
        )?;
        if semver::Version::parse(&candidate.version)? == installed {
            ensure!(candidate.generation==crate::installation::generation(&bin)?,"Same version has a different signed build identity");
            if retain_outcome { return Ok(()); }
            status.failure = None;
            return save(state, &mut status, "current");
        }
        ensure!(
            semver::Version::parse(&candidate.version)? > installed,
            "Downgrade refused"
        );
        if previous_failed.as_deref() == Some(&candidate.generation) {
            check_failure=Some("candidate_previously_failed");
            return Ok(());
        }
        status = UpdateStatus {
            version: Some(candidate.version.clone()),
            generation: Some(candidate.generation.clone()),
            total_bytes: candidate.package.as_ref().map(|p|p.bytes).unwrap_or_else(||candidate.files.iter().map(|f| f.bytes).sum()),
            ..Default::default()
        };
        storage::atomic(&directory(state).join("offer.json"), &signed)?;
        save(state, &mut status, "downloading")?;
        if let Some(package)=&candidate.package {
            let mut staged=tempfile::NamedTempFile::new_in(directory(state))?;
            let mut stream=response(&http,&package.url,package.bytes)?.take(package.bytes+1);
            let copied=std::io::copy(&mut stream,&mut staged)?;
            ensure!(copied==package.bytes,"Package download length mismatch");
            staged.as_file().sync_all()?;
            crate::package_update::stage(root,&candidate,staged.path())?;
            crate::manager_update::retain_package(state,&candidate,staged.path())?;
            status.downloaded_bytes=copied;
        }else{stage_with(root, &candidate, |file, path| {
            let mut stream = response(&http, &file.url, file.bytes)?.take(file.bytes + 1);
            let mut output = std::fs::File::create(path)?;
            let bytes = std::io::copy(&mut stream, &mut output)?;
            ensure!(bytes == file.bytes, "Download length mismatch");
            output.sync_all()?;
            status.downloaded_bytes += bytes;
            save(state, &mut status, "downloading")?;
            Ok(())
        })?;}
        status.failure = None;
        save(state, &mut status, "staged")
    })();
    if let Err(error)=&result {
        save_feed_check(state,"failed",Some("update_verification_or_download_failed"),Some(error))?;
        if !retain_outcome || status.operation_id != prior_operation {
            status.failure = Some("update_verification_or_download_failed".into());
            save(state, &mut status, "failed")?;
        }
    } else {
        save_feed_check(state,if check_failure.is_some(){"failed"}else{"completed"},check_failure,None)?;
    }
    result
}
fn envelope_value(e: Envelope) -> Value {
    serde_json::json!({"payload":e.payload,"signature":e.signature})
}

/// Publishes a generation only after idle evidence; the existing coordinator
/// independently verifies exact current-user identities, fences, checkpoints,
/// and preserves native tasks. The stable launcher itself is never replaced.
pub fn switch_if_idle(root: &Path, state: &Path, health: &Value) -> Result<bool> {
    switch(root, state, health, false)
}
fn switch(
    root: &Path,
    state: &Path,
    health: &Value,
    local_background_bootstrap: bool,
) -> Result<bool> {
    storage::private_dir(&directory(state))?;
    let _lock = storage::Lock::acquire(&directory(state).join("worker.lock"))?;
    let mut s = read_status(state)?;
    // Explicit installs and restart clicks own the fenced restart route. The
    // automatic worker must not race that coordinator with a hot handoff.
    if crate::local_update::apply_requested(state, &s) { return Ok(false); }
    if (!settings(state)?.automatic && !crate::local_update::apply_requested(state,&s)) || !matches!(s.phase.as_str(), "staged" | "waiting_idle") {
        return Ok(false);
    }
    if !local_background_bootstrap && !crate::diagnostics::ready_for_helper_handoff(health, storage::now()) {
        save(state, &mut s, "waiting_idle")?;
        return Ok(false);
    }
    let (bin, recorded) = launcher::resolve_release(root)?;
    ensure!(recorded == state, "Installation changed");
    let from = crate::installation::generation(&bin)?;
    let generation = s
        .generation
        .clone()
        .filter(|g| digest(g))
        .context("Missing staged generation")?;
    let policy = trust(&bin, state)?;
    let signed: Value = storage::read(&directory(state).join("offer.json"))?;
    let inventory: Value = storage::read(&bin.join("windows-manifest.json"))?;
    let verified = verify_offer_for(
        &policy,
        &serde_json::to_vec(&signed)?,
        inventory["version"]
            .as_str()
            .context("Missing installed version")?,
        storage::now(),
        s.explicit_install,
    )?;
    ensure!(
        verified.generation == generation,
        "Staged signature differs from journal"
    );
    let target = root.join("bin").join(&generation);
    ensure!(
        crate::installation::generation(&target)? == generation,
        "Staged generation changed"
    );
    upgrade::local::verify_generation_inventory(&target)?;
    ensure!(
        health["helperRuntime"]["generation"] == from
            && health["helperRuntime"]["controlProtocol"] == 1,
        "Live helper generation differs"
    );
    ensure!(
        crate::native_update::authenticated_helper(root, state, &from)?["helperRuntime"]
            == health["helperRuntime"],
        "Live helper process identity unverified"
    );
    let latest = crate::process::local_control(state, "status", serde_json::json!({}))?;
    if (!local_background_bootstrap
        && !crate::diagnostics::ready_for_helper_handoff(&latest, storage::now()))
        || latest["helperRuntime"] != health["helperRuntime"]
    {
        save(state, &mut s, "waiting_idle")?;
        return Ok(false);
    }
    let root_control = root.join("control");
    storage::private_dir(&root_control)?;
    let _installation = InstallMutex::acquire(root)?;
    {
        let _install = storage::Lock::acquire(&root_control.join("upgrade.lock"))?;
        if let Some(old) = upgrade::read_status(state)? {
            ensure!(
                old.phase.terminal()
                    && !matches!(
                        old.phase,
                        upgrade::Phase::Failed | upgrade::Phase::WaitingRestart
                    ),
                "Existing handoff needs recovery"
            );
        }
        let path = root.join("desktop-install.json");
        let old: Value = storage::read(&path)?;
        ensure!(
            old["generation"] == from
                && old["stateDirectory"].as_str().map(Path::new) == Some(state),
            "Install record changed"
        );
        let next = prepare_switch(&mut s, old, &generation)?;
        save(state, &mut s, "switching")?;
        storage::atomic(&path, &next)?;
    }
    match upgrade::request_upgrade(root, state, &from) {
        Ok(h) => {
            s.handoff_operation_id = Some(h.operation_id.clone());
            save(state, &mut s, "switching")?;
            drop(_lock);
            finish_handoff(root, state, &h)?;
            Ok(true)
        }
        Err(_) => {
            restore_record(root, &s)?;
            s.failure = Some("handoff_dispatch_failed".into());
            save(state, &mut s, "failed")?;
            Ok(false)
        }
    }
}
pub(crate) fn prepare_switch(status: &mut UpdateStatus, old: Value, generation: &str) -> Result<Value> {
    let mut next = old.clone();
    next["generation"] = Value::String(generation.into());
    next["releaseHash"] = Value::String(generation.into());
    status.previous_record_hash = Some(hash(&serde_json::to_vec(&old)?));
    status.previous_record = Some(old);
    status.candidate_record_hash = Some(hash(&serde_json::to_vec(&next)?));
    Ok(next)
}
pub(crate) fn restorable_record(root: &Path, s: &UpdateStatus) -> Result<Value> {
    let current: Value = storage::read(&root.join("desktop-install.json"))?;
    let old = s
        .previous_record
        .as_ref()
        .context("Missing previous installation record")?;
    let current_hash = hash(&serde_json::to_vec(&current)?);
    ensure!(
        Some(hash(&serde_json::to_vec(old)?)) == s.previous_record_hash,
        "Previous installation record changed"
    );
    if Some(&current_hash) == s.previous_record_hash.as_ref() {
        return Ok(old.clone());
    }
    ensure!(
        Some(current_hash) == s.candidate_record_hash,
        "Installation drift during rollback"
    );
    Ok(old.clone())
}
pub(crate) fn restore_record(root: &Path, s: &UpdateStatus) -> Result<()> {
    let old=restorable_record(root,s)?;
    storage::atomic(&root.join("desktop-install.json"), &old)?;
    if storage::local_app_data().is_ok_and(|local|root==local.join("CodexPocket")) {
        crate::native_entry::reconcile(root)?;
    }
    Ok(())
}
pub fn finish_handoff(root: &Path, state: &Path, h: &upgrade::UpgradeStatus) -> Result<()> {
    storage::private_dir(&directory(state))?;
    let _lock = storage::Lock::acquire(&directory(state).join("worker.lock"))?;
    let mut s = read_status(state)?;
    if s.phase != "switching" || s.generation.as_deref() != Some(&h.to_generation) {
        return Ok(());
    }
    ensure!(
        s.handoff_operation_id
            .as_ref()
            .is_none_or(|id| id == &h.operation_id),
        "Handoff operation differs"
    );
    match h.phase {
        upgrade::Phase::Succeeded => {
            s.failure = None;
            save(state, &mut s, "succeeded")
        }
        upgrade::Phase::WaitingRestart => {
            let _installation = InstallMutex::acquire(root)?;
            restore_record(root, &s)?;
            s.failure = None;
            save(state, &mut s, "waiting_cold_start")
        }
        upgrade::Phase::RolledBack => {
            let _installation = InstallMutex::acquire(root)?;
            restore_record(root, &s)?;
            s.failure = Some("handoff_rolled_back".into());
            save(state, &mut s, "rolled_back")
        }
        upgrade::Phase::Failed
            if matches!(
                h.failure,
                Some(
                    upgrade::Failure::CapabilityFailed
                        | upgrade::Failure::DispatchFailed
                        | upgrade::Failure::InstallationChanged
                )
            ) =>
        {
            let _installation = InstallMutex::acquire(root)?;
            restore_record(root, &s)?;
            s.failure = Some("handoff_failed_before_release".into());
            save(state, &mut s, "failed")
        }
        upgrade::Phase::Failed => {
            // Release/rollback may have happened. Surface the terminal error
            // without moving the pointer until recovery proves its owner.
            s.failure = Some("handoff_recovery_required".into());
            save(state, &mut s, "failed")
        }
        _ => Ok(()),
    }
}

/// Publication precedes coordinator creation. A crash in that narrow interval
/// must not leave a new install pointer with no recoverable handoff journal.
pub(crate) fn recover_unrequested_switch(root: &Path, state: &Path) -> Result<()> {
    if read_status(state)?.phase != "switching" { return Ok(()) }
    let _worker = storage::Lock::acquire(&directory(state).join("worker.lock"))?;
    let _installation = InstallMutex::acquire(root)?;
    storage::private_dir(&root.join("control"))?;
    let _coordinator = storage::Lock::acquire(&root.join("control/upgrade.lock"))?;
    let mut status = read_status(state)?;
    if status.phase != "switching" { return Ok(()) }
    if let Some(handoff) = upgrade::read_status(state)? {
        if status.generation.as_deref() == Some(&handoff.to_generation) { return Ok(()) }
        ensure!(handoff.phase.terminal(), "Another handoff still owns the installation");
    }
    ensure!(status.handoff_operation_id.is_none(), "Previously dispatched handoff journal missing");
    restore_record(root, &status)?;
    status.failure = Some("handoff_not_started".into());
    save(state, &mut status, "failed")
}

/// Shares the Windows installer's existing mutex, including abandoned-owner
/// recovery. Staging remains independent; publication/rollback are serialized.
#[cfg(windows)]
pub(crate) struct InstallMutex(windows_sys::Win32::Foundation::HANDLE);
#[cfg(windows)]
impl InstallMutex {
    pub(crate) fn acquire(root: &Path) -> Result<Self> {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::{Foundation::*, System::Threading::*};
        let normalized = root.to_string_lossy().replace('/', "\\").to_lowercase();
        let name = std::ffi::OsString::from(format!(
            "Local\\CodexPocketInstall_{}",
            hash(normalized.as_bytes())
        ));
        let wide: Vec<u16> = name.encode_wide().chain(Some(0)).collect();
        unsafe {
            let h = CreateMutexW(std::ptr::null(), 0, wide.as_ptr());
            ensure!(!h.is_null(), "Cannot open install mutex");
            if matches!(WaitForSingleObject(h, 0), WAIT_OBJECT_0 | WAIT_ABANDONED) {
                Ok(Self(h))
            } else {
                CloseHandle(h);
                anyhow::bail!("Installer is busy")
            }
        }
    }
}
#[cfg(windows)]
impl Drop for InstallMutex {
    fn drop(&mut self) {
        unsafe {
            windows_sys::Win32::System::Threading::ReleaseMutex(self.0);
            windows_sys::Win32::Foundation::CloseHandle(self.0);
        }
    }
}
#[cfg(not(windows))]
pub(crate) struct InstallMutex;
#[cfg(not(windows))]
impl InstallMutex {
    pub(crate) fn acquire(_: &Path) -> Result<Self> {
        Ok(Self)
    }
}

/// Called by the supervisor worker. Interrupted downloads can be retried;
/// interrupted switches are reconciled by their durable coordinator operation.
pub fn tick(root: &Path, state: &Path) -> Result<()> {
    // Legacy native-restart transactions require explicit recovery. New Pocket
    // updates never restart the official Codex desktop or its app-server.
    if crate::native_update::read_status(state)?.is_some_and(|n|!matches!(n.phase.as_str(),"succeeded"|"rolled_back")) {return Ok(())}
    recover_unrequested_switch(root,state)?;
    if let Some(h) = upgrade::read_status(state)? {
        finish_handoff(root, state, &h)?;
    }
    crate::manager_update::reconcile(root,state)?;
    let s = read_status(state)?;
    if matches!(s.phase.as_str(), "cold_starting" | "restarting_legacy" | "waiting_cold_start") { return Ok(()) }
    let staged = matches!(s.phase.as_str(), "staged" | "waiting_idle");
    if staged && crate::local_update::apply_requested(state,&s) { return Ok(()); }
    if !settings(state)?.automatic && !(crate::local_update::apply_requested(state,&s) && staged) {
        return Ok(());
    }
    if staged {
        let health = crate::process::local_control(state, "status", serde_json::json!({}))?;
        switch_if_idle(root, state, &health)?;
    } else if storage::now().saturating_sub(s.updated_at.max(feed_check(state)["updatedAt"].as_u64().unwrap_or(0))) >= 3600 {
        check_and_stage(root, state)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn failed_build_fixture(version: &str) -> (UpdateStatus, Value) {
        use ring::signature::Ed25519KeyPair;
        let signing = Ed25519KeyPair::from_seed_unchecked(&[51; 32]).unwrap();
        let manifest = serde_json::to_vec(&json!({"version":version,"buildId":version,"files":[]})).unwrap();
        let now = storage::now();
        let payload = serde_json::to_vec(&json!({"schema":1,"version":version,"publishedAt":now-1,
            "expiresAt":now+3600,"windowsManifest":STANDARD.encode(&manifest),"files":[]})).unwrap();
        let offer = json!({"payload":STANDARD.encode(&payload),"signature":STANDARD.encode(signing.sign(&payload).as_ref())});
        let status = UpdateStatus { explicit_install:true, phase:"cold_starting".into(),
            failure:Some("cold_launch_pending".into()), version:Some(version.into()), generation:Some(hash(&manifest)),
            updated_at:now-60, previous_record:Some(json!({"preserved":"predecessor"})), ..Default::default() };
        (status, offer)
    }

    #[test]
    fn failed_build_archive_survives_offer_publish_before_status_commit() {
        let temp = crate::test_support::tempdir();
        let state = temp.path();
        storage::private_dir(&directory(state)).unwrap();
        let (old, old_offer) = failed_build_fixture("0.7.1-rc.20261119.21");
        let (_, new_offer) = failed_build_fixture("0.7.1-rc.20261119.22");
        let _worker = storage::Lock::acquire(&directory(state).join("worker.lock")).unwrap();
        storage::atomic(&directory(state).join("status.json"), &old).unwrap();
        storage::atomic(&directory(state).join("offer.json"), &old_offer).unwrap();
        archive_failed_build(state, &old).unwrap();
        let path = directory(state).join(format!("failed-build-{}.json", old.operation_id));
        let archived = std::fs::read(&path).unwrap();
        let old_status = std::fs::read(directory(state).join("status.json")).unwrap();
        // Exactly stage_local's interruption boundary: the new offer is durable,
        // but save(waiting_cold_start) has not committed a new operation.
        storage::atomic(&directory(state).join("offer.json"), &new_offer).unwrap();
        archive_failed_build(state, &read_status(state).unwrap()).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), archived, "retry must preserve the first old status/offer archive");
        let saved: Value = storage::read(&path).unwrap();
        assert_eq!(saved["offer"], old_offer);
        assert_eq!(saved["status"], serde_json::to_value(&old).unwrap());
        assert_eq!(std::fs::read(directory(state).join("status.json")).unwrap(), old_status);
        assert_eq!(storage::read::<Value>(&directory(state).join("offer.json")).unwrap(), new_offer);
    }

    #[test]
    fn failed_build_archive_rejects_mismatched_existing_evidence_without_overwriting() {
        for mismatch in ["operation", "generation", "offer"] {
            let temp = crate::test_support::tempdir(); let state = temp.path();
            storage::private_dir(&directory(state)).unwrap();
            let (old, old_offer) = failed_build_fixture("0.7.1-rc.20261119.21");
            let (_, new_offer) = failed_build_fixture("0.7.1-rc.20261119.22");
            let mut archive = json!({"reason":"cold_build_identity_mismatch","status":old,"offer":old_offer});
            match mismatch {
                "operation" => archive["status"]["operationId"] = json!(uuid::Uuid::new_v4().to_string()),
                "generation" => archive["status"]["generation"] = json!("a".repeat(64)),
                _ => archive["offer"] = new_offer,
            }
            let path = directory(state).join(format!("failed-build-{}.json", old.operation_id));
            storage::atomic(&path, &archive).unwrap();
            storage::atomic(&directory(state).join("offer.json"), &old_offer).unwrap();
            let before = std::fs::read(&path).unwrap();
            assert!(archive_failed_build(state, &old).is_err(), "{mismatch} must not be accepted");
            assert_eq!(std::fs::read(&path).unwrap(), before);
        }
    }

    #[test]
    fn failed_build_archive_cannot_create_old_evidence_from_a_new_offer() {
        let temp = crate::test_support::tempdir(); let state = temp.path();
        storage::private_dir(&directory(state)).unwrap();
        let (old, _) = failed_build_fixture("0.7.1-rc.20261119.21");
        let (_, new_offer) = failed_build_fixture("0.7.1-rc.20261119.22");
        storage::atomic(&directory(state).join("offer.json"), &new_offer).unwrap();
        assert!(archive_failed_build(state, &old).is_err());
        assert!(!directory(state).join(format!("failed-build-{}.json", old.operation_id)).exists());
    }

    #[test]
    fn failed_feed_check_cannot_turn_a_completed_installer_into_a_failed_update() {
        let temp=crate::test_support::tempdir();let root=temp.path();let state=root.join("state");
        storage::private_dir(&directory(&state)).unwrap();
        let manifest=serde_json::to_vec(&json!({"version":"0.7.1-rc.20261119.17","files":[]})).unwrap();
        let generation=hash(&manifest);let bin=root.join("bin").join(&generation);
        storage::private_dir(&bin).unwrap();std::fs::write(bin.join("windows-manifest.json"),manifest).unwrap();
        storage::atomic(&root.join("desktop-install.json"),&json!({"version":3,"installRoot":root,"stateDirectory":state,
            "generation":generation,"releaseHash":generation,"bridge":root.join("pocket-launcher.exe")})).unwrap();
        // Fail before any network traffic, on the same pre-candidate error path
        // as a missing release feed or an expired/invalid signature.
        let policy=TrustPolicy{schema:1,feed:"https://untrusted.invalid/feed.json".into(),public_key:STANDARD.encode([1;32]),allowed_hosts:vec!["trusted.invalid".into()]};
        storage::atomic(&directory(&state).join("bootstrap-trust.json"),&BootstrapTrust{schema:1,authorized_public_key:policy.public_key.clone(),policy,signed_offer_sha256:"a".repeat(64)}).unwrap();
        let mut status=UpdateStatus{explicit_install:true,generation:Some(generation),version:Some("0.7.1-rc.20261119.17".into()),..Default::default()};
        save(&state,&mut status,"succeeded").unwrap();
        status.updated_at=storage::now()-7200;
        storage::atomic(&directory(&state).join("status.json"),&status).unwrap();
        let before=std::fs::read(directory(&state).join("status.json")).unwrap();
        let error=check_and_stage(root,&state).unwrap_err();
        assert!(error.to_string().contains("Feed is not trusted"));
        assert_eq!(std::fs::read(directory(&state).join("status.json")).unwrap(),before,
            "a later feed error overwrote the verified install operation");
        let checked=feed_check(&state);
        assert_eq!(checked["phase"],"failed");
        assert_eq!(checked["failure"],"update_verification_or_download_failed");
        assert_eq!(public_status(&state)["phase"],"succeeded");
        assert_eq!(public_status(&state)["check"]["phase"],"failed");
        tick(root,&state).unwrap();
        assert_eq!(std::fs::read(directory(&state).join("status.json")).unwrap(),before);
        assert_eq!(feed_check(&state),checked,"failed checks must still respect the hourly cadence");
        std::fs::write(directory(&state).join("bootstrap-trust.json"),b"broken").unwrap();
        assert!(check_and_stage(root,&state).is_err());
        assert_eq!(std::fs::read(directory(&state).join("status.json")).unwrap(),before);
        assert_eq!(feed_check(&state)["failure"],"update_trust_unavailable");
    }
    #[test]
    fn failed_rollback_leaves_switching_without_restoring_an_unverified_owner() {
        let tmp=crate::test_support::tempdir();let root=tmp.path();let state=root.join("state");
        let from="a".repeat(64);let to="b".repeat(64);
        let old=json!({"generation":from,"releaseHash":from,"stateDirectory":state});
        let mut s=UpdateStatus{explicit_install:true,generation:Some(to.clone()),..Default::default()};
        let next=prepare_switch(&mut s,old,&to).unwrap();
        storage::atomic(&root.join("desktop-install.json"),&next).unwrap();
        let h=upgrade::UpgradeStatus{schema:1,operation_id:uuid::Uuid::new_v4().to_string(),install_root:root.into(),
            state_directory:state.clone(),from_generation:from,to_generation:to,phase:upgrade::Phase::Failed,
            updated_at:storage::now(),checkpoint:None,verification:None,failure:Some(upgrade::Failure::RollbackFailed),bridge_restart_required:true};
        s.handoff_operation_id=Some(h.operation_id.clone());save(&state,&mut s,"switching").unwrap();
        finish_handoff(root,&state,&h).unwrap();
        let saved=read_status(&state).unwrap();
        assert_eq!(saved.phase,"failed");
        assert_eq!(saved.failure.as_deref(),Some("handoff_recovery_required"));
        assert_eq!(storage::read::<Value>(&root.join("desktop-install.json")).unwrap(),next);
    }
    #[test]
    fn unsupported_live_handoff_keeps_signed_install_for_cold_start() {
        for explicit in [true, false] {
        let tmp = crate::test_support::tempdir();
        let root = tmp.path(); let state = root.join("state");
        let from = "a".repeat(64); let to = "b".repeat(64);
        let old = json!({"generation":from,"releaseHash":from,"stateDirectory":state});
        let mut update = UpdateStatus { explicit_install:explicit, generation:Some(to.clone()), ..Default::default() };
        let next = prepare_switch(&mut update, old.clone(), &to).unwrap();
        storage::atomic(&root.join("desktop-install.json"), &next).unwrap();
        save(&state, &mut update, "switching").unwrap();
        let h = upgrade::UpgradeStatus {schema:1, operation_id:uuid::Uuid::new_v4().to_string(), install_root:root.into(),
            state_directory:state.clone(), from_generation:from, to_generation:to, phase:upgrade::Phase::WaitingRestart,
            updated_at:storage::now(),checkpoint:None,verification:None,failure:Some(upgrade::Failure::HandoffUnsupported),bridge_restart_required:false};
        finish_handoff(root, &state, &h).unwrap();
        let saved = read_status(&state).unwrap();
        assert_eq!(saved.phase, "waiting_cold_start");
        if explicit { assert!(crate::local_update::resumable_update(&saved)); }
        assert_eq!(saved.operation_id, update.operation_id);
        assert_eq!(storage::read::<Value>(&root.join("desktop-install.json")).unwrap(), old);
        assert_eq!(switch_if_idle(root, &state, &json!({})).unwrap(), false);
        assert_eq!(read_status(&state).unwrap().phase, "waiting_cold_start");
        }
    }
    #[test]
    fn interruption_between_install_pointer_and_coordinator_restores_only_program_record() {
        let root = crate::test_support::tempdir(); let state = root.path().join("state");
        storage::private_dir(&directory(&state)).unwrap();
        let original=json!({"generation":"a".repeat(64),"stateDirectory":state,"unknown":"keep"});
        let candidate=json!({"generation":"b".repeat(64),"stateDirectory":state,"unknown":"keep"});
        storage::atomic(&root.path().join("desktop-install.json"),&candidate).unwrap();
        std::fs::write(state.join("task.db"),b"new user data").unwrap();
        let mut status=UpdateStatus {generation:Some("b".repeat(64)),previous_record:Some(original.clone()),
            previous_record_hash:Some(hash(&serde_json::to_vec(&original).unwrap())),
            candidate_record_hash:Some(hash(&serde_json::to_vec(&candidate).unwrap())),..Default::default()};
        save(&state,&mut status,"switching").unwrap();
        recover_unrequested_switch(root.path(),&state).unwrap();
        assert_eq!(storage::read::<Value>(&root.path().join("desktop-install.json")).unwrap(),original);
        assert_eq!(read_status(&state).unwrap().failure.as_deref(),Some("handoff_not_started"));
        assert_eq!(std::fs::read(state.join("task.db")).unwrap(),b"new user data");
    }
    #[test]
    fn manual_install_does_not_enable_future_feed_checks_when_automatic_is_off() {
        let root = crate::test_support::tempdir();
        let state = root.path().join("state");
        storage::private_dir(&directory(&state)).unwrap();
        set_automatic(&state, false).unwrap();
        for phase in ["succeeded", "failed", "rolled_back", "current"] {
            let mut status = UpdateStatus { explicit_install: true, ..Default::default() };
            save(&state, &mut status, phase).unwrap();
            status.updated_at = 0;
            storage::atomic(&directory(&state).join("status.json"), &status).unwrap();
            // No install record or feed: any attempted check would fail here.
            tick(root.path(), &state).unwrap();
            assert_eq!(read_status(&state).unwrap().phase, phase);
        }
    }

    #[test]
    fn explicit_install_is_reserved_for_restart_even_with_automatic_disabled() {
        let root = crate::test_support::tempdir();
        let state = root.path().join("state");
        storage::private_dir(&directory(&state)).unwrap();
        set_automatic(&state, false).unwrap();
        let mut status = UpdateStatus { explicit_install: true, ..Default::default() };
        save(&state, &mut status, "staged").unwrap();
        assert!(!switch_if_idle(root.path(), &state, &json!({})).unwrap());
        assert_eq!(read_status(&state).unwrap().phase, "staged");
    }
    #[test]
    fn rollback_restores_only_the_exact_candidate_and_preserves_tasks_and_unknown_fields() {
        let root = crate::test_support::tempdir();
        let state = root.path().join("state");
        storage::private_dir(&state).unwrap();
        let original = json!({"generation":"a".repeat(64),"releaseHash":"a".repeat(64),"stateDirectory":state,"futureInstallerField":{"keep":true}});
        let mut candidate = original.clone();
        candidate["generation"] = json!("b".repeat(64));
        candidate["releaseHash"] = json!("b".repeat(64));
        storage::atomic(&root.path().join("desktop-install.json"), &candidate).unwrap();
        let task = state.join("durable-task.json");
        std::fs::write(&task, b"unknown task receipt: never replay").unwrap();
        let s = UpdateStatus {
            previous_record: Some(original.clone()),
            previous_record_hash: Some(hash(&serde_json::to_vec(&original).unwrap())),
            candidate_record_hash: Some(hash(&serde_json::to_vec(&candidate).unwrap())),
            ..Default::default()
        };
        restore_record(root.path(), &s).unwrap();
        restore_record(root.path(), &s).unwrap();
        assert_eq!(
            storage::read::<Value>(&root.path().join("desktop-install.json")).unwrap(),
            original
        );
        assert_eq!(
            std::fs::read(&task).unwrap(),
            b"unknown task receipt: never replay"
        );
        candidate["futureInstallerField"] = json!("foreign change");
        storage::atomic(&root.path().join("desktop-install.json"), &candidate).unwrap();
        assert!(restore_record(root.path(), &s).is_err());
        assert_eq!(
            storage::read::<Value>(&root.path().join("desktop-install.json")).unwrap(),
            candidate
        );
    }
    #[test]
    fn a_staged_update_without_idle_evidence_never_touches_installation() {
        let root = crate::test_support::tempdir();
        let state = root.path().join("state");
        storage::private_dir(&state).unwrap();
        let mut s = UpdateStatus {
            generation: Some("b".repeat(64)),
            ..Default::default()
        };
        save(&state, &mut s, "staged").unwrap();
        assert!(!switch_if_idle(
            root.path(),
            &state,
            &json!({"protocol":1,"updatedAt":storage::now(),"acceptingWrites":true})
        )
        .unwrap());
        assert_eq!(read_status(&state).unwrap().phase, "waiting_idle");
        assert!(!root.path().join("desktop-install.json").exists());
        set_automatic(&state, false).unwrap();
        assert!(!switch_if_idle(root.path(), &state, &json!({})).unwrap());
    }
    #[test]
    fn path_policy_rejects_windows_device_paths_and_case_duplicates() {
        for path in [
            "../agent.exe",
            "C:/evil",
            "dir\\file",
            "file.",
            "CON.txt",
            "COM1.exe",
            "LPT9/data",
            "/absolute",
            "dir//file",
        ] {
            assert!(!safe_path(path), "{path}");
        }
        assert!(safe_path("runtime/bin/worker.dll"));
    }
}
