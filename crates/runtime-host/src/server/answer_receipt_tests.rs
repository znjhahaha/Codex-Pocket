//! Real authenticated HostClient/connection/actor; native I/O is an in-memory pipe.
use super::*;
use crate::client::HostClient;
use std::sync::Mutex;

#[derive(Clone, Copy)]
enum Mode {
    Normal,
    LoseAnswerReply,
    Legacy,
    ColdSchema2,
    ColdUnknown,
}

struct Fixture {
    _root: tempfile::TempDir,
    descriptor: Descriptor,
    native: BufReader<tokio::io::DuplexStream>,
    incoming: mpsc::Sender<Event>,
    actor: tokio::task::JoinHandle<Store>,
    router: tokio::task::JoinHandle<()>,
    methods: Arc<Mutex<Vec<String>>>,
}

impl Fixture {
    async fn new(mode: Mode) -> Self {
        let root = tempfile::tempdir().unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let process =
            pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
        let descriptor = Descriptor {
            protocol: PROTOCOL,
            runtime_id: "answer-fixture".into(),
            generation: uuid::Uuid::new_v4().to_string(),
            pid: process.pid,
            native_pid: process.pid,
            host_process: Some(process.clone()),
            native_process: Some(process),
            port: listener.local_addr().unwrap().port(),
            token: "a".repeat(64),
            config_home: root.path().into(),
            source: "cli".into(),
        };
        if matches!(mode,Mode::ColdSchema2 | Mode::ColdUnknown) {
            let old=uuid::Uuid::new_v4().to_string();
            let mut seed=Store::open(&root.path().join("recovery.json"),&descriptor.runtime_id,&old).unwrap();
            for request_id in [json!(0),json!("0"),json!(-1),json!(u64::MAX),json!("s:0")] {
                let id=key(&request_id).unwrap();
                seed.state.reserve(id.clone(),&json!({"id":request_id,"result":"private-answer-sentinel"}),true).unwrap();
                if matches!(mode,Mode::ColdSchema2) {seed.state.complete(&id,json!({"id":request_id,"result":{"written":true}}),true);}
            }
            seed.save().unwrap();
        }
        let mut store = Store::open(
            &root.path().join("recovery.json"),
            &descriptor.runtime_id,
            &descriptor.generation,
        )
        .unwrap();
        if matches!(mode,Mode::ColdSchema2 | Mode::ColdUnknown) {store.begin_generation(&descriptor.generation).unwrap();}
        let (incoming, receiver) = mpsc::channel(32);
        let (routed, mut route) = mpsc::channel(32);
        let target = incoming.clone();
        let methods = Arc::new(Mutex::new(Vec::new()));
        let seen = methods.clone();
        let router = tokio::spawn(async move {
            while let Some(event) = route.recv().await {
                let event = match event {
                    Event::Client(value, reply) => {
                        let method = value["method"].as_str().unwrap_or("").to_owned();
                        seen.lock().unwrap().push(method.clone());
                        if matches!(mode, Mode::Legacy) && matches!(method.as_str(),"host/answer/receipt"|"host/answer/evidence") {
                            deliver(&reply, fail(value["id"].clone(), "unsupported"));
                            continue;
                        }
                        if matches!(mode, Mode::LoseAnswerReply) && method == "host/respond" {
                            // Keep the channel open until the real actor sends its durable reply,
                            // then discard it to simulate loss after the native write.
                            let (lost, mut response) = mpsc::channel(1);
                            target.send(Event::Client(value, lost)).await.unwrap();
                            response.recv().await.unwrap();
                            continue;
                        }
                        Event::Client(value, reply)
                    }
                    event => event,
                };
                if target.send(event).await.is_err() {
                    break;
                }
            }
        });
        let d = descriptor.clone();
        let (mut writer, native) = tokio::io::duplex(16384);
        let actor = tokio::spawn(async move {
            let (broadcast, _) = broadcast::channel(32);
            let mut clients = tokio::task::JoinSet::new();
            actor_loop(
                listener,
                Arc::new(d.token.clone()),
                &d,
                json!({}),
                &mut writer,
                &mut store,
                routed,
                receiver,
                broadcast,
                &mut clients,
            )
            .await
            .unwrap();
            store
        });
        Self {
            _root: root,
            descriptor,
            native: BufReader::new(native),
            incoming,
            actor,
            router,
            methods,
        }
    }
    async fn client(&self) -> HostClient {
        HostClient::attach(&self.descriptor).await.unwrap()
    }
    async fn emit(&self, value: Value) {
        self.incoming.send(Event::Native(value)).await.unwrap();
    }
    async fn stop(self) -> Store {
        self.incoming.send(Event::NativeClosed).await.unwrap();
        let store = self.actor.await.unwrap();
        self.router.abort();
        store
    }
    async fn raw(&self, method: &str, params: Value) -> Value {
        let socket = TcpStream::connect(("127.0.0.1", self.descriptor.port))
            .await
            .unwrap();
        let (read, mut write) = socket.into_split();
        let mut read = BufReader::new(read);
        write_frame(
            &mut write,
            &json!({"token":self.descriptor.token,"generation":self.descriptor.generation}),
        )
        .await
        .unwrap();
        write_frame(
            &mut write,
            &json!({"id":"fixture-query","method":method,"params":params}),
        )
        .await
        .unwrap();
        loop {
            let value = read_frame(&mut read).await.unwrap().unwrap();
            if value["id"] == "fixture-query" {
                return value;
            }
        }
    }
}

