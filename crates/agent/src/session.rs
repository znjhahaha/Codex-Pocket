use crate::broker::Broker;
use crate::error::{self, Code, classify};
use anyhow::{Result, bail};
use pocket_tunnel::{read_json, write_json};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    sync::{Semaphore, mpsc},
};

/// One framed reader task prevents select cancellation from losing partial TLS frames.
pub async fn serve<S>(stream: S, broker: Arc<Broker>) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let (mut reader, mut writer) = tokio::io::split(stream);
    let (incoming, mut requests) = mpsc::channel::<Value>(16);
    let reader_task = tokio::spawn(async move {
        while let Ok(Some(v)) = read_json::<_, Value>(&mut reader).await {
            if incoming.send(v).await.is_err() {
                break;
            }
        }
    });
    let result=async{
  let mut events=broker.subscribe();let (finished,mut replies)=mpsc::channel(32);let permits=Arc::new(Semaphore::new(16));
  let mut device:Option<String>=None;let deadline=tokio::time::Instant::now()+Duration::from_secs(30);let mut attempts=0;
  let mut tick=tokio::time::interval(Duration::from_secs(1));
  loop{tokio::select!{
   request=requests.recv()=>{
    let Some(v)=request else{break};let id=v["id"].clone();let method=v["method"].as_str().unwrap_or_default().to_owned();let p=v.get("params").cloned().unwrap_or_else(||json!({}));
    if id.as_str().is_none_or(|s|s.is_empty()||s.len()>128)||!p.is_object(){bail!("Malformed request")}
    if device.is_none(){attempts+=1;if attempts>3{bail!("Authentication retry limit")}
     let result=match method.as_str(){"pair"=>broker.pair(&p).await,"hello"=>match broker.authenticate(&p).await{Ok(d)=>{device=Some(d);Ok(broker.hello().await)},Err(e)=>Err(e)},_=>Err(classify(anyhow::anyhow!("Pair or authenticate this device first"),Code::Unauthorized))};
     write_json(&mut writer,&reply(id,result)).await?;continue
    }
    let permit=permits.clone().try_acquire_owned();let Ok(permit)=permit else{write_json(&mut writer,&json!({"id":id,"error":{"code":"busy","message":"Too many pending requests"}})).await?;continue};
    let d=device.clone().unwrap();let b=broker.clone();let tx=finished.clone();
    // Tasks finish the durable command ledger even when the phone disconnects.
    tokio::spawn(async move{let _permit=permit;let result=b.request(&d,&method,p).await;let _=tx.send(reply(id,result)).await;});
   }
   // A history page can be several MB; over a home uplink it legitimately takes longer than
   // 10s. A stalled relay is still detected per chunk by the transport's 5s write timeout.
   Some(value)=replies.recv()=>{tokio::time::timeout(Duration::from_secs(120),write_reply(&mut writer,&value)).await??;}
   event=events.recv()=>{match event{
    Ok(value)=>{if let Some(d)=&device{if !broker.device_exists(d).await{bail!("Device revoked")};tokio::time::timeout(Duration::from_secs(10),write_json(&mut writer,&value)).await??;}},
    // Busy desktop threads can outrun a phone that is receiving a large reply. Skipped
    // journal events are recoverable with `sync`; closing here made every heavy
    // stream look like "connected, then disconnected when entering a chat".
    Err(tokio::sync::broadcast::error::RecvError::Lagged(_))=>{if device.is_some(){tokio::time::timeout(Duration::from_secs(10),write_json(&mut writer,&json!({"method":"pocket/resync","params":{"reason":"event_backlog"}}))).await??;}},
    Err(_)=>break,
   }}
   _=tick.tick()=>{if let Some(d)=&device{if !broker.device_exists(d).await{bail!("Device revoked")}}else if tokio::time::Instant::now()>deadline{bail!("Authentication timed out")}}
  }}Ok(())
 }.await;
    reader_task.abort();
    result
}
async fn write_reply<W: AsyncWrite + Unpin>(writer: &mut W, value: &Value) -> Result<()> {
    match write_json(writer, value).await {
        Ok(()) => {}
        Err(pocket_tunnel::Error::FrameLength) => {
            // The codec validates the whole frame before writing any bytes.
            // A read error must not close the authenticated session. Mutation
            // clients still reconcile because this code never proves rejection.
            write_json(writer, &json!({"id":value["id"],"error":{"code":"payload_too_large","message":"Response exceeds bounded preview size"}})).await?;
        }
        Err(error) => return Err(error.into()),
    }
    Ok(())
}
pub(crate) fn reply(id: Value, result: Result<Value>) -> Value {
    match result {
        Ok(v) => json!({"id":id,"result":v}),
        Err(e) => {
            let code = error::code(&e);
            json!({"id":id,"error":{"code":code.as_str(),"message":code.message()}})
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{auth, config::Config, storage};
    use tokio::io::AsyncWriteExt;
    #[tokio::test]
    async fn quota_failure_keeps_authenticated_transport_usable() {
        let temp = tempfile::tempdir().unwrap();
        let (b, _) = Broker::open(
            Config::create("wss://example.org/pocket".into()).unwrap(),
            temp.path().into(),
        )
        .unwrap();
        storage::atomic_write(
            &temp.path().join("pairing.json"),
            &serde_json::to_vec(
                &json!({"tokenHash":auth::hash("pair"),"expiresAt":storage::now()+60}),
            )
            .unwrap(),
        )
        .unwrap();
        let pair = b
            .pair(&json!({"pairingToken":"pair","deviceId":"phone","deviceName":"Test"}))
            .await
            .unwrap();
        let (client, server) = tokio::io::duplex(8192);
        let task = tokio::spawn(serve(server, b));
        let (mut r, mut w) = tokio::io::split(client);
        for (id, method, params, success) in [
            ("unauth", "quota/sources", json!({"runtimeId":"r"}), false),
            (
                "hello",
                "hello",
                json!({"protocol":1,"deviceId":"phone","deviceSecret":pair["deviceSecret"]}),
                true,
            ),
            (
                "invalid",
                "quota/read",
                json!({"runtimeId":"r","sourceId":uuid::Uuid::new_v4(),"url":"https://bad"}),
                false,
            ),
            (
                "missing",
                "quota/sources",
                json!({"runtimeId":"absent"}),
                false,
            ),
            ("ping", "ping", json!({}), true),
        ] {
            write_json(&mut w, &json!({"id":id,"method":method,"params":params}))
                .await
                .unwrap();
            let reply: Value = tokio::time::timeout(Duration::from_secs(2), read_json(&mut r))
                .await
                .unwrap()
                .unwrap()
                .expect("transport must stay open");
            assert_eq!(reply["id"], id);
            assert_eq!(reply.get("result").is_some(), success);
        }
        task.abort();
    }

    #[tokio::test]
    async fn oversized_history_returns_scoped_error_and_next_request_still_works() {
        let (mut writer, mut reader) = tokio::io::duplex(4096);
        let task = tokio::spawn(async move {
            let large =
                json!({"id":"history","result":{"text":"x".repeat(pocket_tunnel::MAX_JSON_FRAME)}});
            write_reply(&mut writer, &large).await.unwrap();
            write_reply(&mut writer, &json!({"id":"ping","result":{"ok":true}}))
                .await
                .unwrap();
        });
        let first: Value = pocket_tunnel::read_json(&mut reader)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(first["id"], "history");
        assert_eq!(first["error"]["code"], "payload_too_large");
        let next: Value = pocket_tunnel::read_json(&mut reader)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(next["id"], "ping");
        task.await.unwrap();
    }
    #[test]
    fn unclassified_errors_are_unknown_and_never_expose_diagnostics() {
        for error in [
            anyhow::anyhow!("private-path-and-token"),
            crate::native::RpcFailure::Timeout.into(),
            crate::native::RpcFailure::Disconnected.into(),
        ] {
            let value = reply(json!("id"), Err(error));
            assert_eq!(value["error"]["code"], "unknown");
            assert!(!value.to_string().contains("private-path-and-token"));
        }
    }
    #[tokio::test]
    async fn authentication_capability_and_status_errors_have_stable_codes() {
        let temp = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _) = Broker::open(config, temp.path().into()).unwrap();
        let bad_hello = broker
            .authenticate(&json!({"protocol":1,"deviceId":"phone","deviceSecret":"bad"}))
            .await;
        assert_eq!(
            reply(json!(1), bad_hello.map(|_| Value::Null))["error"]["code"],
            "unauthorized"
        );
        assert_eq!(
            reply(
                json!(2),
                broker.request("phone", "runtimes/list", json!({})).await
            )["error"]["code"],
            "revoked"
        );
        storage::atomic_write(
            &temp.path().join("pairing.json"),
            &serde_json::to_vec(
                &json!({"tokenHash":auth::hash("pair"),"expiresAt":storage::now()-1}),
            )
            .unwrap(),
        )
        .unwrap();
        let pairing = json!({"pairingToken":"pair","deviceId":"phone","deviceName":"Phone"});
        assert_eq!(
            reply(json!(3), broker.pair(&pairing).await)["error"]["code"],
            "expired"
        );
        storage::atomic_write(
            &temp.path().join("pairing.json"),
            &serde_json::to_vec(
                &json!({"tokenHash":auth::hash("pair"),"expiresAt":storage::now()+60}),
            )
            .unwrap(),
        )
        .unwrap();
        broker.pair(&pairing).await.unwrap();
        for (method, params, code) in [
            (
                "commands/status",
                json!({"commandId":"missing"}),
                "not_found",
            ),
            ("not/exposed", json!({}), "unsupported"),
            (
                "codex/request",
                json!({"method":"account/logout","params":{}}),
                "unsupported",
            ),
            (
                "codex/request",
                json!({"commandId":"invalid","method":"thread/start","params":{"config":{}}}),
                "invalid_params",
            ),
            (
                "codex/request",
                json!({"commandId":"offline","runtimeId":"absent","method":"thread/start","params":{}}),
                "rejected",
            ),
        ] {
            assert_eq!(
                reply(json!(4), broker.request("phone", method, params).await)["error"]["code"],
                code
            );
        }
    }
    #[tokio::test]
    async fn lagging_event_subscriber_resyncs_in_band_instead_of_closing() {
        let temp = tempfile::tempdir().unwrap();
        let c = Config::create("wss://example.org/pocket".into()).unwrap();
        let (b, _) = Broker::open(c, temp.path().into()).unwrap();
        storage::atomic_write(
            &temp.path().join("pairing.json"),
            &serde_json::to_vec(
                &json!({"tokenHash":auth::hash("pair"),"expiresAt":storage::now()+60}),
            )
            .unwrap(),
        )
        .unwrap();
        // A small pipe models a slow relay: the session blocks writing while events pile up.
        let (client, server) = tokio::io::duplex(4096);
        let task = tokio::spawn(serve(server, b.clone()));
        let (mut r, mut w) = tokio::io::split(client);
        write_json(&mut w,&json!({"id":"p","method":"pair","params":{"pairingToken":"pair","deviceId":"phone","deviceName":"Mate"}})).await.unwrap();
        let pair: Value = read_json(&mut r).await.unwrap().unwrap();
        write_json(&mut w,&json!({"id":"h","method":"hello","params":{"protocol":1,"deviceId":"phone","deviceSecret":pair["result"]["deviceSecret"]}})).await.unwrap();
        let hello: Value = read_json(&mut r).await.unwrap().unwrap();
        assert_eq!(hello["result"]["protocol"], 1);
        let delta = "x".repeat(200);
        for sequence in 0..(crate::broker::NOTIFICATION_CAPACITY * 2) {
            b.notify_for_test(
                json!({"method":"codex/event","params":{"sequence":sequence,"delta":delta}}),
            );
            if sequence % 256 == 0 {
                tokio::task::yield_now().await;
            }
        }
        let resync = loop {
            let value: Value = tokio::time::timeout(Duration::from_secs(10), read_json(&mut r))
                .await
                .expect("session stalled")
                .unwrap()
                .expect("session closed after event backlog");
            if value["method"] == "pocket/resync" {
                break value;
            }
        };
        assert_eq!(resync["params"]["reason"], "event_backlog");
        // The authenticated session remains usable after the backlog.
        write_json(
            &mut w,
            &json!({"id":"l","method":"runtimes/list","params":{}}),
        )
        .await
        .unwrap();
        loop {
            let value: Value = tokio::time::timeout(Duration::from_secs(10), read_json(&mut r))
                .await
                .unwrap()
                .unwrap()
                .expect("session closed");
            if value["id"] == "l" {
                assert!(value.get("result").is_some(), "{value}");
                break;
            }
        }
        // Queued events may still be in flight when the pipe closes; either outcome is fine.
        drop(r);
        drop(w);
        let _ = task.await.unwrap();
    }
    #[tokio::test]
    async fn fragmented_auth_frame_survives_timer_and_pairing_then_hello() {
        let temp = tempfile::tempdir().unwrap();
        let c = Config::create("wss://example.org/pocket".into()).unwrap();
        let (b, _) = Broker::open(c, temp.path().into()).unwrap();
        storage::atomic_write(
            &temp.path().join("pairing.json"),
            &serde_json::to_vec(
                &json!({"tokenHash":auth::hash("pair"),"expiresAt":storage::now()+60}),
            )
            .unwrap(),
        )
        .unwrap();
        let (client, server) = tokio::io::duplex(65536);
        let task = tokio::spawn(serve(server, b));
        let (mut r, mut w) = tokio::io::split(client);
        let v = json!({"id":"p","method":"pair","params":{"pairingToken":"pair","deviceId":"phone","deviceName":"Mate"}});
        let bytes = serde_json::to_vec(&v).unwrap();
        let mut frame = (bytes.len() as u32).to_be_bytes().to_vec();
        frame.extend(bytes);
        w.write_all(&frame[..2]).await.unwrap();
        tokio::time::sleep(Duration::from_millis(1100)).await;
        w.write_all(&frame[2..]).await.unwrap();
        let pair: Value = read_json(&mut r).await.unwrap().unwrap();
        assert!(pair.get("result").is_some(), "{pair}");
        write_json(&mut w,&json!({"id":"h","method":"hello","params":{"protocol":1,"deviceId":"phone","deviceSecret":pair["result"]["deviceSecret"]}})).await.unwrap();
        let hello: Value = read_json(&mut r).await.unwrap().unwrap();
        assert_eq!(hello["result"]["protocol"], 1);
        write_json(
            &mut w,
            &json!({"id":"l","method":"runtimes/list","params":{}}),
        )
        .await
        .unwrap();
        let list: Value = read_json(&mut r).await.unwrap().unwrap();
        assert_eq!(list["result"]["data"], json!([]));
        w.shutdown().await.unwrap();
        drop(r);
        drop(w);
        task.await.unwrap().unwrap();
    }
}
