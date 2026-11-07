//! Authenticated application request routing, native ownership and replay.
use crate::{
    access,
    attachments::{self, Attachment},
    auth::{self, DeviceStore},
    config::Config,
    error::{Code, classify},
    native::{self, Connected, NativeEvent, Rpc, RpcFailure},
    storage,
};
use anyhow::{Context, Result, bail};
use pocket_core::{
    directory::{
        ResolveError, RuntimeAdvertisement, RuntimeDirectory, RuntimeId, RuntimeSource, ThreadKey,
    },
    journal::{Cursor, EventJournal, JournalConfig, NativeEvent as JournalEvent, Replay},
    ledger::{Begin, CommandKey, CommandLedger, LedgerConfig, LedgerError, Status},
    native_process::NativeProcess,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    path::PathBuf,
    sync::Arc,
    time::Duration,
};
use tokio::sync::{Mutex, broadcast, mpsc};
mod v051;

/// Streaming deltas from every desktop thread share this fan-out. A phone session
/// that is writing a large history reply over the relay must not fall behind it
/// after a few hundred tokens; lagging is handled in-band, never by disconnecting.
pub(crate) const NOTIFICATION_CAPACITY: usize = 4096;

struct Runtime {
    usage: crate::context_usage::UsageCache,
    config_home: PathBuf,
    settings: crate::settings::SettingsCache,
    fast_models: BTreeSet<String>,
    capabilities: crate::capabilities::Capabilities,
    rpc: Rpc,
    native_process: Option<NativeProcess>,
    connection_id: String,
    source: String,
    version: String,
    store: String,
    loaded: BTreeSet<String>,
    inventory_ready: bool,
    inventory_revision: uuid::Uuid,
    subscribed: BTreeSet<String>,
    journal: EventJournal,
}
#[derive(Clone)]
struct Pending {
    runtime: String,
    native_id: Value,
    event: Value,
    command: Option<CommandKey>,
    response: Option<Value>,
    // Arbitration closes the UI, but this command still awaits native handling.
    winner_received: bool,
}
#[derive(Clone, Serialize, Deserialize)]
struct OwnershipClaim {
    #[serde(flatten)]
    advertisement: RuntimeAdvertisement,
    #[serde(default)]
    native_process: Option<NativeProcess>,
}
struct State {
    discovery_errors: BTreeSet<(String, String)>,
    devices: DeviceStore,
    ledger: CommandLedger,
    runtimes: BTreeMap<String, Runtime>,
    pending: HashMap<String, Pending>,
    attachments: HashMap<String, Attachment>,
    ownership: BTreeMap<String, OwnershipClaim>,
    ownership_persist_failed: bool,
}
pub struct Broker {
    quota: crate::quota::Quota,
    pub push: Arc<crate::push::PushDelivery>,
    push_delivery: Arc<Mutex<()>>,
    pub connection: Arc<crate::connection::Connection>,
    pub config: Config,
    state_dir: PathBuf,
    state: Mutex<State>,
    writes: Mutex<()>,
    notifications: broadcast::Sender<Value>,
    native_events: mpsc::Sender<NativeEvent>,
}
/// Where desktop-pasted attachments and generated images live, outside any project root.
fn image_roots(state_dir: &std::path::Path) -> Vec<PathBuf> {
    let mut roots = vec![state_dir.join("attachments"), std::env::temp_dir()];
    let home = std::env::var_os("CODEX_HOME")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var_os("USERPROFILE")
                .or_else(|| std::env::var_os("HOME"))
                .map(|h| PathBuf::from(h).join(".codex"))
        });
    if let Some(home) = home {
        roots.push(home.join("generated_images"));
    }
    roots
}
fn field<'a>(v: &'a Value, name: &str) -> Result<&'a str> {
    v[name]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 4096)
        .ok_or_else(|| {
            classify(
                anyhow::anyhow!("Missing or invalid {name}"),
                Code::InvalidParams,
            )
        })
}
/// Characters of command output kept per item in a history snapshot.
pub(crate) const HISTORY_OUTPUT_TAIL: usize = 2000;

/// History pages travel through a bandwidth-limited relay. Command output is about two thirds
/// of a full page (4.9 of 7.6MB measured) yet the phone shows it only after an explicit expand,
/// so keep its tail (where results and errors are) and mark the item. Live deltas stay complete.
fn trim_history_outputs(value: &mut Value) {
    let trim = |turn: &mut Value| {
        let Some(items) = turn.get_mut("items").and_then(Value::as_array_mut) else {
            return;
        };
        for item in items.iter_mut().filter(|i| i["type"] == "commandExecution") {
            let Some(output) = item.get("aggregatedOutput").and_then(Value::as_str) else {
                continue;
            };
            let count = output.chars().count();
            if count <= HISTORY_OUTPUT_TAIL {
                continue;
            }
            let tail: String = output.chars().skip(count - HISTORY_OUTPUT_TAIL).collect();
            item["aggregatedOutput"] = Value::String(tail);
            item["pocketOutputTruncated"] = Value::Bool(true);
        }
    };
    if let Some(page) = value.get_mut("data").and_then(Value::as_array_mut) {
        for turn in page {
            trim(turn)
        }
    }
    if let Some(list) = value
        .pointer_mut("/thread/turns")
        .and_then(Value::as_array_mut)
    {
        for turn in list {
            trim(turn)
        }
    }
}