fn pending(id: Value) -> Value {
    json!({"id":id,"method":"item/tool/call","params":{
        "threadId":"thread","turnId":"turn","callId":"call","tool":"assistant_create_task","arguments":{}}})
}

#[tokio::test]
async fn answer_reused_id_answers_current_pending_and_retains_old_completed_or_unknown_evidence() {
    for mode in [Mode::ColdSchema2, Mode::ColdUnknown] {
        let mut f = Fixture::new(mode).await;
        let mut client = f.client().await;
        let snapshot = client.recovery().await.unwrap();
        let old = client.answer_evidence(0, snapshot["evidenceDigest"].as_str().unwrap()).await.unwrap();
        let old_generation = old.records[0].generation.clone();
        // A current-generation receipt query never substitutes an old answer.
        assert_eq!(client.answer_receipt(json!(0)).await.unwrap().state, "missing");
        let stale = f.raw("host/respond", json!({"generation":old_generation,
            "requestId":0,"response":{"result":{"decision":"accept"}}})).await;
        assert_eq!(stale["error"]["code"], "generation_mismatch");
        let absent = f.raw("host/respond", json!({"generation":f.descriptor.generation,
            "requestId":0,"response":{"result":{"decision":"accept"}}})).await;
        assert_eq!(absent["error"]["code"], "stale_request");
        for id in [json!(0), json!("0")] {
            f.emit(pending(id.clone())).await;
            let response = json!({"result":{"decision":"accept"}});
            let receipt = client.respond(id.clone(), response.clone()).await.unwrap();
            assert_eq!(receipt.state, "completed");
            assert_eq!(receipt.generation, f.descriptor.generation);
            assert_eq!(read_frame(&mut f.native).await.unwrap().unwrap(), json!({"id":id,"result":{"decision":"accept"}}));
            assert_eq!(client.respond(id.clone(), response).await.unwrap().state, "completed");
            assert_eq!(client.answer_receipt(id).await.unwrap().state, "completed");
        }
        let snapshot = client.recovery().await.unwrap();
        let page = client.answer_evidence(0, snapshot["evidenceDigest"].as_str().unwrap()).await.unwrap();
        assert_eq!(page.total, 7);
        for prior in &old.records {
            assert!(page.records.iter().any(|r| r.generation == prior.generation
                && r.request_id == prior.request_id && r.completed == prior.completed));
        }
        assert!(tokio::time::timeout(Duration::from_millis(30), read_frame(&mut f.native)).await.is_err(),
            "duplicate receipts and historical queries never replay an answer");
        let retained = tempfile::tempdir().unwrap();
        let path = retained.path().join("recovery.json");
        std::fs::copy(f._root.path().join("recovery.json"), &path).unwrap();
        f.stop().await;
        let next = uuid::Uuid::new_v4().to_string();
        let mut cold = Store::open(&path, "answer-fixture", &next).unwrap();
        cold.begin_generation(&next).unwrap();
        let page = cold.state.answer_evidence(&next, 0, &cold.state.evidence_digest().unwrap()).unwrap();
        assert_eq!(page.total, 7, "both generations survive a cold load");
        cold.observe_native(pending(json!(0))).unwrap();
        let (mut writer, native) = tokio::io::duplex(1024);
        drop(native);
        let (reply, mut replies) = mpsc::channel(2);
        assert!(answer(&mut writer, &mut cold, json!(0), json!({"result":{}}), &reply, Some(json!("outer"))).await.is_err());
        assert!(replies.try_recv().is_err());
        let restored = Store::open(&path, "answer-fixture", &next).unwrap();
        assert_eq!(restored.state.current_answer_receipt("n:0").state, "unknown");
        let page = restored.state.answer_evidence(&next, 0, &restored.state.evidence_digest().unwrap()).unwrap();
        assert_eq!(page.total, 8, "a third generation retains both earlier ID-zero receipts on write failure");
        assert_eq!(page.records.iter().filter(|r| r.request_id == json!(0)).count(), 3);
        answer(&mut writer, &mut cold, json!(0), json!({"result":{}}), &reply, Some(json!("duplicate"))).await.unwrap();
        assert_eq!(replies.recv().await.unwrap().value["result"]["state"], "unknown");
    }
}

