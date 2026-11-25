//! Durable completion evidence for an already accepted product command.
//! Reading this table never executes a command or changes native state.
use super::*;

pub(super) fn initialize(db: &Connection) -> Result<()> {
    db.execute_batch(
        "CREATE TABLE IF NOT EXISTS product_command_completions_v1(
        caller_id TEXT NOT NULL, command_id TEXT NOT NULL, fingerprint BLOB NOT NULL,
        result TEXT NOT NULL, PRIMARY KEY(caller_id,command_id))",
    )?;
    Ok(())
}

pub(super) fn record(db: &Connection, request: &Request, receipt: &Value) -> Result<()> {
    let Some(caller) = request.caller.as_deref() else {
        return Ok(());
    };
    let mut wire_parameters=request.parameters.clone();
    if let Some(object)=wire_parameters.as_object_mut() {object.remove("retainedAttachments");}
    let fingerprint = pocket_core::ledger::CommandLedger::fingerprint(&serde_json::to_vec(
        &json!({"method":request.method,"params":wire_parameters}),
    )?);
    let mut result = json!({
        "accepted":true,"commandId":request.id,"result":receipt
    });
    if let Some(conversation)=receipt.get("conversation") {result["conversation"]=conversation.clone();}
    let result=serde_json::to_string(&result)?;
    ensure!(result.len() <= 65536, "Product command receipt too large");
    db.execute(
        "INSERT OR IGNORE INTO product_command_completions_v1(caller_id,command_id,fingerprint,result) VALUES(?1,?2,?3,?4)",
        params![caller, request.id, fingerprint.as_slice(), result],
    )?;
    let saved: (Vec<u8>, String) = db.query_row(
        "SELECT fingerprint,result FROM product_command_completions_v1 WHERE caller_id=?1 AND command_id=?2",
        params![caller,request.id],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    ensure!(
        saved.0 == fingerprint && saved.1 == result,
        "Product command receipt conflict"
    );
    Ok(())
}

pub(crate) fn read_completed_command(
    root: &Path,
    caller: &str,
    command: &str,
) -> Result<Option<([u8; 32], Value)>> {
    let path = root.join("messages.db");
    if !path.try_exists()? {
        return Ok(None);
    }
    let file = std::fs::File::open(&path)?;
    crate::storage::verify_physical_file(&path, &file)?;
    let db = Connection::open_with_flags(&path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    db.busy_timeout(Duration::from_millis(50))?;
    db.execute_batch("PRAGMA query_only=ON")?;
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='product_command_completions_v1')",
        [], |r| r.get(0),
    )?;
    if !exists {
        return Ok(None);
    }
    let row: Option<(Vec<u8>,String)> = db.query_row(
        "SELECT fingerprint,result FROM product_command_completions_v1 WHERE caller_id=?1 AND command_id=?2 AND length(result)<=65536",
        params![caller,command], |r| Ok((r.get(0)?, r.get(1)?)),
    ).optional()?;
    let Some((fingerprint, raw)) = row else {
        return Ok(None);
    };
    let fingerprint: [u8; 32] = fingerprint
        .try_into()
        .map_err(|_| anyhow::anyhow!("Invalid command fingerprint"))?;
    let value: Value = serde_json::from_str(&raw)?;
    ensure!(
        value["accepted"] == true && value["commandId"] == command && value.get("result").is_some(),
        "Invalid product command receipt"
    );
    Ok(Some((fingerprint, value)))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn assistant_attachment_completion_keeps_original_wire_fingerprint_for_lost_ack_recovery() {
        let dir=tempfile::tempdir().unwrap();let db=Connection::open(dir.path().join("messages.db")).unwrap();initialize(&db).unwrap();
        let wire=json!({"message":"inspect","attachments":[{"type":"pocketImage","attachmentId":uuid::Uuid::new_v4(),"name":"a.png"}]});
        let mut enriched=wire.clone();enriched["retainedAttachments"]=json!([{"type":"image","path":"private fixture path"}]);
        let (reply,_)=oneshot::channel();let r=Request {method:"assistant/send".into(),parameters:enriched,id:"input-send".into(),caller:Some("phone".into()),directory:None,reply};
        record(&db,&r,&Value::Null).unwrap();
        let (actual,_)=read_completed_command(dir.path(),"phone","input-send").unwrap().unwrap();
        let expected=pocket_core::ledger::CommandLedger::fingerprint(&serde_json::to_vec(&json!({"method":"assistant/send","params":wire})).unwrap());
        assert_eq!(actual,expected);
    }

    #[tokio::test]
    async fn command_receipt_requires_trusted_caller_and_successful_apply() {
        let dir = tempfile::tempdir().unwrap();
        let actor = Actor::start_with_io(
            dir.path().into(),
            "computer".into(),
            "profile".into(),
            crate::assistant_product::AssistantTestIo::default(),
            None,
            Arc::new(|| 1_000_000),
        )
        .await
        .unwrap();
        actor
            .request(
                "assistant/memory/add",
                json!({"text":"local memory", "caller":"phone"}),
                "internal".into(),
                None,
            )
            .await
            .unwrap();
        assert!(
            read_completed_command(dir.path(), "phone", "internal")
                .unwrap()
                .is_none()
        );
        assert!(
            actor
                .request_from_device(
                    "assistant/memory/add",
                    json!({"text":""}),
                    "failed".into(),
                    None,
                    "phone".into()
                )
                .await
                .is_err()
        );
        assert!(
            read_completed_command(dir.path(), "phone", "failed")
                .unwrap()
                .is_none()
        );
        assert!(
            actor
                .request_from_device(
                    "assistant/memory/add",
                    json!({"text":"invalid caller"}),
                    "missing".into(),
                    None,
                    String::new()
                )
                .await
                .is_err()
        );
        assert!(
            read_completed_command(dir.path(), "", "missing")
                .unwrap()
                .is_none()
        );
        drop(actor);
    }

    #[tokio::test]
    async fn command_receipt_survives_dropped_waiter_and_preserves_exact_body() {
        let dir = tempfile::tempdir().unwrap();
        let actor = Actor::start_with_io(
            dir.path().into(),
            "computer".into(),
            "profile".into(),
            crate::assistant_product::AssistantTestIo::default(),
            None,
            Arc::new(|| 1_000_000),
        )
        .await
        .unwrap();
        let command = "late-completion";
        let parameters =
            json!({"runtimeId":"runtime","commandId":command,"text":"temporary receipt test"});
        let (reply, receiver) = oneshot::channel();
        actor
            .sender
            .try_send(Request {
                method: "assistant/memory/add".into(),
                parameters: parameters.clone(),
                id: command.into(),
                caller: Some("phone".into()),
                directory: None,
                reply,
            })
            .unwrap();
        drop(receiver); // Simulate a mobile caller whose wait ended before apply finished.
        let (fingerprint, value) = tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if let Some(saved) = read_completed_command(dir.path(), "phone", command).unwrap() {
                    break saved;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("Accepted command must retain completion after the receiver disappears");
        let expected = pocket_core::ledger::CommandLedger::fingerprint(
            &serde_json::to_vec(&json!({"method":"assistant/memory/add","params":parameters}))
                .unwrap(),
        );
        assert_eq!(fingerprint, expected);
        assert_eq!(value["accepted"], true);
        assert_eq!(value["commandId"], command);
        assert!(
            read_completed_command(dir.path(), "phone", "old-unknown")
                .unwrap()
                .is_none()
        );
        let db = Connection::open(dir.path().join("assistant.db")).unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM assistant_memories", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            1
        );
        drop(actor);
        assert_eq!(
            read_completed_command(dir.path(), "phone", command)
                .unwrap()
                .unwrap()
                .0,
            expected
        );
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM assistant_memories", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            1
        );
    }

    #[test]
    fn command_receipt_legacy_and_different_body_stay_unconfirmed() {
        let dir = tempfile::tempdir().unwrap();
        let db = Connection::open(dir.path().join("messages.db")).unwrap();
        assert!(
            read_completed_command(dir.path(), "phone", "legacy")
                .unwrap()
                .is_none()
        );
        initialize(&db).unwrap();
        let (reply, _) = oneshot::channel();
        let mut request = Request {
            method: "assistant/memory/add".into(),
            parameters: json!({"text":"first"}),
            id: "same-id".into(),
            caller: Some("phone".into()),
            directory: None,
            reply,
        };
        record(&db, &request, &json!({"accepted":true})).unwrap();
        let old = read_completed_command(dir.path(), "phone", "same-id")
            .unwrap()
            .unwrap();
        request.parameters = json!({"text":"different"});
        assert!(record(&db, &request, &json!({"accepted":true})).is_err());
        assert_eq!(
            read_completed_command(dir.path(), "phone", "same-id")
                .unwrap()
                .unwrap(),
            old
        );
    }
}