fn cursor_value(c: &Cursor) -> Value {
    json!({"epoch":c.epoch,"sequence":c.sequence})
}
fn valid_reference(value: &Value) -> bool {
    ["name", "path"].iter().all(|k| {
        value[k]
            .as_str()
            .is_some_and(|s| !s.is_empty() && s.len() <= 4096 && !s.chars().any(char::is_control))
    })
}
fn push_event(event: &Value) -> bool {
    matches!(
        event["method"].as_str(),
        Some(
            "turn/completed"
                | "error"
                | "item/commandExecution/requestApproval"
                | "item/fileChange/requestApproval"
                | "item/tool/requestUserInput"
        )
    ) || (event["method"] == "item/completed"
        && event["params"]["item"]["type"] == "agentMessage"
        && event["params"]["item"]["delivery"] == "async")
}
fn validate_push(method: &str, p: &Value) -> Result<()> {
    let fields: &[&str] = match method {
        "push/register" => &["token", "commandId"],
        "push/unregister" => &["commandId"],
        "push/presence" => &["foreground", "threadId"],
        "push/resolve" => &["eventId"],
        _ => &[],
    };
    let invalid = p
        .as_object()
        .is_none_or(|v| v.keys().any(|k| !fields.contains(&k.as_str())))
        || (method == "push/register"
            && p["token"].as_str().is_none_or(|v| {
                v.is_empty() || v.len() > 4096 || !v.bytes().all(|b| b.is_ascii_graphic())
            }))
        || (method == "push/presence"
            && (!p["foreground"].is_boolean()
                || (!p["threadId"].is_null()
                    && p["threadId"]
                        .as_str()
                        .is_none_or(|s| s.is_empty() || s.len() > 4096))))
        || (method == "push/resolve"
            && p["eventId"]
                .as_str()
                .is_none_or(|s| uuid::Uuid::parse_str(s).is_err()));
    if invalid {
        return Err(classify(
            anyhow::anyhow!("Invalid push request"),
            Code::InvalidParams,
        ));
    }
    Ok(())
}
fn status_value(s: &Status) -> Value {
    match s {
        Status::Submitted => json!({"status":"submitted"}),
        Status::Unknown => json!({"status":"unknown"}),
        Status::Accepted { native_result } => json!({"status":"accepted","result":native_result}),
        Status::Rejected { .. } => json!({"status":"rejected","message":Code::Rejected.message()}),
    }
}
impl Broker {
    pub fn open(
        config: Config,
        state_dir: PathBuf,
    ) -> Result<(Arc<Self>, mpsc::Receiver<NativeEvent>)> {
        storage::private_directory(&state_dir)?;
        storage::private_directory(&state_dir.join("attachments"))?;
        let devices = if state_dir.join("devices.json").exists() {
            serde_json::from_slice(&std::fs::read(state_dir.join("devices.json"))?)?
        } else {
            DeviceStore::default()
        };
        let identity = RuntimeId {
            computer_id: config.computer_id.clone(),
            runtime_id: "broker".into(),
        };
        let policy = LedgerConfig {
            capacity: 50000,
            terminal_ttl: u64::MAX,
        };
        let ledger_text = if state_dir.join("commands.json").exists() {
            Some(std::fs::read_to_string(state_dir.join("commands.json"))?)
        } else {
            None
        };
        let persisted_now = ledger_text
            .as_deref()
            .and_then(|text| {
                serde_json::from_str::<Value>(text)
                    .ok()?
                    .get("last_now")?
                    .as_u64()
            })
            .unwrap_or(0);
        storage::observe(persisted_now);
        let now = storage::now();
        let ledger = if let Some(text) = ledger_text {
            CommandLedger::restore_json(&text, identity, policy, 1, now)
        } else {
            CommandLedger::new(identity, policy, 1, now)
        }
        .map_err(|e| anyhow::anyhow!("Invalid command ledger: {e:?}"))?;
        storage::atomic_write(
            &state_dir.join("commands.json"),
            ledger.snapshot_json().as_bytes(),
        )?;
        let attachment_dir = state_dir.join("attachments");
        let mut attachments: HashMap<String, Attachment> =
            if state_dir.join("attachments.json").exists() {
                serde_json::from_slice(&std::fs::read(state_dir.join("attachments.json"))?)?
            } else {
                HashMap::new()
            };
        attachments.retain(|id, attachment| {
            // Metadata is not authority to delete arbitrary paths. Only UUID
            // files created by upload in this exact directory can be retained
            // or expired; reject traversal and links without following them.
            if uuid::Uuid::parse_str(id).is_err()
                || !["png", "jpg", "webp"]
                    .iter()
                    .any(|ext| attachment.path == attachment_dir.join(format!("{id}.{ext}")))
                || !std::fs::symlink_metadata(&attachment.path)
                    .is_ok_and(|m| m.file_type().is_file())
            {
                return false;
            }
            if attachment.expires <= now {
                let _ = std::fs::remove_file(&attachment.path);
                false
            } else {
                true
            }
        });
        let (notifications, _) = broadcast::channel(NOTIFICATION_CAPACITY);
        let (native_events, rx) = mpsc::channel(512);
        let ownership_file = state_dir.join("ownership.json");
        let mut ownership: Vec<OwnershipClaim> = if ownership_file.exists() {
            serde_json::from_slice(&std::fs::read(ownership_file)?)?
        } else {
            Vec::new()
        };
        for claim in &mut ownership {
            if claim.advertisement.id.computer_id != config.computer_id {
                bail!("Ownership state belongs to another computer");
            }
            claim.advertisement.online = false;
        }
        RuntimeDirectory::new(ownership.iter().map(|claim| claim.advertisement.clone()))
            .map_err(|e| anyhow::anyhow!("Invalid ownership state: {e:?}"))?;
        Ok((
            Arc::new(Self {
                quota: {
                    #[cfg(test)]
                    let quota = crate::quota::Quota::new(state_dir.join("test-cc-switch.db"));
                    #[cfg(not(test))]
                    let quota = crate::quota::Quota::default();
                    quota
                },
                push: Arc::new(crate::push::PushDelivery::open(state_dir.clone())?),
                push_delivery: Arc::new(Mutex::new(())),
                connection: Arc::new(crate::connection::Connection::default()),
                config,
                state_dir,
                state: Mutex::new(State {
                    discovery_errors: BTreeSet::new(),
                    devices,
                    ledger,
                    runtimes: BTreeMap::new(),
                    pending: HashMap::new(),
                    attachments,
                    ownership: ownership
                        .into_iter()
                        .map(|a| (a.advertisement.id.runtime_id.clone(), a))
                        .collect(),
                    ownership_persist_failed: false,
                }),
                writes: Mutex::new(()),
                notifications,
                native_events,
            }),
            rx,
        ))
    }
    fn save_ledger(&self, state: &State) -> Result<()> {
        storage::atomic_write(
            &self.state_dir.join("commands.json"),
            state.ledger.snapshot_json().as_bytes(),
        )
    }
    fn save_devices(&self, state: &State) -> Result<()> {
        storage::atomic_write(
            &self.state_dir.join("devices.json"),
            &serde_json::to_vec(&state.devices)?,
        )
    }
    fn save_attachments(&self, state: &State) -> Result<()> {
        storage::atomic_write(
            &self.state_dir.join("attachments.json"),
            &serde_json::to_vec(&state.attachments)?,
        )
    }
    fn runtime_values(state: &State) -> Vec<Value> {
        state.runtimes.iter().map(|(id,r)| {
            let mut capabilities=r.capabilities.methods();
            capabilities.push("thread/settings/read".into());
            capabilities.retain(|m| !matches!(m.as_str(), "config/read" | "account/rateLimits/read"));
            capabilities.extend(["quota/sources", "quota/read"].into_iter().map(str::to_owned));
            capabilities.extend(["thread/context/read","thread/permissions/read","thread/permissions/update"].into_iter().map(str::to_owned));
            if r.capabilities.supports("thread/read",&json!({"threadId":"probe","includeTurns":false})) {
                capabilities.extend(["workspace/list","workspace/read","workspace/image","references/list"].into_iter().map(str::to_owned));
            }
            json!({"id":id,"source":r.source,"version":r.version,"configStoreId":r.store,"online":r.rpc.online(),"loadedThreadIds":r.loaded,"capabilities":capabilities,"capabilityFields":r.capabilities,"fastModels":r.fast_models})
        }).collect()
    }
    pub async fn diagnostics(&self) -> Value {
        let s = self.state.lock().await;
        let mut value = self.connection.snapshot();
        value["protocol"] = json!(1);
        value["updatedAt"] = json!(crate::connection::now());
        value["runtimes"] = json!(
            Self::runtime_values(&s)
                .into_iter()
                .map(|mut v| {
                    let o = v.as_object_mut().unwrap();
                    o.remove("loadedThreadIds");
                    o.remove("configStoreId");
                    o.remove("capabilityFields");
                    v
                })
                .collect::<Vec<_>>()
        );
        value["discoveryErrors"] = json!(
            s.discovery_errors
                .iter()
                .map(|(source, code)| json!({"source":source,"code":code}))
                .collect::<Vec<_>>()
        );
        value
    }
    pub async fn devices(&self) -> Value {
        json!({"data":self.state.lock().await.devices.devices.values().map(|d|json!({"id":d.id,"name":d.name,"createdAt":d.created_at})).collect::<Vec<_>>()})
    }
    pub async fn local_pair(&self) -> Result<Value> {
        let _state = self.state.lock().await;
        crate::local::pair_files(&self.state_dir)
    }
    pub async fn local_revoke(&self, id: &str) -> Result<Value> {
        let _delivery = self.push_delivery.lock().await;
        let _write = self.writes.lock().await;
        let mut s = self.state.lock().await;
        let result = self.revoke_locked(&mut s, id)?;
        drop(s);
        // Paired-device authority is already durably removed. A gateway outage
        // cannot restore it; the next emit checks current broker authorization.
        let _ = self.push.unregister(id).await;
        Ok(result)
    }
    fn revoke_locked(&self, s: &mut State, id: &str) -> Result<Value> {
        let removed = s.devices.devices.remove(id);
        if let Err(e) = self.save_devices(s) {
            if let Some(d) = removed {
                s.devices.devices.insert(id.to_owned(), d);
            }
            return Err(e);
        }
        let _ = self
            .notifications
            .send(json!({"method":"devices/revoked","params":{"deviceId":id}}));
        Ok(json!({"revoked":removed.is_some(),"deviceId":id}))
    }
    fn ownership_values(&self, state: &State) -> BTreeMap<String, OwnershipClaim> {
        let mut values = state.ownership.clone();
        for claim in values.values_mut() {
            claim.advertisement.online = false;
        }
        for (id, r) in &state.runtimes {
            values.insert(
                id.clone(),
                OwnershipClaim {
                    native_process: r.native_process.clone(),
                    advertisement: RuntimeAdvertisement {
                        id: RuntimeId {
                            computer_id: self.config.computer_id.clone(),
                            runtime_id: id.clone(),
                        },
                        source: if r.source == "desktop" {
                            RuntimeSource::Desktop
                        } else {
                            RuntimeSource::Cli
                        },
                        config_store_id: r.store.clone(),
                        online: r.rpc.online() && r.inventory_ready,
                        loaded_native_thread_ids: r.loaded.clone(),
                    },
                },
            );
        }
        values
    }
    fn save_ownership(&self, state: &mut State) -> Result<()> {
        let values = self.ownership_values(state);
        state.ownership_persist_failed = true;
        storage::atomic_write(
            &self.state_dir.join("ownership.json"),
            &serde_json::to_vec(&values.values().collect::<Vec<_>>())?,
        )?;
        state.ownership = values;
        state.ownership_persist_failed = false;
        Ok(())
    }
    fn directory(&self, state: &State) -> Result<RuntimeDirectory> {
        if state.ownership_persist_failed {
            bail!("Native ownership persistence is unavailable");
        }
        RuntimeDirectory::new(
            self.ownership_values(state)
                .into_values()
                .map(|claim| claim.advertisement),
        )
        .map_err(|e| anyhow::anyhow!("Runtime directory invalid: {e:?}"))
    }
    fn release_terminated_owners(&self, state: &mut State) -> Result<()> {
        let terminated: BTreeSet<_> = self
            .ownership_values(state)
            .into_iter()
            .filter(|(_, claim)| {
                claim
                    .native_process
                    .as_ref()
                    .is_some_and(NativeProcess::terminated)
            })
            .map(|(id, _)| id)
            .collect();
        if terminated.is_empty() {
            return Ok(());
        }
        for key in state
            .pending
            .values()
            .filter(|p| terminated.contains(&p.runtime))
            .filter_map(|p| p.command.as_ref())
        {
            state
                .ledger
                .finish(key, Status::Unknown, storage::now())
                .map_err(|e| anyhow::anyhow!("Cannot retire terminated runtime command: {e:?}"))?;
        }
        self.save_ledger(state)?;
        state
            .pending
            .retain(|_, p| !terminated.contains(&p.runtime));
        for id in &terminated {
            state.ownership.remove(id);
            state.runtimes.remove(id);
        }
        // Never authorize using a release that could not be persisted.
        self.save_ownership(state)?;
        let _ = self.notifications.send(
            json!({"method":"runtimes/changed","params":{"data":Self::runtime_values(state)}}),
        );
        Ok(())
    }
    pub fn subscribe(&self) -> broadcast::Receiver<Value> {
        self.notifications.subscribe()
    }
    #[cfg(test)]
    pub(crate) fn notify_for_test(&self, value: Value) {
        let _ = self.notifications.send(value);
    }
    pub async fn device_exists(&self, device: &str) -> bool {
        self.state.lock().await.devices.devices.contains_key(device)
    }
    pub async fn authenticate(&self, p: &Value) -> Result<String> {
        if p["protocol"] != 1 {
            return Err(classify(
                anyhow::anyhow!("Unsupported protocol"),
                Code::ProtocolMismatch,
            ));
        };
        let id = field(p, "deviceId")?;
        let secret = field(p, "deviceSecret")?;
        if !self.state.lock().await.devices.verify(id, secret) {
            return Err(classify(
                anyhow::anyhow!("Device authentication failed"),
                Code::Unauthorized,
            ));
        };
        Ok(id.to_owned())
    }
    pub async fn hello(&self) -> Value {
        let s = self.state.lock().await;
        // Top-level native capabilities are the intersection of online runtimes;
        // the selected runtime's capabilityFields is authoritative for new fields.
        let runtimes = Self::runtime_values(&s);
        let mut online = runtimes.iter().filter(|r| r["online"] == true);
        let methods = |r: &Value| {
            r["capabilities"]
                .as_array()
                .unwrap()
                .iter()
                .filter_map(|m| m.as_str().map(str::to_owned))
                .collect::<BTreeSet<_>>()
        };
        let mut capabilities: BTreeSet<String> = online.next().map(methods).unwrap_or_default();
        for r in online {
            let actual = methods(r);
            capabilities.retain(|m| actual.contains(m));
        }
        capabilities.extend(
            [
                "sync",
                "approvals/respond",
                "attachment/upload",
                "devices/revoke",
                "devices/list",
                "runtimes/list",
                "threads/list",
                "commands/status",
                "ping",
                "diagnostics",
                "workspace/list",
                "workspace/read",
                "workspace/image",
                "references/list",
                "thread/settings/read",
                "thread/context/read",
                "thread/permissions/read",
                "thread/permissions/update",
                "quota/sources",
                "quota/read",
                "push/status",
                "push/register",
                "push/unregister",
                "push/presence",
                "push/resolve",
            ]
            .into_iter()
            .map(str::to_owned),
        );
        json!({"protocol":1,"computerId":self.config.computer_id,"computerName":self.config.computer_name,"capabilities":capabilities,"runtimes":Self::runtime_values(&s)})
    }
    pub async fn pair(&self, p: &Value) -> Result<Value> {
        let mut state = self.state.lock().await;
        let path = self.state_dir.join("pairing.json");
        let pair: Value = serde_json::from_slice(&std::fs::read(&path).map_err(|e| {
            let code = if e.kind() == std::io::ErrorKind::NotFound {
                Code::Expired
            } else {
                Code::Unknown
            };
            classify(e.into(), code)
        })?)?;
        let token = field(p, "pairingToken")?;
        let expected = pair["tokenHash"].as_str().unwrap_or_default();
        use subtle::ConstantTimeEq;
        if pair["expiresAt"].as_u64().unwrap_or(0) <= storage::now() {
            return Err(classify(
                anyhow::anyhow!("Pairing invitation expired"),
                Code::Expired,
            ));
        }
        if !bool::from(auth::hash(token).as_bytes().ct_eq(expected.as_bytes())) {
            return Err(classify(
                anyhow::anyhow!("Pairing invitation invalid"),
                Code::Unauthorized,
            ));
        }
        let id = field(p, "deviceId")?.to_owned();
        let name = field(p, "deviceName")?.to_owned();
        let secret = storage::secret();
        state
            .devices
            .add(id.clone(), name, &secret, storage::now())
            .map_err(anyhow::Error::msg)?;
        // Consume first. A crash may require re-pairing but can never reuse the invitation.
        if let Err(error) =
            storage::atomic_write(&path, br#"{"tokenHash":"","expiresAt":0,"used":true}"#)
                .and_then(|_| self.save_devices(&state))
        {
            state.devices.devices.remove(&id);
            return Err(error);
        }
        Ok(
            json!({"deviceId":id,"deviceSecret":secret,"computerId":self.config.computer_id,"computerName":self.config.computer_name}),
        )
    }
    async fn add_runtime(&self, c: Connected) -> Result<()> {
        let rpc = c.rpc.clone();
        let recover_pending = c.source == "desktop";
        let store = storage::config_store(&c.config_home)?;
        let mut s = self.state.lock().await;
        if s.runtimes
            .get(&c.runtime_id)
            .is_some_and(|r| r.rpc.online())
        {
            return Ok(());
        }
        // Retire approvals before publishing a replacement. Its predecessor's
        // disconnect may still be queued and will be ignored by generation.
        let keys: Vec<_> = s
            .pending
            .values()
            .filter(|p| p.runtime == c.runtime_id)
            .filter_map(|p| p.command.clone())
            .collect();
        for key in keys {
            s.ledger
                .finish(&key, Status::Unknown, storage::now())
                .map_err(|e| anyhow::anyhow!("Cannot retire runtime command: {e:?}"))?;
        }
        self.save_ledger(&s)?;
        s.pending.retain(|_, p| p.runtime != c.runtime_id);
        let journal = EventJournal::new(
            RuntimeId {
                computer_id: self.config.computer_id.clone(),
                runtime_id: c.runtime_id.clone(),
            },
            JournalConfig {
                max_events: 4096,
                max_bytes: 16 * 1024 * 1024,
            },
        )
        .map_err(|e| anyhow::anyhow!("Journal: {e:?}"))?;
        let known = self.ownership_values(&s).remove(&c.runtime_id);
        if known
            .as_ref()
            .is_some_and(|a| a.advertisement.config_store_id != store)
        {
            bail!("Runtime changed configuration store");
        }
        if let (Some(previous), Some(current)) = (
            known.as_ref().and_then(|a| a.native_process.as_ref()),
            c.native_process.as_ref(),
        ) && previous != current
        {
            bail!("Runtime changed native process identity");
        }
        let native_process = c
            .native_process
            .or_else(|| known.as_ref().and_then(|a| a.native_process.clone()));
        let loaded = known
            .map(|a| a.advertisement.loaded_native_thread_ids)
            .unwrap_or_default();
        let settings = s
            .runtimes
            .get(&c.runtime_id)
            .filter(|r| {
                r.store == store && r.native_process.is_some() && r.native_process == native_process
            })
            .map(|r| r.settings.clone())
            .unwrap_or_default();
        let usage = s.runtimes.get(&c.runtime_id)
            .filter(|r| r.store==store && r.native_process.is_some() && r.native_process==native_process)
            .map(|r|r.usage.clone().stale()).unwrap_or_default();
        s.runtimes.insert(
            c.runtime_id,
            Runtime {
                usage,
                config_home: c.config_home,
                settings,
                fast_models: BTreeSet::new(),
                capabilities: c.capabilities,
                rpc: c.rpc,
                native_process,
                connection_id: c.connection_id,
                source: c.source,
                version: c.version,
                store,
                loaded,
                inventory_ready: false,
                inventory_revision: uuid::Uuid::new_v4(),
                subscribed: BTreeSet::new(),
                journal,
            },
        );
        let _ = self
            .notifications
            .send(json!({"method":"runtimes/changed","params":{"data":Self::runtime_values(&s)}}));
        drop(s);
        if recover_pending {
            rpc.call("pocket/approvals/list", json!({})).await?;
        }
        Ok(())
    }
    fn restore_pending(
        &self,
        state: &mut State,
        runtime: &str,
        value: &Value,
    ) -> Result<Vec<Value>> {
        let entries = value["data"]
            .as_array()
            .context("Invalid pending approval snapshot")?;
        let mut seen = BTreeSet::new();
        for event in entries {
            if !(event["id"].is_string() || event["id"].is_number())
                || !matches!(
                    event["method"].as_str(),
                    Some(
                        "item/commandExecution/requestApproval"
                            | "item/fileChange/requestApproval"
                            | "item/tool/requestUserInput"
                    )
                )
                || !event["params"].is_object()
                || !seen.insert(event["id"].to_string())
            {
                bail!("Invalid pending approval snapshot entry");
            }
        }
        let mut pending: HashMap<_, _> = state
            .pending
            .iter()
            .filter(|(_, p)| p.runtime != runtime)
            .map(|(id, p)| (id.clone(), p.clone()))
            .collect();
        if pending.len() + entries.len() > 256 {
            bail!("Pending approval snapshot exceeds capacity");
        }
        let mut events = Vec::new();
        for event in entries {
            if let Some((token, existing)) = state
                .pending
                .iter()
                .find(|(_, p)| p.runtime == runtime && p.native_id == event["id"])
            {
                pending.insert(token.clone(), existing.clone());
            } else {
                let token = uuid::Uuid::new_v4().to_string();
                let mut restored = event.clone();
                restored["id"] = json!(token);
                pending.insert(
                    token,
                    Pending {
                        runtime: runtime.into(),
                        native_id: event["id"].clone(),
                        event: restored.clone(),
                        command: None,
                        response: None,
                        winner_received: false,
                    },
                );
                events.push(restored);
            }
        }
        for (token, old) in &state.pending {
            if old.runtime == runtime && !pending.contains_key(token) {
                if let Some(key) = &old.command {
                    state
                        .ledger
                        .finish(key, Status::Unknown, storage::now())
                        .map_err(|e| anyhow::anyhow!("Approval reconciliation failed: {e:?}"))?;
                }
                events
                    .push(json!({"method":"pocket/approvalResolved","params":{"requestId":token}}));
            }
        }
        state.pending = pending;
        self.save_ledger(state)?;
        Ok(events)
    }
    pub async fn discover(self: Arc<Self>) {
        loop {
            let mut errors = BTreeSet::new();
            for dir in &self.config.runtime_directories {
                if let Ok(mut entries) = tokio::fs::read_dir(dir).await {
                    while let Ok(Some(e)) = entries.next_entry().await {
                        if e.path().extension().is_none_or(|e| e != "json") {
                            continue;
                        }
                        let id = e
                            .path()
                            .file_stem()
                            .map(|s| s.to_string_lossy().into_owned())
                            .unwrap_or_default();
                        if self
                            .state
                            .lock()
                            .await
                            .runtimes
                            .get(&id)
                            .is_some_and(|r| r.rpc.online())
                        {
                            continue;
                        }
                        match native::desktop(&e.path(), self.native_events.clone()).await {
                            Ok(c) => {
                                if self.add_runtime(c).await.is_err() {
                                    errors.insert(("desktop".into(), "registration_failed".into()));
                                }
                            }
                            Err(_) => {
                                errors.insert(("desktop".into(), "unavailable".into()));
                            }
                        }
                    }
                } else {
                    errors.insert(("desktop".into(), "discovery_unavailable".into()));
                }
            }
            for home in &self.config.codex_homes {
                let Ok(store) = storage::config_store(home) else {
                    errors.insert(("cli".into(), "discovery_unavailable".into()));
                    continue;
                };
                if self
                    .state
                    .lock()
                    .await
                    .runtimes
                    .values()
                    .any(|r| r.source == "cli" && r.store == store && r.rpc.online())
                {
                    continue;
                }
                match native::cli(home, self.native_events.clone()).await {
                    Ok(c) => {
                        if self.add_runtime(c).await.is_err() {
                            errors.insert(("cli".into(), "registration_failed".into()));
                        }
                    }
                    Err(_) => {
                        errors.insert(("cli".into(), "unavailable".into()));
                    }
                }
            }
            if self.refresh_loaded().await.is_err() {
                errors.insert(("native".into(), "inventory_unavailable".into()));
            }
            self.refresh_fast_capabilities().await;
            self.state.lock().await.discovery_errors = errors;
            tokio::time::sleep(Duration::from_secs(2)).await;
        }
    }
    async fn refresh_fast_capabilities(&self) {
        let runtimes = self
            .state
            .lock()
            .await
            .runtimes
            .iter()
            .filter(|(_, r)| {
                r.rpc.online()
                    && r.capabilities.supports(
                        "thread/settings/update",
                        &json!({"threadId":"probe","serviceTier":"fast"}),
                    )
            })
            .map(|(id, r)| {
                (
                    id.clone(),
                    r.connection_id.clone(),
                    r.rpc.clone(),
                    r.capabilities.clone(),
                )
            })
            .collect::<Vec<_>>();
        for (id, generation, rpc, capabilities) in runtimes {
            let models = Self::fast_catalog(&rpc, &capabilities)
                .await
                .unwrap_or_default();
            if let Some(r) = self
                .state
                .lock()
                .await
                .runtimes
                .get_mut(&id)
                .filter(|r| r.connection_id == generation)
            {
                r.fast_models = models;
            }
        }
    }
    async fn fast_catalog(
        rpc: &Rpc,
        capabilities: &crate::capabilities::Capabilities,
    ) -> Result<BTreeSet<String>> {
        let mut cursor = Value::Null;
        let mut models = BTreeSet::new();
        let mut seen = BTreeSet::new();
        for _ in 0..20 {
            let mut params = json!({"limit":100});
            if !cursor.is_null() {
                params["cursor"] = cursor.clone();
            }
            if !capabilities.supports("model/list", &params) {
                bail!("Model catalog schema unavailable");
            }
            let result = rpc.call("model/list", params).await?;
            for model in result["data"]
                .as_array()
                .context("Invalid native model catalog")?
            {
                if model["serviceTiers"]
                    .as_array()
                    .is_some_and(|tiers| tiers.iter().any(|t| t["id"] == "fast"))
                    || model["additionalSpeedTiers"]
                        .as_array()
                        .is_some_and(|tiers| tiers.contains(&json!("fast")))
                {
                    for key in ["model", "id"] {
                        if let Some(id) = model[key]
                            .as_str()
                            .filter(|id| !id.is_empty() && id.len() <= 4096)
                        {
                            models.insert(id.to_owned());
                        }
                    }
                }
            }
            cursor = result.get("nextCursor").cloned().unwrap_or(Value::Null);
            if cursor.is_null() {
                return Ok(models);
            }
            let next = cursor
                .as_str()
                .filter(|s| !s.is_empty())
                .context("Invalid catalog cursor")?;
            if !seen.insert(next.to_owned()) {
                bail!("Repeated catalog cursor");
            }
        }
        bail!("Model catalog incomplete")
    }
    async fn verify_fast(&self, runtime: &str, thread: &str) -> Result<()> {
        let (rpc, generation, capabilities) = {
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.rpc.online())
                .context("Runtime offline")?;
            (
                r.rpc.clone(),
                r.connection_id.clone(),
                r.capabilities.clone(),
            )
        };
        let result = rpc
            .call(
                "thread/read",
                json!({"threadId":thread,"includeTurns":false}),
            )
            .await?;
        if result["thread"]["id"] != thread {
            bail!("Native thread identity mismatch");
        }
        let model = field(&result["thread"], "model")?;
        let models = Self::fast_catalog(&rpc, &capabilities).await?;
        self.check_generation(runtime, &generation).await?;
        if let Some(r) = self
            .state
            .lock()
            .await
            .runtimes
            .get_mut(runtime)
            .filter(|r| r.connection_id == generation)
        {
            r.fast_models = models.clone();
        }
        if !models.contains(model) {
            return Err(classify(
                anyhow::anyhow!("Fast tier unavailable for selected model"),
                Code::Unsupported,
            ));
        }
        Ok(())
    }
    async fn refresh_loaded(&self) -> Result<()> {
        self.release_terminated_owners(&mut *self.state.lock().await)?;
        let runtimes: Vec<_> = self
            .state
            .lock()
            .await
            .runtimes
            .iter()
            .filter(|(_, r)| r.rpc.online())
            .map(|(id, r)| (id.clone(), r.connection_id.clone(), r.rpc.clone()))
            .collect();
        // Collect every native inventory before considering CLI subscriptions.
        // A partial/malformed list can never release an earlier ownership claim.
        for (id, connection_id, rpc) in &runtimes {
            let Some(revision) = self
                .state
                .lock()
                .await
                .runtimes
                .get(id)
                .filter(|r| r.connection_id == *connection_id)
                .map(|r| r.inventory_revision)
            else {
                continue;
            };
            let mut loaded = BTreeSet::new();
            let mut cursor = Value::Null;
            let mut complete = false;
            for _ in 0..100 {
                let mut params = json!({"limit":100});
                if !cursor.is_null() {
                    params["cursor"] = cursor.clone();
                }
                let result = rpc.call("thread/loaded/list", params).await?;
                for thread in result["data"]
                    .as_array()
                    .context("Invalid native loaded-thread inventory")?
                {
                    let thread = thread
                        .as_str()
                        .filter(|s| !s.is_empty() && s.len() <= 4096)
                        .context("Invalid native loaded-thread ID")?;
                    loaded.insert(thread.to_owned());
                }
                cursor = result.get("nextCursor").cloned().unwrap_or(Value::Null);
                if cursor.is_null() {
                    complete = true;
                    break;
                }
                if cursor.as_str().is_none_or(str::is_empty) {
                    bail!("Invalid native inventory cursor");
                }
            }
            if !complete {
                bail!("Native loaded-thread inventory is incomplete");
            }
            let mut state = self.state.lock().await;
            if let Some(runtime) = state
                .runtimes
                .get_mut(id)
                .filter(|r| r.connection_id == *connection_id)
            {
                if runtime.inventory_revision != revision {
                    bail!("Native ownership changed while inventory was being read");
                }
                runtime.subscribed.retain(|thread| loaded.contains(thread));
                runtime.loaded = loaded;
                runtime.inventory_ready = true;
                runtime.inventory_revision = uuid::Uuid::new_v4();
                self.save_ownership(&mut state)?;
            }
        }
        for (id, connection_id, rpc) in runtimes {
            let subscribe = {
                let state = self.state.lock().await;
                let directory = self.directory(&state)?;
                state
                    .runtimes
                    .get(&id)
                    .filter(|r| {
                        r.connection_id == connection_id && r.source == "cli" && r.inventory_ready
                    })
                    .map(|r| {
                        r.loaded
                            .difference(&r.subscribed)
                            .filter(|thread| {
                                directory
                                    .resolve_write(&ThreadKey {
                                        computer_id: self.config.computer_id.clone(),
                                        config_store_id: r.store.clone(),
                                        native_thread_id: (*thread).clone(),
                                    })
                                    .is_ok_and(|owner| owner.runtime_id == id)
                            })
                            .cloned()
                            .collect::<Vec<_>>()
                    })
                    .unwrap_or_default()
            };
            for thread in subscribe {
                if rpc
                    .call(
                        "thread/resume",
                        json!({"threadId":thread,"excludeTurns":true}),
                    )
                    .await
                    .is_ok()
                    && let Some(runtime) = self
                        .state
                        .lock()
                        .await
                        .runtimes
                        .get_mut(&id)
                        .filter(|r| r.connection_id == connection_id)
                {
                    runtime.subscribed.insert(thread);
                }
            }
        }
        Ok(())
    }
    pub async fn receive_events(self: Arc<Self>, mut rx: mpsc::Receiver<NativeEvent>) {
        while let Some(event) = rx.recv().await {
            let mut state = self.state.lock().await;
            match event {
                NativeEvent::PendingSnapshot {
                    runtime_id,
                    connection_id,
                    value,
                    reply,
                } => {
                    if state
                        .runtimes
                        .get(&runtime_id)
                        .is_none_or(|r| r.connection_id != connection_id || r.source != "desktop")
                    {
                        let _ = reply.send(Err(RpcFailure::Disconnected));
                        continue;
                    }
                    match self.restore_pending(&mut state, &runtime_id, &value) {
                        Ok(events) => {
                            for event in events {
                                if let Some(r) = state.runtimes.get_mut(&runtime_id) {
                                    let record = JournalEvent {
                                        native_thread_id: event["params"]["threadId"]
                                            .as_str()
                                            .map(str::to_owned),
                                        native_turn_id: None,
                                        native_item_id: None,
                                        payload: event.clone(),
                                    };
                                    if r.journal.append(record).is_ok() {
                                        let _=self.notifications.send(json!({"method":"codex/event","params":{"runtimeId":runtime_id,"cursor":cursor_value(&r.journal.cursor()),"event":event}}));
                                    }
                                }
                            }
                            let _ = reply.send(Ok(json!({})));
                        }
                        Err(_) => {
                            let _=reply.send(Err(RpcFailure::Native(json!({"message":"Pending approval snapshot could not be restored"}))));
                        }
                    }
                }
                NativeEvent::Snapshot {
                    runtime_id,
                    connection_id,
                    mut value,
                    reply,
                } => {
                    if state
                        .runtimes
                        .get(&runtime_id)
                        .is_none_or(|r| r.connection_id != connection_id)
                    {
                        let _ = reply.send(Err(RpcFailure::Disconnected));
                        continue;
                    }
                    if let Some(r) = state.runtimes.get(&runtime_id) {
                        value["pocketCursor"] = cursor_value(&r.journal.cursor());
                    }
                    trim_history_outputs(&mut value);
                    let _ = reply.send(Ok(value));
                }
                NativeEvent::Disconnected {
                    runtime_id,
                    connection_id,
                } => {
                    if state
                        .runtimes
                        .get(&runtime_id)
                        .is_none_or(|r| r.connection_id != connection_id)
                    {
                        continue;
                    }
                    let keys: Vec<_> = state
                        .pending
                        .values()
                        .filter(|p| p.runtime == runtime_id)
                        .filter_map(|p| p.command.clone())
                        .collect();
                    for key in keys {
                        let _ = state.ledger.finish(&key, Status::Unknown, storage::now());
                    }
                    let _ = self.save_ledger(&state);
                    state.pending.retain(|_, p| p.runtime != runtime_id);
                    if let Some(r) = state.runtimes.get_mut(&runtime_id) {
                        r.inventory_ready = false;
                    }
                    let _ = self.save_ownership(&mut state);
                    let _=self.notifications.send(json!({"method":"runtimes/changed","params":{"data":Self::runtime_values(&state)}}));
                }
                NativeEvent::Message {
                    runtime_id,
                    connection_id,
                    mut value,
                } => {
                    if state
                        .runtimes
                        .get(&runtime_id)
                        .is_none_or(|r| r.connection_id != connection_id)
                    {
                        continue;
                    }
                    let method = value["method"].as_str().unwrap_or_default().to_owned();
                    if method == "account/updated" {
                        drop(state);
                        if let Some(result) =
                            self.quota.account_changed(&runtime_id, &connection_id)
                        {
                            let _ = self
                                .notifications
                                .send(json!({"method":"quota/updated","params":result}));
                        }
                        continue;
                    }
                    if method == "account/rateLimits/updated" {
                        drop(state);
                        if let Some(result) =
                            self.quota
                                .update(&runtime_id, &connection_id, &value["params"])
                        {
                            let _ = self
                                .notifications
                                .send(json!({"method":"quota/updated","params":result}));
                        }
                        continue;
                    }
                    if push_event(&value)
                        && self.push.configured()
                        && let Some(thread) = value["params"]["threadId"].as_str()
                    {
                        let runtime = runtime_id.clone();
                        let thread = thread.to_owned();
                        let broker = self.clone();
                        // Bound concurrent delivery by dropping a redundant wake-up
                        // while another push is in flight. Sync remains authoritative.
                        if let Ok(guard) = self.push_delivery.clone().try_lock_owned() {
                            tokio::spawn(async move {
                                let _guard = guard;
                                let devices = broker
                                    .state
                                    .lock()
                                    .await
                                    .devices
                                    .devices
                                    .keys()
                                    .cloned()
                                    .collect::<Vec<_>>();
                                let _ = broker.push.emit(&runtime, &thread, &devices).await;
                            });
                        }
                    }
                    if matches!(
                        method.as_str(),
                        "serverRequest/resolved"
                            | "pocket/approvalSubmitted"
                            | "pocket/approvalResolved"
                    ) {
                        let matches: Vec<_> = state
                            .pending
                            .iter()
                            .filter(|(_, p)| {
                                p.runtime == runtime_id
                                    && p.native_id == value["params"]["requestId"]
                            })
                            .map(|(id, _)| id.clone())
                            .collect();
                        for id in matches {
                            if let Some(mut p) = state.pending.remove(&id) {
                                if let Some(key) = &p.command {
                                    let exact_winner = state
                                        .runtimes
                                        .get(&runtime_id)
                                        .is_some_and(|r| r.source == "desktop")
                                        && value["params"]["winner"] == "thisConnection"
                                        && value["params"]["threadId"]
                                            == p.event["params"]["threadId"]
                                        && p.response.as_ref().is_some_and(|response| {
                                            response == &value["params"]["response"]
                                        });
                                    let native_resolved = method == "pocket/approvalResolved"
                                        && value["params"]["nativeResolved"] == true;
                                    let submitted = exact_winner
                                        && !native_resolved
                                        && matches!(
                                            method.as_str(),
                                            "pocket/approvalSubmitted" | "pocket/approvalResolved"
                                        );
                                    let ui_only = method == "pocket/approvalResolved"
                                        && !native_resolved
                                        && value["params"].get("winner").is_none();
                                    if submitted || (p.winner_received && ui_only) {
                                        p.winner_received |= submitted;
                                        state.pending.insert(id.clone(), p);
                                    } else {
                                        // Native also resolves requests when a turn
                                        // transition cancels them without applying
                                        // this response. Current protocols provide
                                        // no explicit application acknowledgment.
                                        let _ = state.ledger.finish(
                                            key,
                                            Status::Unknown,
                                            storage::now(),
                                        );
                                        let _ = self.save_ledger(&state);
                                    }
                                }
                                value["params"]["requestId"] = json!(id);
                            }
                        }
                        if let Some(params) = value["params"].as_object_mut() {
                            params.remove("winner");
                            params.remove("response");
                            params.remove("nativeResolved");
                        }
                        if method == "pocket/approvalSubmitted" {
                            value["method"] = json!("pocket/approvalResolved");
                        }
                    }
                    if value.get("id").is_some()
                        && matches!(
                            method.as_str(),
                            "item/commandExecution/requestApproval"
                                | "item/fileChange/requestApproval"
                                | "item/tool/requestUserInput"
                        )
                    {
                        let token = uuid::Uuid::new_v4().to_string();
                        let native_id = value["id"].clone();
                        value["id"] = json!(token);
                        if state.pending.len() < 256 {
                            state.pending.insert(
                                token,
                                Pending {
                                    runtime: runtime_id.clone(),
                                    native_id,
                                    event: value.clone(),
                                    command: None,
                                    response: None,
                                    winner_received: false,
                                },
                            );
                        } else {
                            continue;
                        }
                    }
                    if let Some(r) = state.runtimes.get_mut(&runtime_id) {
                        r.usage.event(&value);
                        if method == "thread/settings/updated" {
                            let Some(thread) = value["params"]["threadId"]
                                .as_str()
                                .filter(|t| crate::settings::valid_thread(t))
                            else {
                                continue;
                            };
                            let settings =
                                crate::settings::canonical(&value["params"]["threadSettings"]);
                            if settings.as_object().is_none_or(|s| s.is_empty()) {
                                continue;
                            }
                            r.settings.event(&value);
                            value = crate::settings::notification(thread, settings);
                        } else {
                            r.settings.event(&value);
                        }
                        if method == "thread/started"
                            && let Some(id) = value["params"]["thread"]["id"].as_str()
                        {
                            r.loaded.insert(id.to_owned());
                            r.inventory_revision = uuid::Uuid::new_v4();
                        }
                        if matches!(method.as_str(), "thread/closed" | "thread/unloaded")
                            && let Some(id) = value["params"]["threadId"].as_str()
                        {
                            r.loaded.remove(id);
                            r.inventory_revision = uuid::Uuid::new_v4();
                        }
                        let event = JournalEvent {
                            native_thread_id: value["params"]["threadId"]
                                .as_str()
                                .map(str::to_owned),
                            native_turn_id: value["params"]["turnId"].as_str().map(str::to_owned),
                            native_item_id: value["params"]["itemId"].as_str().map(str::to_owned),
                            payload: value.clone(),
                        };
                        if r.journal.append(event).is_ok() {
                            let _=self.notifications.send(json!({"method":"codex/event","params":{"runtimeId":runtime_id,"cursor":cursor_value(&r.journal.cursor()),"event":value}}));
                        }
                    }
                    if matches!(
                        method.as_str(),
                        "thread/started" | "thread/closed" | "thread/unloaded"
                    ) {
                        let _ = self.save_ownership(&mut state);
                    }
                }
            }
        }
    }
    pub async fn request(&self, device: &str, method: &str, p: Value) -> Result<Value> {
        if !self.device_exists(device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        match method {
            "thread/context/read" => return self.read_context(device, &p).await,
            "thread/permissions/read" => return self.permission_data(device, &p).await.map(|(value,_)|value),
            "thread/settings/read" => return self.read_settings(device, &p).await,
            "quota/sources" | "quota/read" => {
                crate::quota::validate(method, &p).map_err(|_| {
                    classify(
                        anyhow::anyhow!("Invalid quota request"),
                        Code::InvalidParams,
                    )
                })?;
                let runtime = field(&p, "runtimeId")?;
                // Clone native identity under the event lock; release it before all I/O.
                let (rpc, capabilities, generation) = {
                    let state = self.state.lock().await;
                    let r = state.runtimes.get(runtime).ok_or_else(|| {
                        classify(anyhow::anyhow!("Runtime unavailable"), Code::NotFound)
                    })?;
                    (
                        r.rpc.clone(),
                        r.capabilities.clone(),
                        r.connection_id.clone(),
                    )
                };
                let sources = self
                    .quota
                    .sources(runtime, &generation, rpc, capabilities)
                    .await;
                let result = if method == "quota/sources" {
                    Ok(sources)
                } else {
                    self.quota
                        .read(
                            runtime,
                            &generation,
                            field(&p, "sourceId")?,
                            p["refresh"].as_bool().unwrap_or(false),
                        )
                        .await
                        .map_err(|_| {
                            classify(anyhow::anyhow!("Unknown quota source"), Code::InvalidParams)
                        })
                };
                if !self.device_exists(device).await {
                    return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
                }
                if self
                    .state
                    .lock()
                    .await
                    .runtimes
                    .get(runtime)
                    .is_none_or(|r| r.connection_id != generation)
                {
                    return Err(classify(
                        anyhow::anyhow!("Runtime changed during quota request"),
                        Code::NotFound,
                    ));
                }
                return result;
            }
            "push/status" => {
                validate_push(method, &p)?;
                return Ok(json!({"configured":self.push.configured()}));
            }
            "push/presence" => {
                validate_push(method, &p)?;
                return Ok(self
                    .push
                    .presence(
                        device,
                        p["threadId"].as_str().map(str::to_owned),
                        p["foreground"].as_bool().unwrap(),
                    )
                    .await);
            }
            "push/resolve" => {
                validate_push(method, &p)?;
                return self.push.resolve(device, field(&p, "eventId")?).await;
            }
            "ping" => {
                return Ok(
                    json!({"ok":true,"serverTime":crate::connection::now(),"connection":self.connection.snapshot()}),
                );
            }
            "diagnostics" => return Ok(self.diagnostics().await),
            "workspace/list" | "workspace/read" | "workspace/image" | "references/list" => {
                return self.workspace_request(device, method, &p).await;
            }
            "runtimes/list" => {
                return Ok(json!({"data":Self::runtime_values(&*self.state.lock().await)}));
            }
            "devices/list" => {
                return Ok(self.devices().await);
            }
            "commands/status" => {
                let key = CommandKey {
                    caller_id: device.into(),
                    command_id: field(&p, "commandId")?.into(),
                };
                return self
                    .state
                    .lock()
                    .await
                    .ledger
                    .status(&key)
                    .map(status_value)
                    .ok_or_else(|| classify(anyhow::anyhow!("Command not found"), Code::NotFound));
            }
            "sync" => return self.sync(&p).await,
            "threads/list" => return self.threads(&p).await,
            _ => {}
        }
        let mutation = match method {
            "thread/permissions/update" => {
                if !crate::permissions::validate(&p,true) { return Err(classify(anyhow::anyhow!("Invalid permission update"),Code::InvalidParams)); }
                true
            }
            "codex/request" => access::validate(field(&p, "method")?, &p["params"])
                .map_err(access::AccessError::into_request_error)?,
            "push/register" | "push/unregister" => {
                validate_push(method, &p)?;
                true
            }
            "approvals/respond" | "attachment/upload" | "devices/revoke" => true,
            _ => {
                return Err(classify(
                    anyhow::anyhow!("Unsupported operation"),
                    Code::Unsupported,
                ));
            }
        };
        if !mutation {
            return self.native_request(device, &p).await;
        }
        let _delivery = if matches!(
            method,
            "devices/revoke" | "push/register" | "push/unregister"
        ) {
            Some(self.push_delivery.lock().await)
        } else {
            None
        };
        let _write = self.writes.lock().await;
        if !self.device_exists(device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let command = field(&p, "commandId")?;
        if command.len() > 128 {
            return Err(classify(
                anyhow::anyhow!("Invalid command ID"),
                Code::InvalidParams,
            ));
        }
        let key = CommandKey {
            caller_id: device.into(),
            command_id: command.into(),
        };
        {
            let mut s = self.state.lock().await;
            match s
                .ledger
                .begin(
                    key.clone(),
                    &serde_json::to_vec(&json!({"method":method,"params":p}))?,
                    storage::now(),
                )
                .map_err(|e| {
                    classify(
                        anyhow::anyhow!("Command cannot execute: {e:?}"),
                        if e == LedgerError::BodyConflict {
                            Code::Unknown
                        } else {
                            Code::Rejected
                        },
                    )
                })? {
                Begin::Existing(Status::Accepted { native_result }) => return Ok(native_result),
                Begin::Existing(Status::Rejected { reason }) => {
                    return Err(classify(anyhow::anyhow!(reason), Code::Rejected));
                }
                Begin::Existing(other) => {
                    return Err(classify(
                        anyhow::anyhow!(
                            "Command result is {}; reconcile history before sending a new command",
                            status_value(&other)["status"]
                        ),
                        Code::Unknown,
                    ));
                }
                Begin::Execute => self
                    .save_ledger(&s)
                    .map_err(|e| classify(e, Code::Rejected))?,
            }
        }
        let result = match method {
            "thread/permissions/update" => self.update_permissions(device,&p).await,
            "push/register" => self
                .push
                .register(device, field(&p, "token")?)
                .await
                .map_err(|e| classify(e, Code::Unknown)),
            "push/unregister" => self
                .push
                .unregister(device)
                .await
                .map_err(|e| classify(e, Code::Unknown)),
            "codex/request" => self.native_request(device, &p).await,
            "approvals/respond" => self.approve(&p, &key).await,
            "attachment/upload" => {
                let mut s = self.state.lock().await;
                let result = attachments::upload(
                    &self.state_dir.join("attachments"),
                    device,
                    &p,
                    &mut s.attachments,
                );
                match result {
                    Ok(id) => match self.save_attachments(&s) {
                        Ok(()) => Ok(json!({"attachmentId":id})),
                        Err(error) => {
                            if let Some(attachment) = s.attachments.remove(&id) {
                                let _ = std::fs::remove_file(attachment.path);
                            }
                            Err(error)
                        }
                    },
                    Err(error) => Err(error),
                }
            }
            "devices/revoke" => {
                let mut s = self.state.lock().await;
                let id = field(&p, "deviceId")?;
                let result = self.revoke_locked(&mut s, id);
                drop(s);
                if result.is_ok() {
                    let _ = self.push.unregister(id).await;
                }
                result
            }
            _ => unreachable!(),
        };
        let mut s = self.state.lock().await;
        // Neither enqueue nor generic native resolution proves that an approval
        // response was applied. Its separate lifecycle stays conservative.
        if method == "approvals/respond" && result.is_ok() {
            return result;
        }
        let status = match &result {
            Ok(value) => Status::Accepted {
                native_result: value.clone(),
            },
            Err(e)
                if matches!(
                    e.downcast_ref::<RpcFailure>(),
                    Some(RpcFailure::Disconnected | RpcFailure::Timeout)
                ) || crate::error::classification(e) == Some(Code::Unknown) =>
            {
                Status::Unknown
            }
            Err(_) => Status::Rejected {
                reason: Code::Rejected.message().into(),
            },
        };
        let failure_code = if matches!(status, Status::Unknown) {
            Code::Unknown
        } else {
            Code::Rejected
        };
        s.ledger.finish(&key, status, storage::now()).map_err(|e| {
            classify(
                anyhow::anyhow!("Command finalization failed: {e:?}"),
                Code::Unknown,
            )
        })?;
        self.save_ledger(&s)
            .map_err(|e| classify(e, Code::Unknown))?;
        result.map_err(|e| classify(e, failure_code))
    }
    fn publish_settings(&self, runtime: &str, r: &mut Runtime, event: Value) {
        let record = JournalEvent {
            native_thread_id: event["params"]["threadId"].as_str().map(str::to_owned),
            native_turn_id: None,
            native_item_id: None,
            payload: event.clone(),
        };
        if r.journal.append(record).is_ok() {
            let _ = self.notifications.send(json!({"method":"codex/event","params":{"runtimeId":runtime,"cursor":cursor_value(&r.journal.cursor()),"event":event}}));
        }
    }
    async fn read_settings(&self, device: &str, p: &Value) -> Result<Value> {
        if !p.as_object().is_some_and(|p| {
            p.len() == 2
                && p.contains_key("runtimeId")
                && p.get("threadId")
                    .and_then(Value::as_str)
                    .is_some_and(crate::settings::valid_thread)
        }) {
            return Err(classify(
                anyhow::anyhow!("Settings read accepts only runtimeId and threadId"),
                Code::InvalidParams,
            ));
        }
        let runtime = field(p, "runtimeId")?;
        let thread = field(p, "threadId")?;
        let (rpc, generation, desktop, metadata, revision) = {
            let mut s = self.state.lock().await;
            let r = s
                .runtimes
                .get_mut(runtime)
                .filter(|r| r.rpc.online())
                .context("Runtime offline")?;
            (
                r.rpc.clone(),
                r.connection_id.clone(),
                r.source == "desktop",
                r.capabilities.supports(
                    "thread/read",
                    &json!({"threadId":thread,"includeTurns":false}),
                ),
                r.settings.checkpoint(),
            )
        };
        let mut cached = false;
        if desktop {
            match rpc
                .call("pocket/threadSettings", json!({"threadId":thread}))
                .await
            {
                Ok(value) => {
                    if value["threadId"] != thread || !value["known"].is_boolean() {
                        bail!("Native settings cache identity mismatch");
                    }
                    let mut s = self.state.lock().await;
                    let r = s
                        .runtimes
                        .get_mut(runtime)
                        .filter(|r| r.connection_id == generation && r.rpc.online())
                        .ok_or(RpcFailure::Disconnected)?;
                    if value["known"] == true {
                        if let Some(event) =
                            r.settings.snapshot(thread, &value["settings"], revision)
                        {
                            self.publish_settings(runtime, r, event);
                        }
                        cached = r.settings.read(thread)["known"] == true;
                    }
                }
                Err(RpcFailure::Native(_)) => {} // Older local bridge: try verified metadata.
                Err(error) => return Err(error.into()),
            }
        }
        if !cached && metadata {
            let observed = {
                let mut s = self.state.lock().await;
                let r = s
                    .runtimes
                    .get_mut(runtime)
                    .filter(|r| r.connection_id == generation && r.rpc.online())
                    .ok_or(RpcFailure::Disconnected)?;
                r.settings
                    .prepare("thread/read", &json!({"threadId":thread}))
                    .unwrap()
            };
            match rpc
                .call(
                    "thread/read",
                    json!({"threadId":thread,"includeTurns":false}),
                )
                .await
            {
                Ok(value) => {
                    if value["thread"]["id"] != thread {
                        bail!("Native thread metadata identity mismatch");
                    }
                    let mut s = self.state.lock().await;
                    let r = s
                        .runtimes
                        .get_mut(runtime)
                        .filter(|r| r.connection_id == generation && r.rpc.online())
                        .ok_or(RpcFailure::Disconnected)?;
                    if let Some(event) = r.settings.accepted(observed, &value) {
                        self.publish_settings(runtime, r, event);
                    }
                }
                Err(RpcFailure::Native(_)) => {} // No settings evidence available.
                Err(error) => return Err(error.into()),
            }
        }
        let s = self.state.lock().await;
        if !s.devices.devices.contains_key(device) {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let r = s
            .runtimes
            .get(runtime)
            .filter(|r| r.connection_id == generation && r.rpc.online())
            .ok_or(RpcFailure::Disconnected)?;
        let mut value = r.settings.read(thread);
        value["runtimeId"] = json!(runtime);
        Ok(value)
    }
    async fn workspace_proof(
        &self,
        runtime: &str,
        thread: &str,
    ) -> Result<(
        crate::workspace::Workspace,
        Rpc,
        String,
        crate::capabilities::Capabilities,
    )> {
        let (rpc, generation, capabilities) = {
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.rpc.online())
                .context("Runtime offline")?;
            if !r.capabilities.supports(
                "thread/read",
                &json!({"threadId":thread,"includeTurns":false}),
            ) {
                return Err(classify(
                    anyhow::anyhow!("Native thread proof unsupported"),
                    Code::Unsupported,
                ));
            }
            (
                r.rpc.clone(),
                r.connection_id.clone(),
                r.capabilities.clone(),
            )
        };
        let proof = rpc
            .call(
                "thread/read",
                json!({"threadId":thread,"includeTurns":false}),
            )
            .await?;
        if proof["thread"]["id"] != thread {
            bail!("Native thread identity mismatch");
        }
        let cwd = field(&proof["thread"], "cwd")?;
        let workspace = crate::workspace::Workspace::new(vec![PathBuf::from(cwd)])?;
        self.check_generation(runtime, &generation).await?;
        Ok((workspace, rpc, generation, capabilities))
    }
    async fn check_generation(&self, runtime: &str, generation: &str) -> Result<()> {
        if self
            .state
            .lock()
            .await
            .runtimes
            .get(runtime)
            .is_none_or(|r| r.connection_id != generation || !r.rpc.online())
        {
            return Err(RpcFailure::Disconnected.into());
        }
        Ok(())
    }
    async fn workspace_request(&self, device: &str, method: &str, p: &Value) -> Result<Value> {
        let allowed = if method == "references/list" {
            &["runtimeId", "threadId", "path", "query"][..]
        } else if method == "workspace/image" {
            &["runtimeId", "threadId", "path", "offset"][..]
        } else {
            &["runtimeId", "threadId", "path"][..]
        };
        if p.as_object()
            .is_none_or(|p| p.keys().any(|k| !allowed.contains(&k.as_str())))
            || p.get("path").is_some_and(|v| !v.is_string())
            || p.get("query")
                .is_some_and(|v| v.as_str().is_none_or(|s| s.len() > 256))
        {
            return Err(classify(
                anyhow::anyhow!("Invalid workspace request"),
                Code::InvalidParams,
            ));
        }
        let runtime = field(p, "runtimeId")?;
        let thread = field(p, "threadId")?;
        let (workspace, rpc, generation, capabilities) =
            self.workspace_proof(runtime, thread).await?;
        let result = match method {
            "workspace/read" => workspace.read(field(p, "path")?)?,
            "workspace/image" => {
                let offset = match p.get("offset") {
                    None => 0,
                    Some(v) => v.as_u64().ok_or_else(|| {
                        classify(anyhow::anyhow!("Invalid offset"), Code::InvalidParams)
                    })?,
                };
                workspace
                    .with_image_roots(image_roots(&self.state_dir))
                    .image(field(p, "path")?, offset)?
            }
            "workspace/list" => workspace.list(p["path"].as_str())?,
            _ => {
                self.references(
                    &workspace,
                    &rpc,
                    &capabilities,
                    p["path"].as_str(),
                    p["query"].as_str().unwrap_or(""),
                )
                .await?
            }
        };
        self.check_generation(runtime, &generation).await?;
        if !self.device_exists(device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        Ok(result)
    }
    async fn references(
        &self,
        workspace: &crate::workspace::Workspace,
        rpc: &Rpc,
        capabilities: &crate::capabilities::Capabilities,
        path: Option<&str>,
        query: &str,
    ) -> Result<Value> {
        let listing = workspace.list(path)?;
        let mut data = listing["entries"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|v| v["kind"] == "file")
            .map(|v| json!({"type":"file","name":v["name"],"path":v["path"]}))
            .collect::<Vec<_>>();
        let mut unsupported = Vec::new();
        let params = json!({"cwds":workspace.roots()});
        if capabilities.supports("skills/list", &params) {
            match rpc.call("skills/list", params).await {
                Ok(result) => {
                    for entry in result["data"].as_array().into_iter().flatten() {
                        let matches_root = entry["cwd"].as_str().is_some_and(|cwd| {
                            workspace
                                .roots()
                                .iter()
                                .any(|r| r == std::path::Path::new(cwd))
                        });
                        if !matches_root {
                            continue;
                        }
                        for skill in entry["skills"].as_array().into_iter().flatten() {
                            if skill["enabled"] == true && valid_reference(skill) {
                                let mut reference = json!({"type":"skill","name":skill["name"],"path":skill["path"]});
                                if let Some(description) = skill
                                    .get("description")
                                    .filter(|v| v.as_str().is_some_and(|s| s.len() <= 4096))
                                {
                                    reference["description"] = description.clone();
                                }
                                data.push(reference);
                            }
                        }
                    }
                }
                Err(RpcFailure::Native(_)) => unsupported.push("skills/list"),
                Err(e) => return Err(e.into()),
            }
        } else {
            unsupported.push("skills/list");
        }
        // Catalog presence alone does not establish a mention URI. Only accept an
        // explicit native mention object from an installed, enabled catalog item.
        let params =
            json!({"cwds":workspace.roots(),"marketplaceKinds":["local"],"forceRefetch":false});
        if capabilities.supports("plugin/list", &params) {
            match rpc.call("plugin/list", params).await {
                Ok(result) => {
                    let mut mapped = false;
                    for marketplace in result["marketplaces"].as_array().into_iter().flatten() {
                        for plugin in marketplace["plugins"].as_array().into_iter().flatten() {
                            let mention = &plugin["mention"];
                            if plugin["enabled"] == true
                                && plugin["installed"] == true
                                && mention["type"] == "mention"
                                && valid_reference(mention)
                            {
                                mapped = true;
                                data.push(json!({"type":"mention","name":mention["name"],"path":mention["path"]}));
                            }
                        }
                    }
                    if !mapped {
                        unsupported.push("plugin/list");
                    }
                }
                Err(RpcFailure::Native(_)) => unsupported.push("plugin/list"),
                Err(e) => return Err(e.into()),
            }
        } else {
            unsupported.push("plugin/list");
        }
        let query = query.to_lowercase();
        data.retain(|v| {
            v["name"]
                .as_str()
                .unwrap_or("")
                .to_lowercase()
                .contains(&query)
                || v["path"]
                    .as_str()
                    .unwrap_or("")
                    .to_lowercase()
                    .contains(&query)
        });
        let truncated = listing["truncated"] == true || data.len() > 256;
        data.truncate(256);
        Ok(json!({"data":data,"truncated":truncated,"unsupported":unsupported}))
    }
    async fn validate_references(
        &self,
        device: &str,
        runtime: &str,
        thread: &str,
        input: &Value,
    ) -> Result<()> {
        let refs = self
            .workspace_request(
                device,
                "references/list",
                &json!({"runtimeId":runtime,"threadId":thread}),
            )
            .await?;
        for item in input
            .as_array()
            .into_iter()
            .flatten()
            .filter(|v| matches!(v["type"].as_str(), Some("skill" | "mention")))
        {
            if !refs["data"].as_array().unwrap().iter().any(|v| {
                ["type", "name", "path"]
                    .iter()
                    .all(|key| v[key] == item[key])
            }) {
                bail!("Reference was not enabled for this native thread");
            }
        }
        Ok(())
    }
    async fn native_request(&self, device: &str, p: &Value) -> Result<Value> {
        self.native_request_checked(device,p,None).await
    }
    async fn native_request_checked(&self, device: &str, p: &Value, permission_guard: Option<(&str,&BTreeMap<String,String>)>) -> Result<Value> {
        let runtime = field(p, "runtimeId")?;
        let method = field(p, "method")?;
        let mut params = p["params"].clone();
        let mutation = if permission_guard.is_some() {
            if method!="thread/settings/update" || !crate::permission_policy::valid_update(&params) {bail!("Invalid internal permission update");}
            true
        } else {access::validate(method, &params).map_err(access::AccessError::into_request_error)?};
        // Catalog and workspace proofs must remain bound to this connection,
        // including the interval before ownership refresh and native dispatch.
        let proof_generation = self
            .state
            .lock()
            .await
            .runtimes
            .get(runtime)
            .filter(|r| r.rpc.online())
            .map(|r| r.connection_id.clone())
            .context("Runtime offline")?;
        if crate::capabilities::advanced(method) {
            let s = self.state.lock().await;
            if !s
                .runtimes
                .get(runtime)
                .is_some_and(|r| r.rpc.online() && r.capabilities.supports(method, &params))
            {
                return Err(classify(
                    anyhow::anyhow!("Native schema unavailable for operation"),
                    Code::Unsupported,
                ));
            }
        }
        if matches!(method, "skills/list" | "plugin/list") {
            return Err(classify(
                anyhow::anyhow!("Use scoped references/list"),
                Code::Unsupported,
            ));
        }
        if method == "thread/settings/update" && params["serviceTier"] == "fast" {
            self.verify_fast(runtime, field(&params, "threadId")?)
                .await?;
        }
        if params["input"].as_array().is_some_and(|items| {
            items
                .iter()
                .any(|i| matches!(i["type"].as_str(), Some("skill" | "mention")))
        }) {
            self.validate_references(
                device,
                runtime,
                field(&params, "threadId")?,
                &params["input"],
            )
            .await?;
        }
        if mutation {
            self.refresh_loaded().await?;
        }
        let (rpc, connection_id, resume_probe) = {
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.rpc.online() && r.connection_id == proof_generation)
                .context("Runtime offline")?;
            let mut resume_probe = None;
            if mutation && let Some(thread) = params["threadId"].as_str() {
                let key = ThreadKey {
                    computer_id: self.config.computer_id.clone(),
                    config_store_id: r.store.clone(),
                    native_thread_id: thread.into(),
                };
                match self.directory(&s)?.resolve_write(&key) {
                    Ok(owner) if owner.runtime_id != runtime => {
                        bail!("Thread is owned by another runtime")
                    }
                    Err(ResolveError::AmbiguousOwners) => {
                        bail!("Thread is loaded by multiple runtimes; sending paused")
                    }
                    Err(ResolveError::NoOnlineOwner)
                        if matches!(
                            method,
                            "thread/resume"
                                | "thread/name/set"
                                | "thread/archive"
                                | "thread/unarchive"
                                | "thread/fork"
                        ) =>
                    {
                        resume_probe = Some(thread.to_owned());
                    }
                    Err(ResolveError::NoOnlineOwner) => {
                        bail!("Resume this inactive thread before sending")
                    }
                    Ok(_) => {}
                    Err(e) => bail!("Cannot establish thread ownership: {e:?}"),
                }
            }
            attachments::translate(&mut params, device, &s.attachments)?;
            (r.rpc.clone(), r.connection_id.clone(), resume_probe)
        };
        if let Some(thread) = resume_probe {
            // Inactive history may resume only after this native connection
            // proves the exact ID, and a fresh directory still permits it.
            let proof = rpc
                .call(
                    "thread/read",
                    json!({"threadId":thread,"includeTurns":false}),
                )
                .await?;
            if proof["thread"]["id"] != thread {
                bail!("Native runtime did not prove ownership of this thread");
            }
            self.refresh_loaded().await?;
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.connection_id == connection_id && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            let key = ThreadKey {
                computer_id: self.config.computer_id.clone(),
                config_store_id: r.store.clone(),
                native_thread_id: thread,
            };
            match self.directory(&s)?.resolve_write(&key) {
                Ok(owner) if owner.runtime_id != runtime => {
                    bail!("Thread is owned by another runtime")
                }
                Ok(_) | Err(ResolveError::NoOnlineOwner) => {}
                Err(ResolveError::AmbiguousOwners) => {
                    bail!("Thread is loaded by multiple runtimes; sending paused")
                }
                Err(e) => bail!("Cannot establish thread ownership: {e:?}"),
            }
        }
        let settings_observation = {
            let mut s = self.state.lock().await;
            let r = s
                .runtimes
                .get_mut(runtime)
                .filter(|r| r.connection_id == connection_id && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            if let Some((expected,custom)) = permission_guard {
                let thread=field(&params,"threadId")?;
                let current=crate::permissions::response(runtime,thread,&r.connection_id,&r.settings.read(thread)["settings"],&r.capabilities,custom);
                if current["revision"]!=expected {return Err(classify(anyhow::anyhow!("Permission revision changed before dispatch"),Code::Rejected));}
            }
            r.settings.prepare(method, &params)
        };
        let result = rpc.call(method, params).await?;
        let mut s = self.state.lock().await;
        let r = s
            .runtimes
            .get_mut(runtime)
            .filter(|r| r.connection_id == connection_id)
            .ok_or(RpcFailure::Disconnected)?;
        if let Some(event) =
            settings_observation.and_then(|observed| r.settings.accepted(observed, &result))
        {
            self.publish_settings(runtime, r, event);
        }
        if method == "thread/fork"
            && (result["thread"]["id"]
                .as_str()
                .is_none_or(|id| id.is_empty() || id.len() > 4096)
                || result["thread"]["id"] == p["params"]["threadId"])
        {
            return Err(classify(
                anyhow::anyhow!("Native fork did not return a distinct identity"),
                Code::Unknown,
            ));
        }
        if matches!(method, "thread/start" | "thread/resume" | "thread/fork")
            && let Some(id) = result["thread"]["id"].as_str()
        {
            r.loaded.insert(id.into());
            r.inventory_revision = uuid::Uuid::new_v4();
            self.save_ownership(&mut s)
                .map_err(|e| classify(e, Code::Unknown))?;
        }
        Ok(result)
    }
    async fn approve(&self, p: &Value, key: &CommandKey) -> Result<Value> {
        let runtime = field(p, "runtimeId")?;
        let id = field(p, "requestId")?;
        let (rpc, native_id) = {
            let mut s = self.state.lock().await;
            let pending = s
                .pending
                .get(id)
                .filter(|r| r.runtime == runtime && r.command.is_none())
                .context("Approval already resolved or stale")?;
            validate_approval(&pending.event, &p["response"])?;
            let native_id = pending.native_id.clone();
            let rpc = s
                .runtimes
                .get(runtime)
                .filter(|r| r.rpc.online())
                .context("Runtime offline")?
                .rpc
                .clone();
            s.pending.get_mut(id).unwrap().command = Some(key.clone());
            s.pending.get_mut(id).unwrap().response = Some(p["response"].clone());
            (rpc, native_id)
        };
        rpc.respond(native_id, p["response"].clone()).await?;
        Ok(json!({"status":"submitted"}))
    }
    async fn sync(&self, p: &Value) -> Result<Value> {
        let s = self.state.lock().await;
        let mut events = vec![];
        let mut snapshots = vec![];
        let mut cursors = serde_json::Map::new();
        for (id, r) in &s.runtimes {
            let c = &p["cursors"][id];
            let parsed = c["epoch"]
                .as_str()
                .and_then(|v| uuid::Uuid::parse_str(v).ok())
                .zip(c["sequence"].as_u64());
            let replay = parsed.map(|(epoch, sequence)| {
                r.journal.replay(&Cursor {
                    runtime: RuntimeId {
                        computer_id: self.config.computer_id.clone(),
                        runtime_id: id.clone(),
                    },
                    epoch,
                    sequence,
                })
            });
            match replay {
                Some(Replay::Events(items)) => {
                    for e in items {
                        events.push(json!({"runtimeId":id,"cursor":cursor_value(&e.cursor),"event":e.event.payload}));
                    }
                }
                _ => snapshots.push(id.clone()),
            }
            cursors.insert(id.clone(), cursor_value(&r.journal.cursor()));
        }
        let pending: Vec<_> = s
            .pending
            .values()
            .filter(|p| p.command.is_none())
            .map(|p| json!({"runtimeId":p.runtime,"event":p.event}))
            .collect();
        Ok(
            json!({"events":events,"snapshotRequired":snapshots,"cursors":cursors,"pendingRequests":pending}),
        )
    }
    async fn threads(&self, p: &Value) -> Result<Value> {
        let _ = self.refresh_loaded().await;
        let runtimes = {
            let s = self.state.lock().await;
            let mut stores = BTreeSet::new();
            s.runtimes
                .iter()
                .filter(|(_, r)| r.rpc.online() && stores.insert(r.store.clone()))
                .map(|(id, r)| (id.clone(), r.store.clone(), r.rpc.clone()))
                .collect::<Vec<_>>()
        };
        let mut data = Vec::new();
        let mut next = serde_json::Map::new();
        let mut more = false;
        for (id, store, rpc) in runtimes {
            let previous = &p["cursor"][&store];
            if previous == false {
                next.insert(store, Value::Bool(false));
                continue;
            }
            let mut params = json!({"limit":p["limit"].as_u64().unwrap_or(30).clamp(1,100),"archived":p["archived"].as_bool().unwrap_or(false),"sourceKinds":["cli","vscode","appServer","unknown"],"sortKey":"updated_at"});
            if previous.is_string() {
                params["cursor"] = previous.clone();
            }
            if let Some(search) = p["search"].as_str().filter(|s| !s.is_empty()) {
                params["searchTerm"] = json!(search);
            }
            let result = rpc.call("thread/list", params).await?;
            if result["nextCursor"].is_string() {
                next.insert(store.clone(), result["nextCursor"].clone());
                more = true;
            } else {
                next.insert(store.clone(), Value::Bool(false));
            }
            let s = self.state.lock().await;
            let directory = self.directory(&s)?;
            for thread in result["data"].as_array().into_iter().flatten() {
                let Some(thread_id) = thread["id"].as_str() else {
                    continue;
                };
                let owner = directory.resolve_write(&ThreadKey {
                    computer_id: self.config.computer_id.clone(),
                    config_store_id: store.clone(),
                    native_thread_id: thread_id.into(),
                });
                let selected = owner.as_ref().map(|o| o.runtime_id.as_str()).unwrap_or(&id);
                let source = s
                    .runtimes
                    .get(selected)
                    .map(|r| r.source.as_str())
                    .unwrap_or("cli");
                let mut row=json!({"id":thread_id,"runtimeId":selected,"configStoreId":store,"source":source,"name":thread["name"],"preview":thread["preview"],"cwd":thread["cwd"],"updatedAt":thread["updatedAt"],"status":thread["status"],"conflict":matches!(owner,Err(ResolveError::AmbiguousOwners))});
                row.as_object_mut().unwrap().extend(crate::permissions::thread_metadata(thread).as_object().unwrap().clone());
                data.push(row);
            }
        }
        data.sort_by(|a, b| b["updatedAt"].as_i64().cmp(&a["updatedAt"].as_i64()));
        Ok(json!({"data":data,"nextCursor":if more{Value::Object(next)}else{Value::Null}}))
    }
}

fn validate_approval(event: &Value, response: &Value) -> Result<()> {
    let object = response
        .as_object()
        .context("Approval response must be an object")?;
    match event["method"].as_str() {
        Some("item/commandExecution/requestApproval" | "item/fileChange/requestApproval") => {
            if object.len() != 1
                || !matches!(
                    response["decision"].as_str(),
                    Some("accept" | "acceptForSession" | "decline" | "cancel")
                )
            {
                bail!("Unsupported approval decision")
            }
            if let Some(available) = event["params"]["availableDecisions"].as_array()
                && !available.contains(&response["decision"])
            {
                bail!("Decision is not offered by native runtime")
            }
        }
        Some("item/tool/requestUserInput") => {
            if object.len() != 1 {
                bail!("Unsupported question response")
            };
            let answers = response["answers"].as_object().context("Missing answers")?;
            let questions = event["params"]["questions"]
                .as_array()
                .context("Native questions unavailable")?;
            if answers.len() != questions.len() {
                bail!("Answer every native question")
            }
            for q in questions {
                let id = field(q, "id")?;
                let answer = &answers[id];
                if answer.as_object().is_none_or(|a| a.len() != 1) {
                    bail!("Invalid question answer")
                };
                let texts = answer["answers"].as_array().context("Invalid answers")?;
                if texts.is_empty()
                    || texts.len() > 32
                    || texts
                        .iter()
                        .any(|v| v.as_str().is_none_or(|t| t.len() > 65536))
                {
                    bail!("Invalid answer text")
                }
            }
        }
        _ => bail!("Complete this interaction on desktop"),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[tokio::test]
    async fn settings_replacement_rejects_old_response_and_wrong_thread_metadata() {
        let (_dir, b) = fixture().await;
        let mut old = peer(&b, "r", "cli").await;
        let old_task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/settings/read",
                    json!({"runtimeId":"r","threadId":"t"}),
                )
                .await
            }
        });
        let old_read = old.recv().await;
        old.offline();
        let mut current = peer(&b, "r", "cli").await;
        old.reply(&old_read, json!({"thread":{"id":"t","model":"old"}}))
            .await;
        assert!(finish(old_task).await.is_err());
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/settings/read",
                    json!({"runtimeId":"r","threadId":"t"}),
                )
                .await
            }
        });
        let read = current.recv().await;
        current
            .reply(&read, json!({"thread":{"id":"other","model":"wrong"}}))
            .await;
        assert!(finish(task).await.is_err());
        let s = b.state.lock().await;
        assert_eq!(s.runtimes["r"].settings.read("t")["known"], false);
        assert_eq!(s.runtimes["r"].settings.read("other")["known"], false);
    }
    #[tokio::test]
    async fn settings_helper_reads_historical_metadata_without_loading_thread() {
        for source in ["cli", "desktop"] {
            let (_dir, b) = fixture().await;
            let mut native = peer(&b, "r", source).await;
            let task = tokio::spawn({
                let b = b.clone();
                async move {
                    b.request(
                        "phone",
                        "thread/settings/read",
                        json!({"runtimeId":"r","threadId":"history"}),
                    )
                    .await
                }
            });
            if source == "desktop" {
                let request = native.recv().await;
                assert_eq!(request["method"], "pocket/threadSettings");
                assert_eq!(request["params"], json!({"threadId":"history"}));
                native
                    .reply(
                        &request,
                        json!({"threadId":"history","known":false,"settings":{}}),
                    )
                    .await;
            }
            let request = native.recv().await;
            assert_eq!(request["method"], "thread/read");
            assert_eq!(
                request["params"],
                json!({"threadId":"history","includeTurns":false})
            );
            native.reply(&request, json!({"thread":{"id":"history","model":"persisted","reasoningEffort":null,"cwd":"private","turns":[{"model":"content"}]},"config":{"apiKey":"private"}})).await;
            assert_eq!(
                finish(task).await.unwrap(),
                json!({"runtimeId":"r","threadId":"history","known":true,"settings":{"model":"persisted","effort":null}})
            );
            assert!(
                !b.state.lock().await.runtimes["r"]
                    .loaded
                    .contains("history")
            );
        }
    }
    #[tokio::test]
    async fn settings_late_read_cannot_replace_new_event_or_another_runtime() {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "a", "cli").await;
        let _other = peer(&b, "b", "cli").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/settings/read",
                    json!({"runtimeId":"a","threadId":"t"}),
                )
                .await
            }
        });
        let read = native.recv().await;
        native.send(json!({"method":"thread/settings/updated","params":{"threadId":"t","threadSettings":{"model":"new","effort":"ultra","collaborationMode":{"mode":"plan","settings":{"model":"new","reasoning_effort":"ultra","developer_instructions":"private"}},"permissions":"all","cwd":"private"}}})).await;
        native
            .reply(
                &read,
                json!({"thread":{"id":"t","model":"old","reasoningEffort":"low"}}),
            )
            .await;
        let value = finish(task).await.unwrap();
        assert_eq!(
            value["settings"],
            json!({"model":"new","effort":"ultra","collaborationMode":{"mode":"plan","settings":{"model":"new","reasoning_effort":"ultra"}}})
        );
        let s = b.state.lock().await;
        assert_eq!(s.runtimes["b"].settings.read("t")["known"], false);
        assert_eq!(s.runtimes["a"].settings.read("other")["known"], false);
        let cursor = s.runtimes["a"].journal.cursor();
        if let Replay::Events(events) = s.runtimes["a"].journal.replay(&Cursor {
            sequence: 0,
            ..cursor
        }) {
            assert_eq!(events.len(), 1);
            assert_eq!(events[0].event.native_thread_id.as_deref(), Some("t"));
            assert_eq!(
                events[0].event.payload["params"]["threadSettings"],
                value["settings"]
            );
        } else {
            panic!("retained settings notification");
        }
    }
    #[tokio::test]
    async fn settings_bridge_cache_recovers_plan_on_reconnect() {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "r", "desktop").await;
        for effort in ["high", "ultra"] {
            let task = tokio::spawn({
                let b = b.clone();
                async move {
                    b.request(
                        "phone",
                        "thread/settings/read",
                        json!({"runtimeId":"r","threadId":"t"}),
                    )
                    .await
                }
            });
            let read = native.recv().await;
            assert_eq!(read["method"], "pocket/threadSettings");
            native.reply(&read, json!({"threadId":"t","known":true,"settings":{"model":"model","effort":effort,"collaborationMode":{"mode":"plan","settings":{"model":"model","reasoning_effort":effort,"developer_instructions":"private"}},"sandboxPolicy":{"type":"dangerFullAccess"}}})).await;
            let result = finish(task).await.unwrap();
            assert_eq!(
                result["settings"],
                json!({"model":"model","effort":effort,"collaborationMode":{"mode":"plan","settings":{"model":"model","reasoning_effort":effort}},"sandboxPolicy":{"type":"dangerFullAccess","hasAdditionalWritableRoots":false}})
            );
        }
    }
    #[tokio::test]
    async fn settings_helper_without_evidence_is_unknown_and_never_starts_native_work() {
        let (_dir, b) = fixture().await;
        let _peer = peer(&b, "runtime", "cli").await;
        b.state
            .lock()
            .await
            .runtimes
            .get_mut("runtime")
            .unwrap()
            .capabilities = Default::default();
        let result = b
            .request(
                "phone",
                "thread/settings/read",
                json!({"runtimeId":"runtime","threadId":"history"}),
            )
            .await
            .unwrap();
        assert_eq!(
            result,
            json!({"runtimeId":"runtime","threadId":"history","settings":{},"known":false})
        );
        assert!(
            b.request(
                "absent",
                "thread/settings/read",
                json!({"runtimeId":"runtime","threadId":"history"})
            )
            .await
            .is_err()
        );
        assert!(
            b.request(
                "phone",
                "thread/settings/read",
                json!({"runtimeId":"runtime","threadId":"history","permissions":"all"})
            )
            .await
            .is_err()
        );
    }
    use super::*;
    use crate::native::test_peer::{self, Peer};
    #[test]
    fn history_snapshots_keep_only_the_tail_of_long_command_output() {
        let long = format!("{}中文结尾", "x".repeat(HISTORY_OUTPUT_TAIL * 3));
        let item = |output: &str| json!({"type":"commandExecution","id":"c","command":"make","aggregatedOutput":output});
        let mut page = json!({"data":[{"items":[item(&long), item("short"), {"type":"agentMessage","text":long}]}]});
        let mut read = json!({"thread":{"turns":[{"items":[item(&long)]}]}});
        trim_history_outputs(&mut page);
        trim_history_outputs(&mut read);
        let first = &page["data"][0]["items"][0];
        let kept = first["aggregatedOutput"].as_str().unwrap();
        assert_eq!(kept.chars().count(), HISTORY_OUTPUT_TAIL);
        assert!(kept.ends_with("中文结尾"));
        assert_eq!(first["pocketOutputTruncated"], true);
        assert_eq!(first["command"], "make");
        assert_eq!(page["data"][0]["items"][1]["aggregatedOutput"], "short");
        assert!(
            page["data"][0]["items"][1]
                .get("pocketOutputTruncated")
                .is_none()
        );
        // Only command output is shortened; prose is never cut.
        assert_eq!(
            page["data"][0]["items"][2]["text"].as_str().unwrap().len(),
            long.len()
        );
        assert_eq!(
            read["thread"]["turns"][0]["items"][0]["pocketOutputTruncated"],
            true
        );
    }
    #[tokio::test]
    async fn v02_ping_and_diagnostics_are_authenticated() {
        let (_dir, b) = fixture().await;
        assert!(b.request("absent", "ping", json!({})).await.is_err());
        let ping = b.request("phone", "ping", json!({})).await.unwrap();
        assert_eq!(ping["ok"], true);
        assert_eq!(ping["connection"]["connected"], false);
        let status = b.request("phone", "diagnostics", json!({})).await.unwrap();
        assert_eq!(status["protocol"], 1);
        assert!(status["discoveryErrors"].is_array());
    }
    #[tokio::test]
    async fn v02_push_rpc_uses_authenticated_identity_and_durable_registration() {
        let (_dir, b) = fixture().await;
        let status = b.request("phone", "push/status", json!({})).await.unwrap();
        assert!(status["configured"].is_boolean());
        assert!(
            b.hello().await["capabilities"]
                .as_array()
                .unwrap()
                .contains(&json!("push/resolve"))
        );
        assert_eq!(
            b.request(
                "phone",
                "push/presence",
                json!({"foreground":true,"threadId":"t"})
            )
            .await
            .unwrap()["ok"],
            true
        );
        for method in [
            "push/register",
            "push/presence",
            "push/resolve",
            "push/unregister",
        ] {
            assert!(b.request("other", method, json!({})).await.is_err());
            assert!(
                b.request(
                    "phone",
                    method,
                    json!({"deviceId":"other","token":"token","commandId":"id"})
                )
                .await
                .is_err()
            );
        }
        assert!(
            b.request("phone", "push/register", json!({"token":"token"}))
                .await
                .is_err()
        );
    }
    #[tokio::test]
    async fn v02_references_filter_disabled_and_foreign_catalog_entries() {
        let (dir, b) = fixture().await;
        let root = dir.path().join("workspace");
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("file.txt"), "text").unwrap();
        let mut native = peer(&b, "a", "desktop").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "references/list",
                    json!({"runtimeId":"a","threadId":"t"}),
                )
                .await
            }
        });
        let proof = native.recv().await;
        native
            .reply(&proof, json!({"thread":{"id":"t","cwd":root}}))
            .await;
        let skills = native.recv().await;
        assert_eq!(skills["method"], "skills/list");
        assert_eq!(skills["params"]["cwds"], json!([root]));
        native.reply(&skills,json!({"data":[{"cwd":root,"skills":[{"name":"review","path":"C:/skills/review/SKILL.md","description":"Review source changes","enabled":true},{"name":"disabled","path":"secret","enabled":false}]},{"cwd":"C:/foreign","skills":[{"name":"foreign","path":"private","enabled":true}]}]})).await;
        let plugins = native.recv().await;
        assert_eq!(plugins["method"], "plugin/list");
        assert_eq!(plugins["params"]["forceRefetch"], false);
        native.reply(&plugins,json!({"marketplaces":[{"plugins":[{"id":"installed","installed":true,"enabled":true,"name":"Plugin"}]}]})).await;
        let result = finish(task).await.unwrap();
        assert_eq!(result["data"].as_array().unwrap().len(), 2);
        assert_eq!(result["data"][0]["type"], "file");
        assert_eq!(result["data"][1]["name"], "review");
        assert_eq!(result["data"][1]["description"], "Review source changes");
        assert!(
            result["unsupported"]
                .as_array()
                .unwrap()
                .contains(&json!("plugin/list"))
        );
        let hello = b.hello().await;
        for name in ["workspace/list", "workspace/read", "references/list"] {
            assert!(
                hello["runtimes"][0]["capabilities"]
                    .as_array()
                    .unwrap()
                    .contains(&json!(name))
            );
        }
    }
    #[tokio::test]
    async fn v02_queue_disconnect_and_invalid_fork_remain_unknown_without_replay() {
        for method in ["thread/queue/start", "thread/fork"] {
            let (_dir, b) = fixture().await;
            let mut native = peer(&b, "a", "desktop").await;
            let params = json!({"runtimeId":"a","method":method,"params":{"threadId":"t"},"commandId":"uncertain"});
            let task = tokio::spawn({
                let b = b.clone();
                let p = params.clone();
                async move { b.request("phone", "codex/request", p).await }
            });
            loaded(&mut native, &["t"]).await;
            let sent = native.recv().await;
            assert_eq!(sent["method"], method);
            if method == "thread/fork" {
                native.reply(&sent, json!({"thread":{"id":"t"}})).await;
            } else {
                native.offline();
                drop(native);
            }
            assert_eq!(
                crate::session::reply(json!("x"), finish(task).await)["error"]["code"],
                "unknown"
            );
            assert_eq!(
                b.request("phone", "commands/status", json!({"commandId":"uncertain"}))
                    .await
                    .unwrap()["status"],
                "unknown"
            );
            assert_eq!(
                crate::session::reply(
                    json!("x"),
                    b.request("phone", "codex/request", params).await
                )["error"]["code"],
                "unknown"
            );
        }
    }
    #[tokio::test]
    async fn v02_unverified_advanced_method_never_dispatches() {
        let (_dir, b) = fixture().await;
        let _native = peer(&b, "a", "desktop").await;
        b.state
            .lock()
            .await
            .runtimes
            .get_mut("a")
            .unwrap()
            .capabilities = Default::default();
        let result=b.request("phone","codex/request",json!({"runtimeId":"a","method":"thread/queue/start","params":{"threadId":"t"},"commandId":"blocked"})).await;
        assert!(result.is_err());
        let hello = b.hello().await;
        assert!(
            !hello["runtimes"][0]["capabilities"]
                .as_array()
                .unwrap()
                .contains(&json!("thread/queue/start"))
        );
        assert!(
            !hello["runtimes"][0]["capabilities"]
                .as_array()
                .unwrap()
                .contains(&json!("workspace/read"))
        );
    }
    #[tokio::test]
    async fn v02_fast_requires_selected_threads_model_catalog_tier() {
        for supported in [true, false] {
            let (_dir, b) = fixture().await;
            let mut native = peer(&b, "a", "desktop").await;
            let task = tokio::spawn({
                let b = b.clone();
                async move {
                    b.request("phone","codex/request",json!({"runtimeId":"a","commandId":"fast","method":"thread/settings/update","params":{"threadId":"t","serviceTier":"fast"}})).await
                }
            });
            let read = native.recv().await;
            assert_eq!(read["method"], "thread/read");
            native
                .reply(&read, json!({"thread":{"id":"t","model":"selected"}}))
                .await;
            let models = native.recv().await;
            assert_eq!(models["method"], "model/list");
            native.reply(&models,json!({"data":[{"model":"different","serviceTiers":[{"id":"fast"}]},{"model":"selected","serviceTiers":if supported{json!([{"id":"fast"}])}else{json!([])}}],"nextCursor":null})).await;
            if supported {
                loaded(&mut native, &["t"]).await;
                let sent = native.recv().await;
                assert_eq!(sent["method"], "thread/settings/update");
                native.reply(&sent, json!({})).await;
            }
            assert_eq!(finish(task).await.is_ok(), supported);
        }
    }
    #[tokio::test]
    async fn v02_native_mutations_keep_ids_and_fork_ownership() {
        for (method, params) in [
            (
                "thread/queue/add",
                json!({"threadId":"t","clientUserMessageId":"m","input":[]}),
            ),
            (
                "thread/queue/update",
                json!({"threadId":"t","queuedSubmissionId":"q","input":[]}),
            ),
            (
                "thread/queue/delete",
                json!({"threadId":"t","queuedSubmissionId":"q"}),
            ),
            (
                "thread/queue/reorder",
                json!({"threadId":"t","queuedSubmissionIds":["q2","q"]}),
            ),
            (
                "thread/queue/start",
                json!({"threadId":"t","queuedSubmissionId":"q"}),
            ),
            (
                "thread/goal/set",
                json!({"threadId":"t","objective":"work","origin":"user"}),
            ),
            ("thread/goal/clear", json!({"threadId":"t","origin":"user"})),
            ("thread/compact/start", json!({"threadId":"t"})),
            (
                "thread/settings/update",
                json!({"threadId":"t","serviceTier":null}),
            ),
            (
                "thread/fork",
                json!({"threadId":"t","attachToSource":true,"beforeTurnId":"turn"}),
            ),
        ] {
            let (_dir, b) = fixture().await;
            let mut native = peer(&b, "a", "desktop").await;
            let request =
                json!({"runtimeId":"a","commandId":"once","method":method,"params":params});
            let task = tokio::spawn({
                let b = b.clone();
                let request = request.clone();
                async move { b.request("phone", "codex/request", request).await }
            });
            loaded(&mut native, &["t"]).await;
            let sent = native.recv().await;
            assert_eq!(sent["method"], method);
            assert_eq!(sent["params"], params);
            let result = if method == "thread/fork" {
                json!({"thread":{"id":"fork-new"}})
            } else {
                json!({"native":true})
            };
            native.reply(&sent, result.clone()).await;
            assert_eq!(finish(task).await.unwrap(), result);
            assert_eq!(
                b.request("phone", "codex/request", request).await.unwrap(),
                result
            );
            if method == "thread/fork" {
                assert!(
                    b.state.lock().await.runtimes["a"]
                        .loaded
                        .contains("fork-new")
                );
            }
        }
    }
    #[tokio::test]
    async fn v02_workspace_requires_exact_native_thread_and_rejects_client_roots() {
        let (dir, b) = fixture().await;
        let root = dir.path().join("workspace");
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("file.txt"), "bounded text").unwrap();
        let mut native = peer(&b, "a", "desktop").await;
        for mismatch in [false, true] {
            let task = tokio::spawn({
                let b = b.clone();
                async move {
                    b.request(
                        "phone",
                        "workspace/read",
                        json!({"runtimeId":"a","threadId":"t","path":"file.txt"}),
                    )
                    .await
                }
            });
            let proof = native.recv().await;
            assert_eq!(proof["method"], "thread/read");
            assert_eq!(
                proof["params"],
                json!({"threadId":"t","includeTurns":false})
            );
            native
                .reply(
                    &proof,
                    json!({"thread":{"id":if mismatch{"other"}else{"t"},"cwd":root}}),
                )
                .await;
            let result = finish(task).await;
            if mismatch {
                assert!(result.is_err());
            } else {
                assert_eq!(result.unwrap()["text"], "bounded text");
            }
        }
        assert!(
            b.request(
                "phone",
                "workspace/list",
                json!({"runtimeId":"a","threadId":"t","roots":[dir.path()]})
            )
            .await
            .is_err()
        );
    }
    #[tokio::test]
    async fn quota_is_authenticated_gated_and_does_not_hold_the_event_lock() {
        let (_dir, b) = fixture().await;
        assert_eq!(
            crate::error::code(
                &b.request("stranger", "quota/sources", json!({"runtimeId":"a"}))
                    .await
                    .unwrap_err()
            ),
            Code::Revoked
        );
        let mut peer = peer(&b, "a", "cli").await;
        {
            let mut state = b.state.lock().await;
            let c = &mut state.runtimes.get_mut("a").unwrap().capabilities;
            c.0.insert("account/rateLimits/read".into(), Default::default());
            c.0.remove("config/read");
        }
        let list = b
            .request("phone", "quota/sources", json!({"runtimeId":"a"}))
            .await
            .unwrap();
        let id = list["sources"][0]["id"].clone();
        let hello = b.hello().await;
        for method in ["quota/sources", "quota/read"] {
            assert!(
                hello["capabilities"]
                    .as_array()
                    .unwrap()
                    .contains(&json!(method))
            );
            assert!(
                hello["runtimes"][0]["capabilities"]
                    .as_array()
                    .unwrap()
                    .contains(&json!(method))
            );
        }
        let reading = tokio::spawn({
            let b = b.clone();
            let id = id.clone();
            async move {
                b.request(
                    "phone",
                    "quota/read",
                    json!({"runtimeId":"a","sourceId":id,"refresh":true}),
                )
                .await
            }
        });
        let req = peer.recv().await;
        assert_eq!(req["method"], "account/rateLimits/read");
        assert_eq!(
            tokio::time::timeout(
                Duration::from_millis(100),
                b.request("phone", "ping", json!({}))
            )
            .await
            .unwrap()
            .unwrap()["ok"],
            true
        );
        let connection = b.state.lock().await.runtimes["a"].connection_id.clone();
        let mut notifications = b.subscribe();
        b.native_events.send(NativeEvent::Message{runtime_id:"a".into(),connection_id:connection.clone(),value:json!({"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":17,"resetsAt":1794000000}}}})}).await.unwrap();
        let event = tokio::time::timeout(Duration::from_millis(200), notifications.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(event["method"], "quota/updated");
        assert_eq!(event["params"]["items"][0]["usedPercent"], 17.0);
        peer.send(json!({"id":req["id"],"error":{"message":"must-not-escape"}}))
            .await;
        let response = reading.await.unwrap().unwrap();
        assert_eq!(response["status"], "ok");
        assert_eq!(response["items"][0]["usedPercent"], 17.0);
        for m in ["account/rateLimits/read", "config/read"] {
            assert!(
                b.request(
                    "phone",
                    "codex/request",
                    json!({"runtimeId":"a","method":m,"params":{}})
                )
                .await
                .is_err()
            );
        }
        assert!(
            b.request(
                "phone",
                "quota/read",
                json!({"runtimeId":"a","sourceId":id,"script":"bad"})
            )
            .await
            .is_err()
        );
        assert_eq!(
            b.request("phone", "ping", json!({})).await.unwrap()["ok"],
            true
        );
    }

    pub(super) async fn fixture() -> (tempfile::TempDir, Arc<Broker>) {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (b, rx) = Broker::open(config, dir.path().into()).unwrap();
        b.state
            .lock()
            .await
            .devices
            .add("phone".into(), "Phone".into(), "secret", storage::now())
            .unwrap();
        tokio::spawn(b.clone().receive_events(rx));
        (dir, b)
    }
    pub(super) async fn peer(b: &Broker, id: &str, source: &str) -> Peer {
        peer_with_process(b, id, source, None).await
    }
    async fn peer_with_process(
        b: &Broker,
        id: &str,
        source: &str,
        process: Option<NativeProcess>,
    ) -> Peer {
        let (mut c, mut p) =
            test_peer::connect(id, source, &b.state_dir, b.native_events.clone()).await;
        c.native_process = process;
        if source == "desktop" {
            let register = b.add_runtime(c);
            let snapshot = async {
                let request = p.recv().await;
                assert_eq!(request["method"], "pocket/approvals/list");
                p.reply(&request, json!({"data":[]})).await;
            };
            let (result, _) = tokio::join!(register, snapshot);
            result.unwrap();
        } else {
            b.add_runtime(c).await.unwrap();
        }
        p
    }
    pub(super) async fn loaded(p: &mut Peer, ids: &[&str]) {
        let request = p.recv().await;
        assert_eq!(request["method"], "thread/loaded/list");
        p.reply(&request, json!({"data":ids,"nextCursor":null}))
            .await;
    }
    #[cfg(windows)]
    #[test]
    fn native_lifetime_child() {
        if std::env::var_os("POCKET_TEST_NATIVE_LIFETIME").is_some() {
            let _ = std::io::copy(&mut std::io::stdin(), &mut std::io::sink());
        }
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn persisted_native_claim_blocks_live_engine_but_releases_after_verified_exit() {
        use pocket_core::native_process::NativeProcess;
        use std::{
            os::windows::process::CommandExt,
            process::{Child, Command, Stdio},
        };
        struct Disposable(Child);
        impl Drop for Disposable {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        for source in ["desktop", "cli"] {
            let mut child = Disposable(
                Command::new(std::env::current_exe().unwrap())
                    .args(["--exact", "broker::tests::native_lifetime_child"])
                    .env("POCKET_TEST_NATIVE_LIFETIME", "1")
                    .stdin(Stdio::piped())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .creation_flags(0x08000000)
                    .spawn()
                    .unwrap(),
            );
            let identity = NativeProcess::capture(child.0.id()).unwrap();
            let (dir, original) = fixture().await;
            original
                .save_devices(&*original.state.lock().await)
                .unwrap();
            let mut old =
                peer_with_process(&original, "old-native-uuid", source, Some(identity.clone()))
                    .await;
            let mut observed = original.subscribe();
            old.send(json!({"method":"thread/started","params":{"thread":{"id":"history"}}}))
                .await;
            observed.recv().await.unwrap();
            let generation = original.state.lock().await.runtimes["old-native-uuid"]
                .connection_id
                .clone();
            old.offline();
            original
                .native_events
                .send(NativeEvent::Disconnected {
                    runtime_id: "old-native-uuid".into(),
                    connection_id: generation.clone(),
                })
                .await
                .unwrap();
            event_barrier(&original, "old-native-uuid", &generation).await;
            let claims: Value =
                serde_json::from_slice(&std::fs::read(dir.path().join("ownership.json")).unwrap())
                    .unwrap();
            assert_eq!(claims[0]["native_process"], json!(identity));
            let (b, rx) = Broker::open(original.config.clone(), dir.path().into()).unwrap();
            tokio::spawn(b.clone().receive_events(rx));
            let mut new = peer(&b, "a", source).await;
            let task = resume(b.clone());
            loaded(&mut new, &[]).await;
            let error = finish(task).await.unwrap_err();
            assert_eq!(crate::error::code(&error), Code::Rejected);
            assert!(
                child.0.try_wait().unwrap().is_none(),
                "old native is still alive despite helper restart"
            );
            child.0.kill().unwrap();
            child.0.wait().unwrap();
            let mut task = tokio::spawn({
                let b = b.clone();
                async move {
                    b.request("phone", "codex/request", json!({"commandId":"after-native-exit","runtimeId":"a","method":"thread/resume","params":{"threadId":"history","excludeTurns":true}})).await
                }
            });
            loaded(&mut new, &[]).await;
            let read = tokio::select! {
                result = &mut task => panic!("verified native exit must permit exact-ID history proof: {result:?}"),
                request = new.recv() => request,
            };
            assert_eq!(read["method"], "thread/read");
            assert_eq!(
                read["params"],
                json!({"threadId":"history","includeTurns":false})
            );
            new.reply(&read, json!({"thread":{"id":"history"}})).await;
            loaded(&mut new, &[]).await;
            let resumed = new.recv().await;
            assert_eq!(resumed["method"], "thread/resume");
            assert_eq!(
                resumed["params"],
                json!({"threadId":"history","excludeTurns":true})
            );
            new.reply(&resumed, json!({"thread":{"id":"history"}}))
                .await;
            assert!(finish(task).await.is_ok());
            let persisted: Value =
                serde_json::from_slice(&std::fs::read(dir.path().join("ownership.json")).unwrap())
                    .unwrap();
            assert!(
                persisted
                    .as_array()
                    .unwrap()
                    .iter()
                    .all(|claim| claim["id"]["runtime_id"] != "old-native-uuid")
            );
        }
    }
    fn resume(b: Arc<Broker>) -> tokio::task::JoinHandle<Result<Value>> {
        tokio::spawn(async move {
            b.request("phone", "codex/request", json!({"commandId":"resume","runtimeId":"a","method":"thread/resume","params":{"threadId":"history","excludeTurns":true}})).await
        })
    }
    pub(super) async fn finish(task: tokio::task::JoinHandle<Result<Value>>) -> Result<Value> {
        tokio::time::timeout(Duration::from_secs(3), task)
            .await
            .expect("broker timeout")
            .unwrap()
    }
    async fn event_barrier(b: &Broker, runtime: &str, connection: &str) {
        let (reply, rx) = tokio::sync::oneshot::channel();
        b.native_events
            .send(NativeEvent::Snapshot {
                runtime_id: runtime.into(),
                connection_id: connection.into(),
                value: json!({}),
                reply,
            })
            .await
            .unwrap();
        rx.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn inactive_resume_proves_history_then_refreshes_owner_without_changing_native_id() {
        for source in ["desktop", "cli"] {
            let (_dir, b) = fixture().await;
            let mut a = peer(&b, "a", source).await;
            let task = resume(b.clone());
            loaded(&mut a, &[]).await;
            let read = a.recv().await;
            assert_eq!(read["method"], "thread/read");
            assert_eq!(
                read["params"],
                json!({"threadId":"history","includeTurns":false})
            );
            a.reply(&read, json!({"thread":{"id":"history"}})).await;
            loaded(&mut a, &[]).await;
            let request = a.recv().await;
            assert_eq!(request["method"], "thread/resume");
            assert_eq!(
                request["params"],
                json!({"threadId":"history","excludeTurns":true})
            );
            a.reply(&request, json!({"thread":{"id":"history"}})).await;
            assert_eq!(finish(task).await.unwrap()["thread"]["id"], "history");
        }
    }
    #[tokio::test]
    async fn dispatched_disconnect_is_unknown_and_native_rejection_is_redacted() {
        for disconnect in [true, false] {
            let (_dir, b) = fixture().await;
            let mut native = peer(&b, "a", "desktop").await;
            let params =
                json!({"commandId":"write","runtimeId":"a","method":"thread/start","params":{}});
            let task = tokio::spawn({
                let b = b.clone();
                let params = params.clone();
                async move { b.request("phone", "codex/request", params).await }
            });
            loaded(&mut native, &[]).await;
            let request = native.recv().await;
            let disk: Value =
                serde_json::from_slice(&std::fs::read(b.state_dir.join("commands.json")).unwrap())
                    .unwrap();
            assert_eq!(disk["entries"][0]["status"], "Submitted");
            if disconnect {
                drop(native);
            } else {
                native
                    .send(json!({"id":request["id"],"error":{"message":"private-path-and-token"}}))
                    .await;
            }
            let response = crate::session::reply(json!(1), finish(task).await);
            assert_eq!(
                response["error"]["code"],
                if disconnect { "unknown" } else { "rejected" }
            );
            assert!(!response.to_string().contains("private-path-and-token"));
            let status = b
                .request("phone", "commands/status", json!({"commandId":"write"}))
                .await
                .unwrap();
            assert_eq!(
                status["status"],
                if disconnect { "unknown" } else { "rejected" }
            );
            assert!(!status.to_string().contains("private-path-and-token"));
            assert_eq!(
                crate::session::reply(json!(2), b.request("phone", "codex/request", params).await)
                    ["error"]["code"],
                response["error"]["code"]
            );
        }
    }
    #[tokio::test]
    async fn dispatched_timeout_stays_unknown_in_ledger_and_wire_reply() {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "a", "desktop").await;
        let params =
            json!({"commandId":"timeout","runtimeId":"a","method":"thread/start","params":{}});
        let task = tokio::spawn({
            let b = b.clone();
            let params = params.clone();
            async move { b.request("phone", "codex/request", params).await }
        });
        loaded(&mut native, &[]).await;
        assert_eq!(native.recv().await["method"], "thread/start");
        tokio::time::pause();
        tokio::time::advance(Duration::from_secs(91)).await;
        tokio::time::resume();
        let result = crate::session::reply(json!(1), finish(task).await);
        assert_eq!(result["error"]["code"], "unknown");
        assert_eq!(
            b.request("phone", "commands/status", json!({"commandId":"timeout"}))
                .await
                .unwrap()["status"],
            "unknown"
        );
        assert_eq!(
            crate::session::reply(json!(2), b.request("phone", "codex/request", params).await)["error"]
                ["code"],
            "unknown"
        );
    }
    #[tokio::test]
    async fn persistence_failures_distinguish_before_and_after_native_dispatch() {
        for dispatched in [false, true] {
            let (_dir, b) = fixture().await;
            let mut native = peer(&b, "a", "desktop").await;
            let params =
                json!({"commandId":"persist","runtimeId":"a","method":"thread/start","params":{}});
            let block_persist = || {
                std::fs::remove_file(b.state_dir.join("commands.json")).unwrap();
                std::fs::create_dir(b.state_dir.join("commands.json")).unwrap();
            };
            let result = if dispatched {
                let task = tokio::spawn({
                    let b = b.clone();
                    let params = params.clone();
                    async move { b.request("phone", "codex/request", params).await }
                });
                loaded(&mut native, &[]).await;
                let request = native.recv().await;
                block_persist();
                native
                    .reply(&request, json!({"thread":{"id":"created"}}))
                    .await;
                finish(task).await
            } else {
                block_persist();
                let result = b.request("phone", "codex/request", params).await;
                assert!(
                    tokio::time::timeout(Duration::from_millis(100), native.recv())
                        .await
                        .is_err()
                );
                result
            };
            assert_eq!(
                crate::session::reply(json!(1), result)["error"]["code"],
                if dispatched { "unknown" } else { "rejected" }
            );
        }
    }
    #[tokio::test]
    async fn terminated_writer_recovers_attachments_and_commands_after_clock_rollback() {
        use base64::{Engine, engine::general_purpose::STANDARD};
        let upload = json!({"commandId":"durable-upload","mime":"image/png","base64":STANDARD.encode(b"\x89PNG\r\n\x1a\nheader")});
        let pending =
            json!({"commandId":"pending","runtimeId":"absent","method":"thread/start","params":{}});
        if let Ok(stage) = std::env::var("POCKET_AGENT_RESTART_STAGE") {
            let root = PathBuf::from(std::env::var_os("POCKET_AGENT_RESTART_STATE").unwrap());
            let (b, _) = Broker::open(Config::load(&root).unwrap(), root.clone()).unwrap();
            if stage == "writer" {
                // Only this disposable process observes a future clock. The
                // reader starts with the real (lower) wall time and a fresh CLOCK.
                storage::observe(storage::now() + 3600);
                let accepted = b
                    .request("phone", "attachment/upload", upload)
                    .await
                    .unwrap();
                let mut s = b.state.lock().await;
                s.ledger
                    .begin(
                        CommandKey {
                            caller_id: "phone".into(),
                            command_id: "pending".into(),
                        },
                        &serde_json::to_vec(&json!({"method":"codex/request","params":pending}))
                            .unwrap(),
                        storage::now(),
                    )
                    .unwrap();
                b.save_ledger(&s).unwrap();
                std::fs::write(
                    root.join("writer-ready.json"),
                    serde_json::to_vec(&json!({"clock":storage::now(),"accepted":accepted}))
                        .unwrap(),
                )
                .unwrap();
                std::future::pending::<()>().await;
            } else {
                assert_eq!(stage, "reader");
                let marker: Value =
                    serde_json::from_slice(&std::fs::read(root.join("writer-ready.json")).unwrap())
                        .unwrap();
                assert!(storage::now() >= marker["clock"].as_u64().unwrap());
                assert_eq!(
                    b.request("phone", "attachment/upload", upload)
                        .await
                        .unwrap(),
                    marker["accepted"]
                );
                let mut input = json!({"input":[{"type":"pocketImage","attachmentId":marker["accepted"]["attachmentId"]}]});
                attachments::translate(&mut input, "phone", &b.state.lock().await.attachments)
                    .unwrap();
                assert_eq!(
                    std::fs::read(input["input"][0]["path"].as_str().unwrap()).unwrap(),
                    b"\x89PNG\r\n\x1a\nheader"
                );
                assert_eq!(
                    crate::session::reply(
                        json!(1),
                        b.request("phone", "codex/request", pending).await
                    )["error"]["code"],
                    "unknown"
                );
                assert_eq!(
                    std::fs::read_dir(root.join("attachments")).unwrap().count(),
                    1
                );
                let disk: Value =
                    serde_json::from_slice(&std::fs::read(root.join("commands.json")).unwrap())
                        .unwrap();
                let pending = disk["entries"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|e| e["key"]["command_id"] == "pending")
                    .unwrap();
                assert_eq!(pending["status"], "Unknown");
            }
            return;
        }
        // Exercise the checkout's NTFS volume, not a different default target.
        let dir = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR")).unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        config.save(dir.path()).unwrap();
        let (b, _) = Broker::open(config, dir.path().into()).unwrap();
        {
            let mut s = b.state.lock().await;
            s.devices
                .add(
                    "phone".into(),
                    "Phone".into(),
                    "test-secret",
                    storage::now(),
                )
                .unwrap();
            b.save_devices(&s).unwrap();
        }
        drop(b);
        let child = |stage: &str| {
            let mut command = std::process::Command::new(std::env::current_exe().unwrap());
            command.args(["--exact","broker::tests::terminated_writer_recovers_attachments_and_commands_after_clock_rollback","--nocapture"])
                .env("POCKET_AGENT_RESTART_STAGE",stage).env("POCKET_AGENT_RESTART_STATE",dir.path());
            #[cfg(windows)]
            {
                use std::os::windows::process::CommandExt;
                command.creation_flags(0x08000000);
            }
            command
        };
        let mut writer = child("writer")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
        let ready = dir.path().join("writer-ready.json");
        while !ready.exists()
            && tokio::time::Instant::now() < deadline
            && writer.try_wait().unwrap().is_none()
        {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let _ = writer.kill();
        writer.wait().unwrap();
        assert!(
            ready.exists(),
            "writer did not persist state before termination"
        );
        let reader = child("reader").output().unwrap();
        assert!(
            reader.status.success(),
            "fresh-process recovery failed: {}",
            String::from_utf8_lossy(&reader.stderr)
        );
    }
    #[tokio::test]
    async fn history_proof_cannot_override_a_new_foreign_owner() {
        let (_dir, b) = fixture().await;
        let mut a = peer(&b, "a", "desktop").await;
        let mut other = peer(&b, "z", "desktop").await;
        let task = resume(b.clone());
        loaded(&mut a, &[]).await;
        loaded(&mut other, &[]).await;
        let read = a.recv().await;
        a.reply(&read, json!({"thread":{"id":"history"}})).await;
        loaded(&mut a, &[]).await;
        loaded(&mut other, &["history"]).await;
        assert!(
            finish(task)
                .await
                .unwrap_err()
                .to_string()
                .contains("another runtime")
        );
    }
    #[tokio::test]
    async fn disconnected_owner_blocks_resume_even_with_shared_history() {
        let (_dir, b) = fixture().await;
        let mut target = peer(&b, "a", "cli").await;
        let mut previous = peer(&b, "z", "desktop").await;
        let generation = b.state.lock().await.runtimes["z"].connection_id.clone();
        let mut observed = b.subscribe();
        previous
            .send(json!({"method":"thread/started","params":{"thread":{"id":"history"}}}))
            .await;
        observed.recv().await.unwrap();
        event_barrier(&b, "z", &generation).await;
        previous.offline();
        b.native_events
            .send(NativeEvent::Disconnected {
                runtime_id: "z".into(),
                connection_id: generation.clone(),
            })
            .await
            .unwrap();
        event_barrier(&b, "z", &generation).await;
        let mut task = resume(b.clone());
        loaded(&mut target, &[]).await;
        tokio::select! {
            result=&mut task => { assert!(result.unwrap().is_err()); }
            read=target.recv() => {
                assert_eq!(read["method"],"thread/read");
                target.reply(&read,json!({"thread":{"id":"history"}})).await;
                loaded(&mut target,&[]).await;
                let request=target.recv().await;
                assert_ne!(request["method"],"thread/resume","a lost helper connection did not release the native thread");
            }
        }
    }
    #[tokio::test]
    async fn inactive_history_management_uses_exact_proof_without_loading_or_turns() {
        for source in ["desktop", "cli"] {
            for method in ["thread/name/set", "thread/archive", "thread/unarchive"] {
                let (_dir, b) = fixture().await;
                let mut native = peer(&b, "a", source).await;
                let mut params = json!({"threadId":"history"});
                if method == "thread/name/set" {
                    params["name"] = json!("renamed");
                }
                let mut task = tokio::spawn({
                    let b = b.clone();
                    let params = params.clone();
                    async move {
                        b.request("phone","codex/request",json!({"commandId":"manage","runtimeId":"a","method":method,"params":params})).await
                    }
                });
                loaded(&mut native, &[]).await;
                let proof = tokio::select! {
                    result=&mut task => panic!("inactive management rejected before proof: {:?}",result.unwrap()),
                    request=native.recv()=>request,
                };
                assert_eq!(proof["method"], "thread/read");
                assert_eq!(
                    proof["params"],
                    json!({"threadId":"history","includeTurns":false})
                );
                native
                    .reply(&proof, json!({"thread":{"id":"history"}}))
                    .await;
                loaded(&mut native, &[]).await;
                let request = native.recv().await;
                assert_eq!(request["method"], method);
                assert_eq!(request["params"], params);
                native.reply(&request, json!({})).await;
                finish(task).await.unwrap();
                assert!(b.state.lock().await.runtimes["a"].loaded.is_empty());
            }
        }
    }
    #[tokio::test]
    async fn history_management_rejects_foreign_ambiguous_and_uncertain_owners() {
        for method in ["thread/name/set", "thread/archive", "thread/unarchive"] {
            for kind in ["foreign", "ambiguous", "uncertain"] {
                for after_proof in [false, true] {
                    let (_dir, b) = fixture().await;
                    let mut target = peer(&b, "a", "desktop").await;
                    let mut other = peer(&b, "z", "desktop").await;
                    if !after_proof && kind == "uncertain" {
                        let mut observed = b.subscribe();
                        other.send(json!({"method":"thread/started","params":{"thread":{"id":"history"}}})).await;
                        observed.recv().await.unwrap();
                        other.offline();
                    }
                    let mut params = json!({"threadId":"history"});
                    if method == "thread/name/set" {
                        params["name"] = json!("Renamed history");
                    }
                    let task = tokio::spawn({
                        let b = b.clone();
                        async move {
                            b.request("phone","codex/request",json!({"commandId":"manage","runtimeId":"a","method":method,"params":params})).await
                        }
                    });
                    if after_proof {
                        loaded(&mut target, &[]).await;
                        loaded(&mut other, &[]).await;
                        let proof = target.recv().await;
                        assert_eq!(proof["method"], "thread/read");
                        if kind == "uncertain" {
                            let mut observed = b.subscribe();
                            other.send(json!({"method":"thread/started","params":{"thread":{"id":"history"}}})).await;
                            observed.recv().await.unwrap();
                            other.offline();
                        }
                        target
                            .reply(&proof, json!({"thread":{"id":"history"}}))
                            .await;
                    }
                    loaded(
                        &mut target,
                        if kind == "ambiguous" {
                            &["history"]
                        } else {
                            &[]
                        },
                    )
                    .await;
                    if kind != "uncertain" {
                        loaded(&mut other, &["history"]).await;
                    }
                    assert_eq!(
                        crate::error::code(&finish(task).await.unwrap_err()),
                        Code::Rejected
                    );
                    assert!(
                        b.state.lock().await.runtimes["a"].loaded.is_empty() || kind == "ambiguous"
                    );
                }
            }
        }
    }
    #[tokio::test]
    async fn unoffered_decline_is_rejected_without_dispatch_and_offered_cancel_keeps_native_id() {
        for native_id in [json!(0), json!(9007199254740993_u64), json!("native/0007")] {
            let (_dir, b) = fixture().await;
            let mut native = peer(&b, "cli-test", "cli").await;
            let mut events = b.subscribe();
            native.send(json!({
                "id":native_id,"method":"item/commandExecution/requestApproval",
                "params":{"threadId":"synthetic-thread","availableDecisions":[
                    "accept",{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["synthetic"]}},"cancel"
                ]}
            })).await;
            events.recv().await.unwrap();
            let token =
                b.sync(&json!({})).await.unwrap()["pendingRequests"][0]["event"]["id"].clone();
            let rejected = json!({"commandId":"unoffered","runtimeId":"cli-test","requestId":token,"response":{"decision":"decline"}});
            for _ in 0..2 {
                let error = b
                    .request("phone", "approvals/respond", rejected.clone())
                    .await
                    .unwrap_err();
                assert_eq!(crate::error::code(&error), Code::Rejected);
            }
            assert_eq!(
                b.request("phone", "commands/status", json!({"commandId":"unoffered"}))
                    .await
                    .unwrap()["status"],
                "rejected"
            );
            let pending = b.sync(&json!({})).await.unwrap()["pendingRequests"].clone();
            assert_eq!(pending.as_array().unwrap().len(), 1);
            assert_eq!(pending[0]["event"]["id"], token);
            assert!(
                tokio::time::timeout(Duration::from_millis(30), native.recv())
                    .await
                    .is_err(),
                "a rejected decision must not reach native"
            );

            // A separate, explicitly chosen advertised decision can consume the
            // still-pending token. This is not a retry of the rejected command.
            let cancel = json!({"commandId":"offered","runtimeId":"cli-test","requestId":token,"response":{"decision":"cancel"}});
            assert_eq!(
                b.request("phone", "approvals/respond", cancel.clone())
                    .await
                    .unwrap(),
                json!({"status":"submitted"})
            );
            assert_eq!(
                native.recv().await,
                json!({"id":native_id,"result":{"decision":"cancel"}})
            );
            assert_eq!(
                b.request("phone", "commands/status", json!({"commandId":"offered"}))
                    .await
                    .unwrap()["status"],
                "submitted"
            );
            native.send(json!({"method":"serverRequest/resolved","params":{"threadId":"synthetic-thread","requestId":native_id}})).await;
            events.recv().await.unwrap();
            assert_eq!(
                b.request("phone", "commands/status", json!({"commandId":"offered"}))
                    .await
                    .unwrap()["status"],
                "unknown"
            );
            assert!(
                b.sync(&json!({})).await.unwrap()["pendingRequests"]
                    .as_array()
                    .unwrap()
                    .is_empty()
            );
            assert_eq!(
                crate::error::code(
                    &b.request("phone", "approvals/respond", cancel)
                        .await
                        .unwrap_err()
                ),
                Code::Unknown
            );
            assert!(
                tokio::time::timeout(Duration::from_millis(30), native.recv())
                    .await
                    .is_err(),
                "an unknown response must not replay"
            );
        }
    }

    #[tokio::test]
    async fn approval_submission_without_native_handling_is_unknown_after_disconnect_and_restart() {
        let (dir, b) = fixture().await;
        b.save_devices(&*b.state.lock().await).unwrap();
        let mut native = peer(&b, "a", "desktop").await;
        let mut events = b.subscribe();
        native.send(json!({"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"t"}})).await;
        events.recv().await.unwrap();
        let token = b.sync(&json!({})).await.unwrap()["pendingRequests"][0]["event"]["id"].clone();
        let params = json!({"commandId":"withheld","runtimeId":"a","requestId":token,"response":{"decision":"decline"}});
        assert_eq!(
            b.request("phone", "approvals/respond", params.clone())
                .await
                .unwrap()["status"],
            "submitted"
        );
        assert_eq!(native.recv().await["result"], params["response"]);
        // The old wire shape is only an arbitration receipt, never native proof.
        native.send(json!({"method":"pocket/approvalResolved","params":{"requestId":7,"threadId":"t","winner":"thisConnection","response":{"decision":"decline"}}})).await;
        events.recv().await.unwrap();
        assert_eq!(
            b.request("phone", "commands/status", json!({"commandId":"withheld"}))
                .await
                .unwrap()["status"],
            "submitted"
        );
        native
            .send(
                json!({"method":"pocket/approvalResolved","params":{"requestId":7,"threadId":"t"}}),
            )
            .await;
        events.recv().await.unwrap();
        assert!(
            b.sync(&json!({})).await.unwrap()["pendingRequests"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        let mut new_command = params.clone();
        new_command["commandId"] = json!("same-closed-token");
        assert_eq!(
            crate::error::code(
                &b.request("phone", "approvals/respond", new_command)
                    .await
                    .unwrap_err()
            ),
            Code::Rejected
        );
        // A helper crash before a native resolution also restores Submitted as Unknown.
        let (crashed, _) = Broker::open(b.config.clone(), dir.path().into()).unwrap();
        assert_eq!(
            crashed
                .request("phone", "commands/status", json!({"commandId":"withheld"}))
                .await
                .unwrap()["status"],
            "unknown"
        );
        assert_eq!(
            crate::error::code(
                &crashed
                    .request("phone", "approvals/respond", params.clone())
                    .await
                    .unwrap_err()
            ),
            Code::Unknown
        );
        drop(crashed);
        // Close the real loopback socket without providing a native resolution.
        drop(native);
        let disconnected = tokio::time::timeout(Duration::from_secs(3), events.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(disconnected["method"], "runtimes/changed");
        assert_eq!(
            b.request("phone", "commands/status", json!({"commandId":"withheld"}))
                .await
                .unwrap()["status"],
            "unknown"
        );
        assert_eq!(
            crate::error::code(
                &b.request("phone", "approvals/respond", params.clone())
                    .await
                    .unwrap_err()
            ),
            Code::Unknown
        );
        let (restored, _) = Broker::open(b.config.clone(), dir.path().into()).unwrap();
        assert_eq!(
            restored
                .request("phone", "commands/status", json!({"commandId":"withheld"}))
                .await
                .unwrap()["status"],
            "unknown"
        );
        assert_eq!(
            crate::error::code(
                &restored
                    .request("phone", "approvals/respond", params)
                    .await
                    .unwrap_err()
            ),
            Code::Unknown
        );
    }
    #[tokio::test]
    async fn approval_native_cancel_resolution_remains_unknown_even_with_matching_winner() {
        for (source, receipt, correlated_resolution, matches_response, canceled) in [
            ("desktop", false, false, false, false),
            ("desktop", true, true, true, false),
            ("desktop", true, true, true, true),
            ("desktop", true, true, false, false),
            ("desktop", false, true, true, false),
            ("cli", true, true, true, false),
            ("cli", false, false, false, true),
        ] {
            let (dir, b) = fixture().await;
            b.save_devices(&*b.state.lock().await).unwrap();
            let mut native = peer(&b, "a", source).await;
            let mut events = b.subscribe();
            native.send(json!({"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"t"}})).await;
            events.recv().await.unwrap();
            let token =
                b.sync(&json!({})).await.unwrap()["pendingRequests"][0]["event"]["id"].clone();
            let params = json!({"commandId":"decision","runtimeId":"a","requestId":token,"response":{"decision":"decline"}});
            if canceled {
                // Native clears its callback at turn interruption; an in-flight
                // phone response can still arrive before the resolution notice.
                native.send(json!({"method":"turn/completed","params":{"threadId":"t","turn":{"id":"u","status":"interrupted"}}})).await;
                events.recv().await.unwrap();
            }
            assert_eq!(
                b.request("phone", "approvals/respond", params.clone())
                    .await
                    .unwrap()["status"],
                "submitted"
            );
            assert_eq!(native.recv().await["result"], params["response"]);
            if receipt {
                native.send(json!({"method":"pocket/approvalSubmitted","params":{"requestId":7,"threadId":"t","winner":"thisConnection","response":{"decision":"decline"}}})).await;
                events.recv().await.unwrap();
                native.send(json!({"method":"pocket/approvalResolved","params":{"requestId":7,"threadId":"t"}})).await;
                events.recv().await.unwrap();
                assert_eq!(
                    b.request("phone", "commands/status", json!({"commandId":"decision"}))
                        .await
                        .unwrap()["status"],
                    if source == "desktop" {
                        "submitted"
                    } else {
                        "unknown"
                    }
                );
            }
            let mut event =
                json!({"method":"pocket/approvalResolved","params":{"requestId":7,"threadId":"t"}});
            if source == "cli" && !correlated_resolution {
                event["method"] = json!("serverRequest/resolved");
            }
            if correlated_resolution {
                event["params"]["nativeResolved"] = json!(true);
                event["params"]["winner"] = json!("thisConnection");
                event["params"]["response"] =
                    json!({"decision":if matches_response {"decline"}else{"accept"}});
            }
            native.send(event).await;
            events.recv().await.unwrap();
            // Native uses this same resolution when a turn transition clears
            // the request without applying the already-submitted phone reply.
            // Even matching arbitration plus resolution cannot prove application.
            let expected = "unknown";
            assert_eq!(
                b.request("phone", "commands/status", json!({"commandId":"decision"}))
                    .await
                    .unwrap()["status"],
                expected
            );
            assert!(
                b.sync(&json!({})).await.unwrap()["pendingRequests"]
                    .as_array()
                    .unwrap()
                    .is_empty()
            );
            let (restored, _) = Broker::open(b.config.clone(), dir.path().into()).unwrap();
            assert_eq!(
                restored
                    .request("phone", "commands/status", json!({"commandId":"decision"}))
                    .await
                    .unwrap()["status"],
                expected
            );
            assert_eq!(
                crate::error::code(
                    &restored
                        .request("phone", "approvals/respond", params.clone())
                        .await
                        .unwrap_err()
                ),
                Code::Unknown
            );
            assert_eq!(
                crate::session::reply(
                    json!(1),
                    b.request("phone", "approvals/respond", params).await
                )["error"]["code"],
                "unknown"
            );
        }
    }
    #[tokio::test]
    async fn reconnect_recovers_pending_snapshot_with_fresh_broker_token() {
        let (_dir, b) = fixture().await;
        let mut old = peer(&b, "a", "desktop").await;
        let mut events = b.subscribe();
        let pending = json!({"id":"original-native-alias","method":"item/commandExecution/requestApproval","params":{"threadId":"t","command":"test-only"}});
        old.send(pending.clone()).await;
        events.recv().await.unwrap();
        let old_token =
            b.sync(&json!({})).await.unwrap()["pendingRequests"][0]["event"]["id"].clone();
        old.offline();
        let (connection, mut replacement) =
            test_peer::connect("a", "desktop", &b.state_dir, b.native_events.clone()).await;
        let mut task = tokio::spawn({
            let b = b.clone();
            async move { b.add_runtime(connection).await }
        });
        let snapshot = tokio::select! {
            result=&mut task=>panic!("replacement did not request pending snapshot: {:?}",result.unwrap()),
            request=replacement.recv()=>request,
        };
        assert_eq!(snapshot["method"], "pocket/approvals/list");
        replacement
            .reply(&snapshot, json!({"data":[pending]}))
            .await;
        task.await.unwrap().unwrap();
        let sync = b.sync(&json!({})).await.unwrap();
        let requests = sync["pendingRequests"].as_array().unwrap();
        assert_eq!(requests.len(), 1);
        let token = requests[0]["event"]["id"].clone();
        assert_ne!(token, old_token);
        assert_eq!(requests[0]["event"]["params"], pending["params"]);
        assert!(b.request("phone","approvals/respond",json!({"commandId":"stale","runtimeId":"a","requestId":old_token,"response":{"decision":"decline"}})).await.is_err());
        b.request("phone","approvals/respond",json!({"commandId":"current","runtimeId":"a","requestId":token,"response":{"decision":"decline"}})).await.unwrap();
        assert_eq!(replacement.recv().await["id"], pending["id"]);
    }
    #[tokio::test]
    async fn ownership_claim_survives_broker_restart_and_malformed_inventory() {
        let (dir, b) = fixture().await;
        let mut old = peer(&b, "z", "desktop").await;
        let generation = b.state.lock().await.runtimes["z"].connection_id.clone();
        let mut observed = b.subscribe();
        old.send(json!({"method":"thread/started","params":{"thread":{"id":"history"}}}))
            .await;
        observed.recv().await.unwrap();
        event_barrier(&b, "z", &generation).await;
        let (restored, _) = Broker::open(b.config.clone(), dir.path().into()).unwrap();
        let key = ThreadKey {
            computer_id: b.config.computer_id.clone(),
            config_store_id: storage::config_store(dir.path()).unwrap(),
            native_thread_id: "history".into(),
        };
        assert_ne!(
            restored
                .directory(&*restored.state.lock().await)
                .unwrap()
                .resolve_write(&key),
            Err(ResolveError::NoOnlineOwner)
        );
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.refresh_loaded().await }
        });
        let request = old.recv().await;
        old.reply(&request, json!({"data":"invalid","nextCursor":null}))
            .await;
        assert!(
            task.await.unwrap().is_err(),
            "malformed inventory must not release a known claim"
        );
        assert!(
            b.state.lock().await.runtimes["z"]
                .loaded
                .contains("history")
        );
    }
    #[tokio::test]
    async fn delayed_inventory_cannot_erase_a_new_native_ownership_event() {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "a", "desktop").await;
        let mut events = b.subscribe();
        let refresh = tokio::spawn({
            let b = b.clone();
            async move { b.refresh_loaded().await }
        });
        let request = native.recv().await;
        native
            .send(json!({"method":"thread/started","params":{"thread":{"id":"newly-running"}}}))
            .await;
        events.recv().await.unwrap();
        native
            .reply(&request, json!({"data":[],"nextCursor":null}))
            .await;
        assert!(
            refresh.await.unwrap().is_err(),
            "a delayed pre-event inventory cannot prove release"
        );
        assert!(
            b.state.lock().await.runtimes["a"]
                .loaded
                .contains("newly-running")
        );
    }
    #[tokio::test]
    async fn mismatched_or_failed_history_proof_never_resumes() {
        for failure in [false, true] {
            let (_dir, b) = fixture().await;
            let mut a = peer(&b, "a", "desktop").await;
            let task = resume(b.clone());
            loaded(&mut a, &[]).await;
            let read = a.recv().await;
            assert_eq!(read["method"], "thread/read");
            if failure {
                a.send(json!({"id":read["id"],"error":{"code":-1,"message":"missing"}}))
                    .await;
            } else {
                a.reply(&read, json!({"thread":{"id":"different"}})).await;
            }
            assert!(finish(task).await.is_err());
        }
    }
    #[tokio::test]
    async fn stale_loaded_list_cannot_populate_replacement() {
        let (_dir, b) = fixture().await;
        let mut old = peer(&b, "a", "desktop").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.refresh_loaded().await }
        });
        let request = old.recv().await;
        old.offline();
        let _new = peer(&b, "a", "desktop").await;
        old.reply(&request, json!({"data":["stale"],"nextCursor":null}))
            .await;
        let _ = tokio::time::timeout(Duration::from_secs(3), task)
            .await
            .unwrap()
            .unwrap();
        assert!(b.state.lock().await.runtimes["a"].loaded.is_empty());
    }
    #[tokio::test]
    async fn stale_events_leave_replacement_approval_and_journal_unchanged() {
        let (_dir, b) = fixture().await;
        let old = peer(&b, "a", "desktop").await;
        let stale = b.state.lock().await.runtimes["a"].connection_id.clone();
        old.offline();
        let mut current = peer(&b, "a", "desktop").await;
        let generation = b.state.lock().await.runtimes["a"].connection_id.clone();
        let mut notifications = b.subscribe();
        current.send(json!({"id":1,"method":"item/commandExecution/requestApproval","params":{"threadId":"current"}})).await;
        tokio::time::timeout(Duration::from_secs(3), notifications.recv())
            .await
            .unwrap()
            .unwrap();
        let snapshot = b.sync(&json!({})).await.unwrap();
        let token = snapshot["pendingRequests"][0]["event"]["id"].clone();
        b.request("phone","approvals/respond",json!({"commandId":"current-approval","runtimeId":"a","requestId":token,"response":{"decision":"accept"}})).await.unwrap();
        assert_eq!(current.recv().await["id"], 1);
        b.state
            .lock()
            .await
            .runtimes
            .get_mut("a")
            .unwrap()
            .loaded
            .insert("current".into());
        for value in [
            json!({"method":"thread/unloaded","params":{"threadId":"current"}}),
            json!({"method":"serverRequest/resolved","params":{"requestId":1}}),
            json!({"id":2,"method":"item/commandExecution/requestApproval","params":{"threadId":"stale"}}),
        ] {
            b.native_events
                .send(NativeEvent::Message {
                    runtime_id: "a".into(),
                    connection_id: stale.clone(),
                    value,
                })
                .await
                .unwrap();
        }
        b.native_events
            .send(NativeEvent::Disconnected {
                runtime_id: "a".into(),
                connection_id: stale.clone(),
            })
            .await
            .unwrap();
        let (reply, rx) = tokio::sync::oneshot::channel();
        b.native_events
            .send(NativeEvent::Snapshot {
                runtime_id: "a".into(),
                connection_id: stale,
                value: json!({"thread":{"id":"stale"}}),
                reply,
            })
            .await
            .unwrap();
        assert!(matches!(rx.await.unwrap(), Err(RpcFailure::Disconnected)));
        event_barrier(&b, "a", &generation).await;
        assert_eq!(
            b.sync(&json!({})).await.unwrap()["cursors"],
            snapshot["cursors"]
        );
        assert!(
            b.state.lock().await.runtimes["a"]
                .loaded
                .contains("current")
        );
        assert_eq!(
            b.request(
                "phone",
                "commands/status",
                json!({"commandId":"current-approval"})
            )
            .await
            .unwrap()["status"],
            "submitted"
        );
    }
    #[tokio::test]
    async fn stale_cli_subscription_cannot_authorize_replacement_before_inventory() {
        let (_dir, b) = fixture().await;
        let mut old = peer(&b, "a", "cli").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.refresh_loaded().await }
        });
        loaded(&mut old, &["loaded"]).await;
        let request = old.recv().await;
        assert_eq!(request["method"], "thread/resume");
        assert_eq!(
            request["params"],
            json!({"threadId":"loaded","excludeTurns":true})
        );
        old.offline();
        let mut new = peer(&b, "a", "cli").await;
        old.reply(&request, json!({"thread":{"id":"loaded"}})).await;
        let _ = tokio::time::timeout(Duration::from_secs(3), task)
            .await
            .unwrap()
            .unwrap();
        let s = b.state.lock().await;
        assert_eq!(s.runtimes["a"].loaded, BTreeSet::from(["loaded".into()]));
        assert!(!s.runtimes["a"].inventory_ready);
        assert!(s.runtimes["a"].subscribed.is_empty());
        drop(s);
        let refreshed = tokio::spawn({
            let b = b.clone();
            async move { b.refresh_loaded().await }
        });
        loaded(&mut new, &[]).await;
        refreshed.await.unwrap().unwrap();
        assert!(b.state.lock().await.runtimes["a"].loaded.is_empty());
    }
    #[tokio::test]
    async fn stale_start_completion_is_unknown_and_does_not_load_replacement() {
        let (_dir, b) = fixture().await;
        let mut old = peer(&b, "a", "desktop").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request("phone", "codex/request", json!({"commandId":"start","runtimeId":"a","method":"thread/start","params":{}})).await
            }
        });
        loaded(&mut old, &[]).await;
        let request = old.recv().await;
        assert_eq!(request["method"], "thread/start");
        old.offline();
        let _new = peer(&b, "a", "desktop").await;
        old.reply(&request, json!({"thread":{"id":"stale"}})).await;
        assert!(finish(task).await.is_err());
        assert!(b.state.lock().await.runtimes["a"].loaded.is_empty());
        assert_eq!(
            b.request("phone", "commands/status", json!({"commandId":"start"}))
                .await
                .unwrap()["status"],
            "unknown"
        );
    }
    #[tokio::test]
    async fn replacement_retires_approval_tokens_and_persists_unknown_before_old_disconnect() {
        let (_dir, b) = fixture().await;
        let mut old = peer(&b, "a", "desktop").await;
        let old_generation = b.state.lock().await.runtimes["a"].connection_id.clone();
        let mut notifications = b.subscribe();
        for id in [1, 2] {
            old.send(json!({"id":id,"method":"item/commandExecution/requestApproval","params":{"threadId":"t"}})).await;
            tokio::time::timeout(Duration::from_secs(3), notifications.recv())
                .await
                .unwrap()
                .unwrap();
        }
        let sync = b.sync(&json!({})).await.unwrap();
        let token = sync["pendingRequests"][0]["event"]["id"].clone();
        let response = json!({"commandId":"approval","runtimeId":"a","requestId":token,"response":{"decision":"accept"}});
        b.request("phone", "approvals/respond", response.clone())
            .await
            .unwrap();
        let native = old.recv().await;
        let native_id = native["id"].clone();
        old.offline();
        let _new = peer(&b, "a", "desktop").await;
        assert!(
            b.sync(&json!({})).await.unwrap()["pendingRequests"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            b.request("phone", "commands/status", json!({"commandId":"approval"}))
                .await
                .unwrap()["status"],
            "unknown"
        );
        let disk: Value =
            serde_json::from_slice(&std::fs::read(b.state_dir.join("commands.json")).unwrap())
                .unwrap();
        assert_eq!(disk["entries"][0]["status"], "Unknown");
        let generation = b.state.lock().await.runtimes["a"].connection_id.clone();
        for event in [
            NativeEvent::Disconnected {
                runtime_id: "a".into(),
                connection_id: old_generation.clone(),
            },
            NativeEvent::Message {
                runtime_id: "a".into(),
                connection_id: old_generation,
                value: json!({"method":"serverRequest/resolved","params":{"requestId":native_id}}),
            },
        ] {
            b.native_events.send(event).await.unwrap();
        }
        event_barrier(&b, "a", &generation).await;
        assert!(
            b.request("phone", "approvals/respond", response)
                .await
                .unwrap_err()
                .to_string()
                .contains("unknown")
        );
        assert_eq!(b.state.lock().await.runtimes["a"].connection_id, generation);
    }

    #[tokio::test]
    async fn invalid_restored_attachment_metadata_never_deletes_outside_files() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("state");
        let outside = dir.path().join("keep.png");
        std::fs::write(&outside, b"keep").unwrap();
        storage::private_directory(&root).unwrap();
        let absolute_id = uuid::Uuid::new_v4().to_string();
        let traversal_id = uuid::Uuid::new_v4().to_string();
        let metadata = json!({(absolute_id):{"owner":"phone","path":outside,"expires":0},(traversal_id):{"owner":"phone","path":root.join("attachments/../../keep.png"),"expires":storage::now()+60}});
        storage::atomic_write(
            &root.join("attachments.json"),
            &serde_json::to_vec(&metadata).unwrap(),
        )
        .unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (b, _) = Broker::open(config, root).unwrap();
        assert_eq!(std::fs::read(outside).unwrap(), b"keep");
        assert!(b.state.lock().await.attachments.is_empty());
    }

    #[tokio::test]
    async fn device_save_failure_keeps_pairing_tombstone_and_rolls_back_device() {
        let (dir, b) = fixture().await;
        storage::atomic_write(
            &dir.path().join("pairing.json"),
            &serde_json::to_vec(
                &json!({"tokenHash":auth::hash("once"),"expiresAt":storage::now()+60}),
            )
            .unwrap(),
        )
        .unwrap();
        std::fs::create_dir(dir.path().join("devices.json")).unwrap();
        let p = json!({"pairingToken":"once","deviceId":"new","deviceName":"Phone"});
        assert!(b.pair(&p).await.is_err());
        assert!(!b.device_exists("new").await);
        let tombstone: Value =
            serde_json::from_slice(&std::fs::read(dir.path().join("pairing.json")).unwrap())
                .unwrap();
        assert_eq!(tombstone, json!({"used":true,"tokenHash":"","expiresAt":0}));
        assert!(b.pair(&p).await.is_err());
    }
    #[test]
    fn approval_response_does_not_consume_malformed_input() {
        let event = json!({"method":"item/commandExecution/requestApproval","params":{"availableDecisions":["accept","decline"]}});
        assert!(validate_approval(&event, &json!({"decision":"accept"})).is_ok());
        assert!(validate_approval(&event, &json!({"decision":"acceptForSession"})).is_err());
        assert!(validate_approval(&event, &json!({"decision":"accept","extra":true})).is_err());
    }
    #[tokio::test]
    async fn pairing_is_one_use_and_no_account_needed() {
        let d = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (b, _) = Broker::open(config, d.path().into()).unwrap();
        storage::atomic_write(
            &d.path().join("pairing.json"),
            &serde_json::to_vec(
                &json!({"tokenHash":auth::hash("once"),"expiresAt":storage::now()+60}),
            )
            .unwrap(),
        )
        .unwrap();
        let p = json!({"pairingToken":"once","deviceId":"phone","deviceName":"Mate"});
        let result = b.pair(&p).await.unwrap();
        let tombstone: Value =
            serde_json::from_slice(&std::fs::read(d.path().join("pairing.json")).unwrap()).unwrap();
        assert_eq!(tombstone, json!({"used":true,"tokenHash":"","expiresAt":0}));
        assert!(b.pair(&p).await.is_err());
        assert_eq!(
            b.authenticate(
                &json!({"protocol":1,"deviceId":"phone","deviceSecret":result["deviceSecret"]})
            )
            .await
            .unwrap(),
            "phone"
        );
    }
    #[tokio::test]
    async fn accepted_command_survives_restart_and_rejects_changed_body() {
        use base64::{Engine, engine::general_purpose::STANDARD};
        let d = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (b, _) = Broker::open(config.clone(), d.path().into()).unwrap();
        {
            let mut s = b.state.lock().await;
            s.devices
                .add("phone".into(), "Mate".into(), "secret", storage::now())
                .unwrap();
            b.save_devices(&s).unwrap();
        }
        let p = json!({"commandId":"upload-1","name":"test.png","mime":"image/png","base64":STANDARD.encode(b"\x89PNG\r\n\x1a\nheader")});
        let first = b
            .request("phone", "attachment/upload", p.clone())
            .await
            .unwrap();
        drop(b);
        let (b, rx) = Broker::open(config, d.path().into()).unwrap();
        tokio::spawn(b.clone().receive_events(rx));
        assert_eq!(
            b.request("phone", "attachment/upload", p.clone())
                .await
                .unwrap(),
            first
        );
        let mut changed = p;
        changed["name"] = json!("different.png");
        assert!(
            b.request("phone", "attachment/upload", changed)
                .await
                .is_err()
        );
        assert_eq!(
            std::fs::read_dir(d.path().join("attachments"))
                .unwrap()
                .count(),
            1
        );
        let mut native = peer(&b, "a", "desktop").await;
        let input = json!({"commandId":"use-image","runtimeId":"a","method":"turn/start","params":{"threadId":"t","input":[{"type":"pocketImage","attachmentId":first["attachmentId"]}]}});
        let task = tokio::spawn({
            let b = b.clone();
            let input = input.clone();
            async move { b.request("phone", "codex/request", input).await }
        });
        loaded(&mut native, &["t"]).await;
        let request = native.recv().await;
        assert_eq!(request["method"], "turn/start");
        assert_eq!(request["params"]["threadId"], "t");
        assert_eq!(request["params"]["input"][0]["type"], "localImage");
        let path = PathBuf::from(request["params"]["input"][0]["path"].as_str().unwrap());
        assert!(path.starts_with(d.path().join("attachments")));
        assert_eq!(std::fs::read(path).unwrap(), b"\x89PNG\r\n\x1a\nheader");
        native
            .reply(&request, json!({"turn":{"id":"native-turn"}}))
            .await;
        assert_eq!(finish(task).await.unwrap()["turn"]["id"], "native-turn");
        assert_eq!(
            b.request("phone", "codex/request", input.clone())
                .await
                .unwrap()["turn"]["id"],
            "native-turn"
        );
        b.state
            .lock()
            .await
            .devices
            .add("other".into(), "Other".into(), "secret", storage::now())
            .unwrap();
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.request("other", "codex/request", input).await }
        });
        loaded(&mut native, &["t"]).await;
        assert!(
            finish(task)
                .await
                .unwrap_err()
                .to_string()
                .contains("not available to this device")
        );
    }
}