#[tokio::test]
async fn answer_evidence_wire_retains_five_cold_schema2_typed_ids_without_payload() {
    let f=Fixture::new(Mode::ColdSchema2).await;
    let mut client=f.client().await;
    let recovery=client.recovery().await.unwrap();
    let digest=recovery["evidenceDigest"].as_str().unwrap();
    let page=client.answer_evidence(0,digest).await.unwrap();
    assert_eq!(page.total,5);assert_eq!(page.records.len(),5);assert!(page.next_offset.is_none());
    assert!(page.records.iter().all(|r|r.completed && r.generation!=f.descriptor.generation));
    assert!(page.records.iter().any(|r|r.request_id==json!(0)));
    assert!(page.records.iter().any(|r|r.request_id==json!("0")));
    let wire=serde_json::to_string(&page).unwrap();
    assert!(!wire.contains("private-answer-sentinel") && !wire.contains("response") && !wire.contains("result"));
    assert_eq!(client.recovery().await.unwrap(),recovery,"Evidence reads cannot mutate history");
    let store=f.stop().await;
    assert_eq!(store.state.answers.len(),5);
}

#[tokio::test]
async fn answer_evidence_wire_current_answer_and_snapshot_race_have_no_native_replay() {
    let mut f=Fixture::new(Mode::Normal).await;
    f.emit(pending(json!(0))).await;
    let mut client=f.client().await;
    client.respond(json!(0),json!({"result":{"choice":"private-answer-sentinel"}})).await.unwrap();
    read_frame(&mut f.native).await.unwrap().unwrap();
    let recovery=client.recovery().await.unwrap();
    let digest=recovery["evidenceDigest"].as_str().unwrap();
    let page=client.answer_evidence(0,digest).await.unwrap();
    assert!(page.records[0].completed);
    assert!(!serde_json::to_string(&page).unwrap().contains("private-answer-sentinel"));
    f.emit(pending(json!(1))).await;
    assert!(client.answer_evidence(0,digest).await.is_err(),"A changed persisted inventory invalidates the snapshot");
    let store=f.stop().await;
    assert_eq!(store.state.answers.len(),1);assert_eq!(store.state.pending.len(),1);
}

#[tokio::test]
async fn answer_evidence_wire_legacy_and_invalid_parameters_fail_closed() {
    let f=Fixture::new(Mode::Legacy).await;
    let mut client=f.client().await;
    let digest=client.recovery().await.unwrap()["evidenceDigest"].as_str().unwrap().to_owned();
    assert!(client.answer_evidence(0,&digest).await.is_err());
    assert!(f.stop().await.state.answers.is_empty());
    let f=Fixture::new(Mode::Normal).await;
    let digest=f.raw("host/recovery",json!({})).await["result"]["evidenceDigest"].as_str().unwrap().to_owned();
    for fault in ["generation","digest","offset","extra"] {
        let mut params=json!({"generation":f.descriptor.generation,"offset":0,"snapshotDigest":digest});
        match fault {
            "generation"=>params["generation"]=json!("foreign"),
            "digest"=>params["snapshotDigest"]=json!("b".repeat(64)),
            "offset"=>params["offset"]=json!(-1),
            "extra"=>params["answer"]=json!("cannot-send-this"),
            _=>unreachable!(),
        }
        assert!(f.raw("host/answer/evidence",params).await.get("error").is_some(),"accepted {fault}");
    }
    assert!(f.stop().await.state.answers.is_empty());
}

