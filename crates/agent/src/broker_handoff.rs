//! Broker child module. The controller declares this module in broker.rs; all
//! upgrade entry points are authenticated local-only and share one Handoff.

use super::Broker;
use crate::handoff::{
    CheckpointImage, CheckpointRef, CheckpointStore, EventArchive, Handoff, HandoffError,
    HostCursor, Lifecycle, Params, Phase, RuntimeIdentity, Snapshot,
};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    time::Duration,
};

impl Broker {
    /// Runs after discover/attach/refresh, before listeners or business writes.
    /// Restores only pending metadata; never responds or reissues a native RPC.
    pub async fn verify_handoff_successor(
        &self,
        handoff: &Handoff,
        cp: &CheckpointRef,
    ) -> std::result::Result<CheckpointImage, HandoffError> {
        self.recover_handoff_successor(handoff, cp, false).await
    }
    /// Reverify under the write serializer, restore metadata, then durably adopt
    /// the checkpoint and open the controller gate. A failure leaves it fenced.
    pub async fn activate_handoff_successor(
        &self,
        handoff: &Handoff,
        cp: &CheckpointRef,
    ) -> std::result::Result<CheckpointImage, HandoffError> {
        self.recover_handoff_successor(handoff, cp, true).await
    }
    async fn recover_handoff_successor(
        &self,
        handoff: &Handoff,
        cp: &CheckpointRef,
        activate: bool,
    ) -> std::result::Result<CheckpointImage, HandoffError> {
        if handoff.expected_checkpoint().as_ref() != Some(cp) || handoff.accepting_writes() {
            return Err(HandoffError::new("handoff_conflict"));
        }
        let _guard = self
            .writes
            .try_lock()
            .map_err(|_| HandoffError::new("not_ready"))?;
        let image = CheckpointStore::new(&self.state_dir).verify(cp, &self.config.computer_id)?;
        let current = self.capture_handoff_snapshot(handoff, true).await?;
        image.snapshot.verify_successor(&current)?;
        let mut state = self.state.lock().await;
        let before = state.pending.clone();
        let mut restored = before.clone();
        for (token, value) in image
            .snapshot
            .pending
            .as_object()
            .ok_or_else(|| HandoffError::new("checkpoint_mismatch"))?
        {
            let object = value
                .as_object()
                .ok_or_else(|| HandoffError::new("checkpoint_mismatch"))?;
            if uuid::Uuid::parse_str(token).is_err()
                || object.len() != 6
                || object.keys().any(|k| {
                    !matches!(
                        k.as_str(),
                        "runtime"
                            | "nativeId"
                            | "event"
                            | "command"
                            | "response"
                            | "winnerReceived"
                    )
                })
                || !value["command"].is_null()
                || !value["response"].is_null()
                || pocket_runtime_ipc::support::key(&value["nativeId"]).is_none()
            {
                return Err(HandoffError::new("checkpoint_mismatch"));
            }
            let runtime = value["runtime"]
                .as_str()
                .ok_or_else(|| HandoffError::new("checkpoint_mismatch"))?;
            let live = state
                .runtimes
                .get(runtime)
                .ok_or_else(|| HandoffError::new("checkpoint_mismatch"))?;
            let expected = image
                .snapshot
                .runtimes
                .get(runtime)
                .ok_or_else(|| HandoffError::new("checkpoint_mismatch"))?;
            if !live.rpc.online()
                || live.store != expected.store
                || live.native_process != expected.native_process
            {
                return Err(HandoffError::new("checkpoint_mismatch"));
            }
            let duplicates = restored
                .iter()
                .filter(|(_, p)| p.runtime == runtime && p.native_id == value["nativeId"])
                .map(|(key, p)| (key.clone(), p.clone()))
                .collect::<Vec<_>>();
            // Desktop add_runtime obtains a complete authenticated approvals
            // snapshot. A missing old question was answered independently.
            if live.source == "desktop" && duplicates.is_empty() {
                continue;
            }
            if duplicates.iter().any(|(_, p)| {
                p.event != value["event"] || p.command.is_some() || p.response.is_some()
            }) {
                return Err(HandoffError::new("checkpoint_mismatch"));
            }
            for (key, _) in duplicates {
                restored.remove(&key);
            }
            if restored.contains_key(token) {
                return Err(HandoffError::new("checkpoint_mismatch"));
            }
            restored.insert(
                token.clone(),
                super::Pending {
                    runtime: runtime.into(),
                    native_id: value["nativeId"].clone(),
                    event: value["event"].clone(),
                    command: None,
                    response: None,
                    winner_received: value["winnerReceived"]
                        .as_bool()
                        .ok_or_else(|| HandoffError::new("checkpoint_mismatch"))?,
                },
            );
        }
        state.pending = restored;
        if activate {
            if let Err(e) = handoff.complete_successor(cp, &current) {
                state.pending = before;
                return Err(e);
            }
            handoff.sync_remote_gate(|enabled| self.connection.set_remote_enabled(enabled));
        }
        Ok(image)
    }
    /// Startup runs this while fenced and before opening local/remote listeners.
    /// Register the same trusted Host descriptors used by native attachment.
    pub async fn upgrade_startup_snapshot(
        &self,
        handoff: &Handoff,
    ) -> std::result::Result<Snapshot, HandoffError> {
        let _guard = self
            .writes
            .try_lock()
            .map_err(|_| HandoffError::new("not_ready"))?;
        self.capture_handoff_snapshot(handoff, true).await
    }
    /// Only the current-user authenticated local allowlist may call this. The
    /// phone/native request routers must not forward upgrade methods here.
    pub async fn upgrade_local(
        &self,
        handoff: &Handoff,
        method: &str,
        params: Value,
    ) -> std::result::Result<Value, HandoffError> {
        if !crate::handoff::METHODS.contains(&method) {
            return Err(HandoffError::new("unsupported"));
        }
        if method == "upgrade/capabilities" {
            return handoff.capabilities(&params);
        }
        let params = Params::parse(&params)?;
        if method == "upgrade/cancel" {
            let result = handoff.cancel(&params);
            handoff.sync_remote_gate(|enabled| self.connection.set_remote_enabled(enabled));
            return result.and_then(|v| {
                serde_json::to_value(v).map_err(|_| HandoffError::new("unavailable"))
            });
        }
        let receipt = if method == "upgrade/quiesce" {
            let result = handoff.begin_quiesce(&params);
            handoff.sync_remote_gate(|enabled| self.connection.set_remote_enabled(enabled));
            result?
        } else if method == "upgrade/release" {
            handoff.status(&Params {
                checkpoint: None,
                ..params.clone()
            })?
        } else {
            handoff.status(&params)?
        };
        // A repeated release is bound to its original input checkpoint, while
        // status expects the final checkpoint. Let release validate that input.
        let receipt = if method == "upgrade/release"
            && matches!(receipt.phase, Phase::ReleasePending | Phase::Released)
        {
            handoff.release(&params, self.empty_handoff_snapshot().await)?
        } else if method == "upgrade/release" {
            let guard = self
                .writes
                .try_lock()
                .map_err(|_| HandoffError::new("not_ready"))?;
            let snapshot = self.capture_handoff_snapshot(handoff, true).await?;
            let result = handoff.release(&params, snapshot);
            drop(guard);
            result?
        } else if matches!(receipt.phase, Phase::Draining | Phase::Failed) {
            let guard = self.writes.try_lock().ok();
            let mut snapshot = self
                .capture_handoff_snapshot(handoff, guard.is_some())
                .await?;
            if guard.is_none() {
                snapshot.blockers.insert("unconfirmed_writes".into());
            }
            let result = handoff.progress(&params, snapshot);
            drop(guard);
            result?
        } else {
            receipt
        };
        serde_json::to_value(receipt).map_err(|_| HandoffError::new("unavailable"))
    }
    /// The caller MUST first write and flush the complete successful local
    /// upgrade/release response. This only notifies main; no process exits here.
    pub fn upgrade_release_flushed(
        &self,
        handoff: &Handoff,
        params: &Value,
    ) -> std::result::Result<(), HandoffError> {
        handoff.release_flushed(&Params::parse(params)?)
    }
    async fn empty_handoff_snapshot(&self) -> Snapshot {
        Snapshot {
            computer_id: self.config.computer_id.clone(),
            ledger: Value::Null,
            events: BTreeMap::new(),
            runtimes: BTreeMap::new(),
            pending: json!({}),
            ownership: json!([]),
            question_receipts: json!({"records":[]}),
            protected_files: BTreeMap::new(),
            blockers: BTreeSet::new(),
        }
    }
    /// Caller holds Broker.writes for a publishable checkpoint. With that lock
    /// busy, collect diagnostics only and never publish a safe checkpoint.
    async fn capture_handoff_snapshot(
        &self,
        handoff: &Handoff,
        writable: bool,
    ) -> std::result::Result<Snapshot, HandoffError> {
        let probes = {
            let state = self.state.lock().await;
            state
                .runtimes
                .iter()
                .filter_map(|(id, r)| {
                    r.rpc.managed_host_cursor().map(|c| {
                        (
                            id.clone(),
                            r.connection_id.clone(),
                            r.config_home.clone(),
                            r.native_process.clone(),
                            c,
                        )
                    })
                })
                .collect::<Vec<_>>()
        };
        let mut hosts = BTreeMap::new();
        if writable {
            for (id, connection, home, native, cursor) in probes {
                let checked = async {
                    let path = handoff
                        .host_descriptor(&id)
                        .ok_or_else(|| HandoffError::new("unavailable"))?;
                    let descriptor = pocket_runtime_ipc::load_descriptor(&path, &home)
                        .map_err(|_| HandoffError::new("unavailable"))?;
                    if descriptor.runtime_id != id
                        || descriptor.generation != cursor.generation
                        || descriptor.native_process != native
                    {
                        return Err(HandoffError::new("unavailable"));
                    }
                    let mut client = pocket_runtime_ipc::HostClient::attach(&descriptor)
                        .await
                        .map_err(|_| HandoffError::new("unavailable"))?;
                    let health = client
                        .health()
                        .await
                        .map_err(|_| HandoffError::new("unavailable"))?;
                    if !health.capabilities.survives_broker_disconnect
                        || health.host_process.is_none()
                    {
                        return Err(HandoffError::new("unavailable"));
                    }
                    Ok::<_, HandoffError>(health)
                };
                if let Ok(Ok(health)) = tokio::time::timeout(Duration::from_secs(3), checked).await
                {
                    hosts.insert(id, (connection, health));
                }
            }
        }
        let mut state = self.state.lock().await;
        let mut blockers = BTreeSet::new();
        if writable {
            if self.save_ledger(&state).is_err()
                || self.save_ownership(&mut state).is_err()
                || state.questions.save(&self.state_dir).is_err()
            {
                blockers.insert("checkpoint_failed".into());
            }
        }
        if state.ownership_persist_failed {
            blockers.insert("runtime_inventory_unverified".into());
        }
        let mut runtimes = BTreeMap::new();
        let mut events = BTreeMap::new();
        for (id, r) in &state.runtimes {
            let cursor = r.rpc.managed_host_cursor().map(|c| HostCursor {
                generation: c.generation,
                after: c.after,
            });
            let lifecycle = if cursor.is_some() {
                Lifecycle::ManagedHostExternal
            } else if r.source == "desktop" {
                Lifecycle::DesktopExternal
            } else if r.source == "cli" {
                Lifecycle::CliDaemonExternal
            } else {
                Lifecycle::Unknown
            };
            // Current native::cli is a non-owning daemon attachment. No agent
            // code creates a CLI child; a future owned adapter must advertise
            // LegacyCliAgentOwned explicitly instead of borrowing this branch.
            let health = hosts
                .get(id)
                .filter(|(connection, _)| connection == &r.connection_id)
                .map(|(_, h)| h);
            let host_process = health.and_then(|h| h.host_process.clone());
            let verified = r.native_process.as_ref().is_some_and(|expected| {
                pocket_core::native_process::NativeProcess::capture(expected.pid)
                    .is_ok_and(|actual| actual == *expected)
            });
            if !verified {
                blockers.insert("runtime_identity_unverified".into());
            }
            if lifecycle == Lifecycle::ManagedHostExternal && health.is_none() {
                blockers.insert("runtime_identity_unverified".into());
            }
            let reconciliation_required = health.is_some_and(|h| h.reconciliation_required);
            runtimes.insert(
                id.clone(),
                RuntimeIdentity {
                    runtime_id: id.clone(),
                    store: r.store.clone(),
                    connection_id: r.connection_id.clone(),
                    native_process: r.native_process.clone(),
                    lifecycle,
                    inventory_verified: r.inventory_ready && r.rpc.online(),
                    active: health.map(|h| h.active_turns > 0 || h.pending_requests > 0),
                    host_cursor: cursor.clone(),
                    host_process,
                    reconciliation_required,
                },
            );
            events.insert(id.clone(), EventArchive::capture(&r.journal, cursor));
        }
        // Disconnection is not exit evidence. Include every still-owned native
        // engine, even when its adapter vanished, so safe never ignores it.
        for (id, claim) in self.ownership_values(&state) {
            if runtimes.contains_key(&id)
                || claim
                    .native_process
                    .as_ref()
                    .is_some_and(|p| p.terminated())
            {
                continue;
            }
            if claim.advertisement.loaded_native_thread_ids.is_empty()
                && claim.native_process.is_none()
            {
                continue;
            }
            runtimes.insert(
                id.clone(),
                RuntimeIdentity {
                    runtime_id: id,
                    store: claim.advertisement.config_store_id,
                    connection_id: "offline".into(),
                    native_process: claim.native_process,
                    lifecycle: Lifecycle::Unknown,
                    inventory_verified: false,
                    active: None,
                    host_cursor: None,
                    host_process: None,
                    reconciliation_required: true,
                },
            );
        }
        let pending=Value::Object(state.pending.iter().map(|(id,p)|(id.clone(),json!({"runtime":p.runtime,"nativeId":p.native_id,"event":p.event,"command":p.command,"response":p.response,"winnerReceived":p.winner_received}))).collect());
        let ledger = serde_json::from_str(&state.ledger.snapshot_json())
            .map_err(|_| HandoffError::new("unavailable"))?;
        let ownership = serde_json::to_value(
            self.ownership_values(&state)
                .into_values()
                .collect::<Vec<_>>(),
        )
        .map_err(|_| HandoffError::new("unavailable"))?;
        let question_receipts =
            serde_json::to_value(&state.questions).map_err(|_| HandoffError::new("unavailable"))?;
        // Fixed durable paths plus a bounded physical drafts inventory. Runtime
        // files, transport health, notification indexes and locks are excluded.
        let store = CheckpointStore::new(&self.state_dir);
        let protected_files = store.capture_protected()?;
        Ok(Snapshot {
            computer_id: self.config.computer_id.clone(),
            ledger,
            events,
            runtimes,
            pending,
            ownership,
            question_receipts,
            protected_files,
            blockers,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{fixture, peer};
    use super::Broker;
    use crate::handoff::*;
    use serde_json::{Value, json};
    use uuid::Uuid;
    fn params(generation: Uuid) -> Value {
        json!({"id":Uuid::from_u128(2),"fromGeneration":generation,"checkpoint":null})
    }

    #[tokio::test]
    async fn bounded_protection_captures_identity_and_drafts_without_runtime_files() {
        let (dir, b) = fixture().await;
        for name in [
            "identity/certificate.der",
            "identity/private-key.der",
            "drafts/unsent.json",
            "drafts/nested/second.json",
            "logs/active.log",
            "managed-runtimes/volatile.json",
        ] {
            let path = dir.path().join(name);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, b"fixture bytes").unwrap();
        }
        let h = Handoff::open(dir.path(), Uuid::from_u128(1), true, false).unwrap();
        let image = b.upgrade_startup_snapshot(&h).await.unwrap();
        for name in [
            "identity/certificate.der",
            "identity/private-key.der",
            "drafts/unsent.json",
            "drafts/nested/second.json",
        ] {
            assert!(
                image.protected_files.get(name).is_some_and(|h| h.is_some()),
                "missing {name}"
            );
        }
        assert!(!image.protected_files.contains_key("logs/active.log"));
        assert!(
            !image
                .protected_files
                .contains_key("managed-runtimes/volatile.json")
        );
        assert!(image.protected_files.len() < 24);
    }

    #[tokio::test]
    async fn draft_membership_changes_invalidate_checkpoint() {
        let (dir, b) = fixture().await;
        let drafts = dir.path().join("drafts");
        std::fs::create_dir(&drafts).unwrap();
        std::fs::write(drafts.join("one.json"), b"one").unwrap();
        let h = Handoff::open(dir.path(), Uuid::from_u128(1), true, false).unwrap();
        let snapshot = b.upgrade_startup_snapshot(&h).await.unwrap();
        let store = CheckpointStore::new(dir.path());
        let cp = store.save(Uuid::from_u128(1), snapshot).unwrap();
        std::fs::write(drafts.join("added.json"), b"two").unwrap();
        assert!(store.verify(&cp, &b.config.computer_id).is_err());
    }

    #[tokio::test]
    async fn excessive_draft_depth_and_count_fail_closed() {
        let (dir, b) = fixture().await;
        let deep = dir.path().join("drafts/a/b/c/d/e");
        std::fs::create_dir_all(&deep).unwrap();
        std::fs::write(deep.join("draft.json"), b"too deep").unwrap();
        let h = Handoff::open(dir.path(), Uuid::from_u128(1), true, false).unwrap();
        assert!(b.upgrade_startup_snapshot(&h).await.is_err());
        let other = tempfile::tempdir().unwrap();
        let (other_b, _) = Broker::open(b.config.clone(), other.path().into()).unwrap();
        std::fs::create_dir(other.path().join("drafts")).unwrap();
        for n in 0..257 {
            std::fs::write(other.path().join(format!("drafts/{n}.json")), b"bounded").unwrap();
        }
        let h = Handoff::open(other.path(), Uuid::from_u128(1), true, false).unwrap();
        assert!(other_b.upgrade_startup_snapshot(&h).await.is_err());
    }

    #[tokio::test]
    async fn local_protocol_reaches_ready_persists_ledger_and_flushes_shutdown() {
        let (dir, b) = fixture().await;
        let generation = Uuid::from_u128(1);
        let h = Handoff::open(dir.path(), generation, true, true).unwrap();
        let p = params(generation);
        let r = b
            .upgrade_local(&h, "upgrade/quiesce", p.clone())
            .await
            .unwrap();
        assert_eq!(r["phase"], "ready");
        assert_eq!(r["safeToExit"], true);
        assert!(!b.connection.remote_enabled());
        let mut release = p.clone();
        release["checkpoint"] = r["checkpoint"].clone();
        let released = b
            .upgrade_local(&h, "upgrade/release", release.clone())
            .await
            .unwrap();
        assert_eq!(released["phase"], "release_pending");
        assert!(!*h.shutdown_receiver().borrow());
        assert_eq!(
            b.upgrade_local(&h, "upgrade/release", release.clone())
                .await
                .unwrap(),
            released
        );
        b.upgrade_release_flushed(&h, &release).unwrap();
        assert!(*h.shutdown_receiver().borrow());
        let cp: CheckpointRef = serde_json::from_value(released["checkpoint"].clone()).unwrap();
        let image = CheckpointStore::new(dir.path())
            .verify(&cp, &b.config.computer_id)
            .unwrap();
        let disk: Value =
            serde_json::from_slice(&std::fs::read(dir.path().join("commands.json")).unwrap())
                .unwrap();
        assert_eq!(image.snapshot.ledger, disk);
    }

    #[tokio::test]
    async fn status_advances_admitted_write_and_cancel_restores_connection() {
        let (dir, b) = fixture().await;
        let generation = Uuid::from_u128(1);
        let h = Handoff::open(dir.path(), generation, true, true).unwrap();
        let p = params(generation);
        let guard = h.admit_write().unwrap();
        let r = b
            .upgrade_local(&h, "upgrade/quiesce", p.clone())
            .await
            .unwrap();
        assert_eq!(r["phase"], "draining");
        guard.confirm().unwrap();
        let r = b
            .upgrade_local(&h, "upgrade/status", p.clone())
            .await
            .unwrap();
        assert_eq!(r["phase"], "ready");
        b.upgrade_local(&h, "upgrade/cancel", p.clone())
            .await
            .unwrap();
        assert!(b.connection.remote_enabled());
        assert!(b.upgrade_local(&h, "upgrade/quiesce", p).await.is_err());
        assert_eq!(
            b.upgrade_local(&h, "upgrade/force", json!({}))
                .await
                .unwrap_err()
                .code,
            "unsupported"
        );
    }

    #[tokio::test]
    async fn passive_notification_index_changes_preserve_active_handoff() {
        let (dir, b) = fixture().await;
        let generation = Uuid::from_u128(1);
        let h = Handoff::open(dir.path(), generation, true, true).unwrap();
        let p = params(generation);
        let path = dir.path().join("push-routes.json");
        std::fs::write(&path, b"before event").unwrap();
        let ready = b
            .upgrade_local(&h, "upgrade/quiesce", p.clone())
            .await
            .unwrap();
        std::fs::write(&path, b"after native event").unwrap();
        let mut release = p;
        release["checkpoint"] = ready["checkpoint"].clone();
        let r = b
            .upgrade_local(&h, "upgrade/release", release)
            .await
            .unwrap();
        assert_eq!(r["safeToExit"], true);
        assert_eq!(std::fs::read(&path).unwrap(), b"after native event");
    }

    #[tokio::test]
    async fn unknown_runtime_identity_never_claims_native_tasks_preserved() {
        let (dir, b) = fixture().await;
        let generation = Uuid::from_u128(1);
        let h = Handoff::open(dir.path(), generation, true, true).unwrap();
        let mut native = peer(&b, "r", "cli").await;
        let r = b
            .upgrade_local(&h, "upgrade/quiesce", params(generation))
            .await
            .unwrap();
        assert_eq!(r["safeToExit"], false);
        assert_eq!(r["nativeTasksPreserved"], false);
        native.assert_idle().await; // Capture never sends a native write or model call.
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn startup_helper_preserves_pending_tokens_archives_and_rollback_data() {
        let (dir, b) = fixture().await;
        b.save_devices(&*b.state.lock().await).unwrap();
        let mut original = peer(&b, "r", "cli").await;
        let process =
            pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
        let event = json!({"id":9,"method":"item/fileChange/requestApproval","params":{"threadId":"t","turnId":"u","itemId":"i"}});
        let token = Uuid::from_u128(17).to_string();
        {
            let mut state = b.state.lock().await;
            let runtime = state.runtimes.get_mut("r").unwrap();
            runtime.native_process = Some(process);
            runtime.inventory_ready = true;
            runtime
                .journal
                .append(pocket_core::journal::NativeEvent {
                    native_thread_id: Some("t".into()),
                    native_turn_id: Some("u".into()),
                    native_item_id: Some("i".into()),
                    payload: event.clone(),
                })
                .unwrap();
            state.pending.insert(
                token.clone(),
                super::super::Pending {
                    runtime: "r".into(),
                    native_id: json!(9),
                    event,
                    command: None,
                    response: None,
                    winner_received: false,
                },
            );
        }
        let h = Handoff::open(dir.path(), Uuid::from_u128(1), true, true).unwrap();
        let p = params(Uuid::from_u128(1));
        let ready = b
            .upgrade_local(&h, "upgrade/quiesce", p.clone())
            .await
            .unwrap();
        assert_eq!(ready["safeToExit"], true);
        let mut release = p;
        release["checkpoint"] = ready["checkpoint"].clone();
        let released = b
            .upgrade_local(&h, "upgrade/release", release.clone())
            .await
            .unwrap();
        b.upgrade_release_flushed(&h, &release).unwrap();
        let cp: CheckpointRef = serde_json::from_value(released["checkpoint"].clone()).unwrap();
        let (new, rx) = super::super::Broker::open(b.config.clone(), dir.path().into()).unwrap();
        tokio::spawn(new.clone().receive_events(rx));
        let mut native = peer(&new, "r", "cli").await;
        new.state
            .lock()
            .await
            .runtimes
            .get_mut("r")
            .unwrap()
            .inventory_ready = true;
        let boot = Handoff::open_fenced(dir.path(), Uuid::from_u128(6), true, true, &cp).unwrap();
        let image = new.verify_handoff_successor(&boot, &cp).await.unwrap();
        assert!(!boot.accepting_writes());
        assert!(new.state.lock().await.pending.contains_key(&token));
        new.activate_handoff_successor(&boot, &cp).await.unwrap();
        assert!(boot.accepting_writes());
        let mut from = image.snapshot.events["r"].cursor.clone();
        from.sequence = 0;
        assert!(
            matches!(boot.replay_recovery("r",&from),pocket_core::journal::Replay::Events(events) if events.len()==1)
        );
        from.sequence = 1;
        assert_eq!(
            boot.replay_recovery("r", &from),
            pocket_core::journal::Replay::SnapshotRequired
        );
        let rollback =
            Handoff::open_fenced(dir.path(), Uuid::from_u128(7), true, true, &cp).unwrap();
        new.verify_handoff_successor(&rollback, &cp).await.unwrap();
        new.activate_handoff_successor(&rollback, &cp)
            .await
            .unwrap();
        assert!(new.state.lock().await.pending.contains_key(&token));
        native.assert_idle().await;
        original.assert_idle().await;
    }
}
