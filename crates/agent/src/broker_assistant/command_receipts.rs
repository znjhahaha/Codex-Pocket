//! Reconcile only durable product completion, without issuing an Actor command.
use super::*;

impl Broker {
    pub(in crate::broker) async fn reconcile_assistant_command(
        &self,
        key: &CommandKey,
    ) -> Result<()> {
        if !matches!(
            self.state.lock().await.ledger.status(key),
            Some(Status::Unknown)
        ) {
            return Ok(());
        }
        let root = self.state_dir.join("assistant");
        let command = key.command_id.clone();
        let caller = key.caller_id.clone();
        let read = tokio::task::spawn_blocking(move || {
            crate::assistant_product::read_completed_command(&root, &caller, &command)
        });
        let Ok(Ok(Ok(Some((fingerprint, value))))) =
            tokio::time::timeout(Duration::from_millis(250), read).await
        else {
            // Missing, old, locked or corrupt evidence leaves the original fence.
            return Ok(());
        };
        if !self.device_exists(&key.caller_id).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let mut state = self.state.lock().await;
        if !state.devices.devices.contains_key(&key.caller_id) {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        if matches!(state.ledger.status(key), Some(Status::Unknown))
            && state.ledger.matches_fingerprint(key, &fingerprint)
        {
            state
                .ledger
                .finish(
                    key,
                    Status::Accepted {
                        native_result: value,
                    },
                    storage::now(),
                )
                .map_err(|_| {
                    classify(
                        anyhow::anyhow!("Command reconciliation unavailable"),
                        Code::Unknown,
                    )
                })?;
            self.save_ledger(&state)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn assistant_completed_command_status_reconciles_exact_body_without_execution() {
        let (_dir, broker) = crate::broker::tests::fixture().await;
        broker
            .state
            .lock()
            .await
            .devices
            .add(
                "second".into(),
                "Second".into(),
                "other-secret",
                storage::now(),
            )
            .unwrap();
        let root = broker.state_dir.join("assistant");
        let actor = Actor::start_with_io(
            root.clone(),
            "computer".into(),
            "profile".into(),
            crate::assistant_product::AssistantTestIo::default(),
            None,
            Arc::new(|| 1_000_000),
        )
        .await
        .unwrap();
        for (id, matches) in [("same-body", true), ("different-body", false)] {
            let p = json!({"runtimeId":"runtime","commandId":id,"text":"receipt test"});
            actor
                .request_from_device(
                    "assistant/memory/add",
                    p.clone(),
                    id.into(),
                    None,
                    "phone".into(),
                )
                .await
                .unwrap();
            let mut original = p;
            if matches {
                let other = CommandKey {
                    caller_id: "second".into(),
                    command_id: id.into(),
                };
                let mut state = broker.state.lock().await;
                state
                    .ledger
                    .begin(
                        other.clone(),
                        &serde_json::to_vec(
                            &json!({"method":"assistant/memory/add","params":original}),
                        )
                        .unwrap(),
                        storage::now(),
                    )
                    .unwrap();
                state
                    .ledger
                    .finish(&other, Status::Unknown, storage::now())
                    .unwrap();
                broker.save_ledger(&state).unwrap();
                drop(state);
                let unrelated = broker
                    .request("second", "commands/status", json!({"commandId":id}))
                    .await
                    .unwrap();
                assert_eq!(
                    unrelated["status"], "unknown",
                    "Another paired caller cannot borrow this command completion"
                );
            }
            if !matches {
                original["text"] = json!("another request");
            }
            let key = CommandKey {
                caller_id: "phone".into(),
                command_id: id.into(),
            };
            {
                let mut state = broker.state.lock().await;
                state
                    .ledger
                    .begin(
                        key.clone(),
                        &serde_json::to_vec(
                            &json!({"method":"assistant/memory/add","params":original}),
                        )
                        .unwrap(),
                        storage::now(),
                    )
                    .unwrap();
                state
                    .ledger
                    .finish(&key, Status::Unknown, storage::now())
                    .unwrap();
                broker.save_ledger(&state).unwrap();
            }
            let read = broker
                .request("phone", "commands/status", json!({"commandId":id}))
                .await
                .unwrap();
            assert_eq!(read["status"], if matches { "accepted" } else { "unknown" });
            let state = broker.state.lock().await;
            assert_eq!(
                matches!(state.ledger.status(&key), Some(Status::Accepted { .. })),
                matches
            );
        }
        assert!(
            broker
                .request(
                    "unpaired",
                    "commands/status",
                    json!({"commandId":"same-body"})
                )
                .await
                .is_err()
        );
        let db = rusqlite::Connection::open(root.join("assistant.db")).unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM assistant_memories", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            2
        );
        // Repeated reads do not invoke the Actor or add another memory.
        broker
            .request("phone", "commands/status", json!({"commandId":"same-body"}))
            .await
            .unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM assistant_memories", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            2
        );
        drop(actor);
    }
}