#[tokio::test]
async fn answer_receipt_wire_reads_answer_zero_not_command_namespace() {
    let mut f = Fixture::new(Mode::Normal).await;
    f.emit(pending(json!(0))).await;
    let mut client = f.client().await;
    assert_eq!(
        client
            .respond(json!(0), json!({"result":{"success":false}}))
            .await
            .unwrap()
            .state,
        "completed"
    );
    assert_eq!(read_frame(&mut f.native).await.unwrap().unwrap()["id"], 0);
    assert_eq!(client.receipt(json!(0)).await.unwrap().state, "missing");
    let result = f
        .raw(
            "host/answer/receipt",
            json!({"generation":f.descriptor.generation,"requestId":0}),
        )
        .await;
    assert_eq!(result["result"]["namespace"], "answer", "{result}");
    assert_eq!(result["result"]["requestId"], 0);
    assert_eq!(result["result"]["state"], "completed");
    assert_eq!(
        result["result"]["response"],
        json!({"id":0,"result":{"written":true}})
    );
    assert_eq!(
        client
            .answer_receipt(json!(0))
            .await
            .unwrap()
            .response
            .unwrap()["result"]["written"],
        true
    );
    assert_eq!(f.stop().await.state.answers.len(), 1);
}

#[tokio::test]
async fn answer_receipt_legacy_double_snapshot_cannot_prove_not_dispatched() {
    let mut f = Fixture::new(Mode::Normal).await;
    let request = pending(json!(1));
    f.emit(request.clone()).await;
    let mut client = f.client().await;
    client
        .respond(json!(1), json!({"result":{"success":false}}))
        .await
        .unwrap();
    read_frame(&mut f.native).await.unwrap().unwrap();
    // push permits an identical native request to reappear after answer completion.
    f.emit(request.clone()).await;
    let first = client.recovery().await.unwrap();
    let second = client.recovery().await.unwrap();
    assert_eq!(first, second);
    assert_eq!(first["generation"], f.descriptor.generation);
    assert_eq!(
        first["pendingRequests"],
        json!([{"generation":f.descriptor.generation,"request":request}])
    );
    assert_eq!(first["unconfirmedWrites"], 0);
    assert_eq!(first["unknownOperations"], 0);
    assert_eq!(first["answerCount"], 1);
    assert_eq!(client.receipt(json!(1)).await.unwrap().state, "missing");
    let store = f.stop().await;
    assert_eq!(store.state.receipt("n:1", true).state, "completed");
}

#[tokio::test]
async fn answer_receipt_timeout_after_write_is_read_without_repeating_answer() {
    let mut f = Fixture::new(Mode::LoseAnswerReply).await;
    f.emit(pending(json!(0))).await;
    let mut client = f.client().await;
    let lost = tokio::time::timeout(
        Duration::from_millis(500),
        client.respond(json!(0), json!({"result":{"success":false}})),
    )
    .await;
    assert!(
        lost.is_err(),
        "The caller must not receive the written reply"
    );
    assert_eq!(read_frame(&mut f.native).await.unwrap().unwrap()["id"], 0);
    drop(client);
    let mut recovered = f.client().await;
    assert_eq!(recovered.receipt(json!(0)).await.unwrap().state, "missing");
    let result = recovered.answer_receipt(json!(0)).await.unwrap();
    assert_eq!(result.state, "completed");
    assert_eq!(result.response.unwrap()["result"]["written"], true);
    let snapshot = recovered.recovery().await.unwrap();
    assert_eq!(snapshot["pendingRequests"], json!([]));
    assert_eq!(snapshot["answerCount"], 1);
    assert_eq!(
        f.methods
            .lock()
            .unwrap()
            .iter()
            .filter(|m| *m == "host/respond")
            .count(),
        1
    );
    let store = f.stop().await;
    assert_eq!(store.state.answers.len(), 1);
}

#[tokio::test]
async fn answer_receipt_same_id_command_and_string_zero_stay_separate() {
    let mut f = Fixture::new(Mode::Normal).await;
    f.emit(pending(json!(0))).await;
    let mut client = f.client().await;
    client
        .respond(json!(0), json!({"result":{"success":false}}))
        .await
        .unwrap();
    read_frame(&mut f.native).await.unwrap().unwrap();
    let submitted = f
        .raw(
            "host/request",
            json!({"generation":f.descriptor.generation,
        "request":{"id":0,"method":"turn/start","params":{"threadId":"thread"}}}),
        )
        .await;
    assert_eq!(submitted["result"]["state"], "submitted");
    assert_eq!(
        read_frame(&mut f.native).await.unwrap().unwrap()["method"],
        "turn/start"
    );
    f.emit(json!({"id":0,"result":{"turn":{"id":"command-turn"}}}))
        .await;
    assert_eq!(
        client.receipt(json!(0)).await.unwrap().response.unwrap()["result"]["turn"]["id"],
        "command-turn"
    );
    let answer = f
        .raw(
            "host/answer/receipt",
            json!({"generation":f.descriptor.generation,"requestId":0}),
        )
        .await;
    assert_eq!(answer["result"]["response"]["result"]["written"], true);
    let other = f
        .raw(
            "host/answer/receipt",
            json!({"generation":f.descriptor.generation,"requestId":"0"}),
        )
        .await;
    assert_eq!(other["result"]["requestId"], "0");
    assert_eq!(other["result"]["state"], "missing");
    assert_eq!(other["result"]["response"], Value::Null);
    let store = f.stop().await;
    assert_eq!(store.state.commands.len(), 1);
    assert_eq!(store.state.answers.len(), 1);
}

#[tokio::test]
async fn answer_receipt_route_requires_generation_and_valid_native_id() {
    let f = Fixture::new(Mode::Normal).await;
    for params in [
        json!({"requestId":0}),
        json!({"generation":"other","requestId":0}),
        json!({"generation":f.descriptor.generation,"requestId":null}),
    ] {
        assert!(
            f.raw("host/answer/receipt", params)
                .await
                .get("error")
                .is_some()
        );
    }
    let store = f.stop().await;
    assert!(store.state.commands.is_empty());
    assert!(store.state.answers.is_empty());
}

#[tokio::test]
async fn answer_receipt_legacy_route_is_not_a_command_missing_receipt() {
    let f = Fixture::new(Mode::Legacy).await;
    let mut client = f.client().await;
    assert_eq!(client.receipt(json!(0)).await.unwrap().state, "missing");
    let result = f
        .raw(
            "host/answer/receipt",
            json!({"generation":f.descriptor.generation,"requestId":0}),
        )
        .await;
    assert_eq!(result["error"]["code"], "unsupported");
    assert!(result.get("result").is_none());
    f.methods.lock().unwrap().clear();
    assert!(client.answer_receipt(json!(0)).await.is_err());
    assert_eq!(
        *f.methods.lock().unwrap(),
        vec!["host/answer/receipt"],
        "An unsupported answer query must not fall back to commands, snapshots or writes"
    );
    assert!(f.stop().await.state.answers.is_empty());
}

#[tokio::test]
async fn answer_receipt_native_write_failure_keeps_unknown_reservation() {
    let dir = tempfile::tempdir().unwrap();
    let generation = uuid::Uuid::new_v4().to_string();
    let path = dir.path().join("recovery.json");
    let mut store = Store::open(&path, "fixture", &generation).unwrap();
    store.observe_native(pending(json!(0))).unwrap();
    let (mut writer, native) = tokio::io::duplex(1024);
    drop(native);
    let (reply, mut replies) = mpsc::channel(1);
    assert!(
        answer(
            &mut writer,
            &mut store,
            json!(0),
            json!({"result":{}}),
            &reply,
            Some(json!("outer"))
        )
        .await
        .is_err()
    );
    assert!(replies.try_recv().is_err());
    assert_eq!(store.state.snapshot()["unconfirmedWrites"], 1);
    assert_eq!(store.state.receipt("n:0", true).state, "unknown");
    assert_eq!(store.receipt("n:0").state, "missing");
    let restored = Store::open(&path, "fixture", &generation).unwrap();
    assert_eq!(restored.state.receipt("n:0", true).state, "unknown");
    assert_eq!(restored.state.pending.len(), 1);
}
