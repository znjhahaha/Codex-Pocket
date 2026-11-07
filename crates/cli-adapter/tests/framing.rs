use codex_cli_adapter::{JsonRpcConnection, Limits};
use serde_json::json;
use tokio::io::{AsyncWriteExt, DuplexStream};
use tokio_tungstenite::{WebSocketStream, tungstenite::protocol::Role};

async fn pair(max: usize) -> (JsonRpcConnection<DuplexStream>, DuplexStream) {
    let (client, server) = tokio::io::duplex(65536);
    let client = JsonRpcConnection::from_raw_socket(
        client,
        Limits {
            max_message_bytes: max,
            ..Limits::default()
        },
    )
    .await
    .unwrap();
    (client, server)
}

fn frame(op: u8, payload: &[u8]) -> Vec<u8> {
    assert!(payload.len() < 126);
    let mut bytes = vec![op, payload.len() as u8];
    bytes.extend(payload);
    bytes
}

#[tokio::test]
async fn fragmented_json_and_utf8_across_websocket_and_io_boundaries() {
    let (mut client, mut server) = pair(1024).await;
    let payload = "{\"id\":\"native/不改\",\"result\":{\"threadId\":\"not-a-uuid\"}}".as_bytes();
    let cut = payload.iter().position(|byte| *byte > 127).unwrap() + 1;
    let bytes = [frame(0x01, &payload[..cut]), frame(0x80, &payload[cut..])].concat();
    let sender = tokio::spawn(async move {
        for byte in bytes {
            server.write_all(&[byte]).await.unwrap();
            tokio::task::yield_now().await;
        }
        server
    });
    assert_eq!(
        client.read().await.unwrap(),
        Some(json!({"id":"native/不改", "result":{"threadId":"not-a-uuid"}}))
    );
    sender.await.unwrap();
}

#[tokio::test]
async fn coalesced_messages_remain_distinct_and_ids_keep_json_types() {
    let (mut client, mut server) = pair(1024).await;
    let first = br#"{"id":9007199254740993,"result":{}}"#;
    let second = br#"{"id":"009007199254740993","result":{}}"#;
    server
        .write_all(&[frame(0x81, first), frame(0x81, second)].concat())
        .await
        .unwrap();
    assert_eq!(
        client.read().await.unwrap().unwrap()["id"],
        json!(9007199254740993_u64)
    );
    assert_eq!(
        client.read().await.unwrap().unwrap()["id"],
        "009007199254740993"
    );
}

#[tokio::test]
async fn close_frame_is_clean_eof() {
    let (mut client, mut server) = pair(1024).await;
    server.write_all(&frame(0x88, &[])).await.unwrap();
    assert!(client.read().await.unwrap().is_none());
}

#[tokio::test]
async fn bare_eof_is_an_error() {
    let (mut client, server) = pair(1024).await;
    drop(server);
    assert!(
        client.read().await.is_err(),
        "an unannounced transport EOF must not look like close"
    );
}

#[tokio::test]
async fn truncated_frame_is_an_error() {
    let (mut client, mut server) = pair(1024).await;
    server.write_all(&[0x81, 20, b'{']).await.unwrap();
    drop(server);
    assert!(client.read().await.is_err());
}

#[tokio::test]
async fn oversized_frame_and_fragmented_message_are_bounded() {
    for bytes in [
        frame(0x81, &[b'x'; 33]),
        [frame(0x01, &[b'x'; 20]), frame(0x80, &[b'x'; 20])].concat(),
        vec![0x81, 127, 0, 0, 0, 1, 0, 0, 0, 0],
    ] {
        let (mut client, mut server) = pair(32).await;
        server.write_all(&bytes).await.unwrap();
        assert!(
            client.read().await.is_err(),
            "must reject over limit without waiting for payload"
        );
    }
}

#[tokio::test]
async fn invalid_json_binary_and_non_object_messages_are_rejected() {
    for bytes in [
        frame(0x81, b"not json"),
        frame(0x82, b"{}"),
        frame(0x81, b"[]"),
    ] {
        let (mut client, mut server) = pair(1024).await;
        server.write_all(&bytes).await.unwrap();
        assert!(client.read().await.is_err());
    }
}

#[tokio::test]
async fn ping_is_answered_before_next_json() {
    use futures_util::StreamExt;
    let (mut client, mut server) = pair(1024).await;
    server
        .write_all(&[frame(0x89, b"hello"), frame(0x81, br#"{"method":"ready"}"#)].concat())
        .await
        .unwrap();
    assert_eq!(
        client.read().await.unwrap(),
        Some(json!({"method":"ready"}))
    );
    let mut server = WebSocketStream::from_raw_socket(server, Role::Server, None).await;
    let response = tokio::time::timeout(std::time::Duration::from_secs(1), server.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(
        matches!(response, tokio_tungstenite::tungstenite::Message::Pong(bytes) if bytes == b"hello"[..])
    );
}

#[tokio::test]
async fn write_sends_one_masked_text_message_with_opaque_ids() {
    use futures_util::StreamExt;
    let (mut client, server) = pair(1024).await;
    let message = json!({"id":"native:id/0001", "method":"turn/start", "params":{"threadId":"opaque-native-thread", "turnId":17}});
    client.write(&message).await.unwrap();
    let mut server = WebSocketStream::from_raw_socket(server, Role::Server, None).await;
    let wire = tokio::time::timeout(std::time::Duration::from_secs(1), server.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(wire.to_text().unwrap()).unwrap(),
        message
    );
}

#[tokio::test]
async fn approval_response_preserves_server_request_id_without_waiting_for_resolution() {
    use futures_util::{SinkExt, StreamExt};
    for id in [json!(0), json!(9007199254740993_u64), json!("native/0007")] {
        let (mut client, server) = pair(1024).await;
        let mut server = WebSocketStream::from_raw_socket(server, Role::Server, None).await;
        let request = json!({"id":id,"method":"item/commandExecution/requestApproval","params":{"threadId":"synthetic-thread","availableDecisions":["accept","cancel"]}});
        server
            .send(tokio_tungstenite::tungstenite::Message::Text(
                request.to_string().into(),
            ))
            .await
            .unwrap();
        let received = client.read().await.unwrap().unwrap();
        assert_eq!(received, request);
        let response = json!({"id":received["id"],"result":{"decision":"cancel"}});
        tokio::time::timeout(std::time::Duration::from_secs(1), client.write(&response))
            .await
            .expect("response write must not await native resolution")
            .unwrap();
        let wire = tokio::time::timeout(std::time::Duration::from_secs(1), server.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(wire.to_text().unwrap()).unwrap(),
            response
        );
        // Native handling is deliberately withheld; successful write is only submission.
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(30), client.read())
                .await
                .is_err()
        );
    }
}

#[tokio::test]
async fn outgoing_bounds_and_non_object_are_rejected_without_sending() {
    let (mut client, _server) = pair(32).await;
    assert!(
        client
            .write(&json!({"method":"x".repeat(50)}))
            .await
            .is_err()
    );
    assert!(client.write(&json!([])).await.is_err());
}

#[tokio::test]
async fn exactly_at_limit_and_arbitrary_precision_ids_round_trip() {
    use futures_util::StreamExt;
    let text = r#"{"id":1234567890123456789012345678901234567890,"result":null}"#;
    let value: serde_json::Value = serde_json::from_str(text).unwrap();
    let (mut client, mut server) = pair(text.len()).await;
    server
        .write_all(&frame(0x81, text.as_bytes()))
        .await
        .unwrap();
    assert_eq!(
        client.read().await.unwrap().unwrap()["id"].to_string(),
        "1234567890123456789012345678901234567890"
    );
    client.write(&value).await.unwrap();
    let mut server = WebSocketStream::from_raw_socket(server, Role::Server, None).await;
    let wire = server.next().await.unwrap().unwrap();
    let received: serde_json::Value = serde_json::from_str(wire.to_text().unwrap()).unwrap();
    assert_eq!(received, value);
}

#[tokio::test]
async fn cancelled_read_preserves_an_incomplete_frame() {
    let (mut client, mut server) = pair(1024).await;
    let bytes = frame(0x81, br#"{"id":"same-after-cancel","result":true}"#);
    server.write_all(&bytes[..8]).await.unwrap();
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(10), client.read())
            .await
            .is_err()
    );
    server.write_all(&bytes[8..]).await.unwrap();
    assert_eq!(
        client.read().await.unwrap(),
        Some(json!({"id":"same-after-cancel", "result":true}))
    );
}

#[tokio::test]
async fn close_sends_a_client_close_frame_and_prevents_writes() {
    use futures_util::StreamExt;
    let (mut client, server) = pair(1024).await;
    client.close().await.unwrap();
    let mut server = WebSocketStream::from_raw_socket(server, Role::Server, None).await;
    assert!(matches!(
        server.next().await.unwrap().unwrap(),
        tokio_tungstenite::tungstenite::Message::Close(_)
    ));
    assert!(client.write(&json!({"method":"late"})).await.is_err());
    assert!(client.read().await.unwrap().is_none());
}

#[tokio::test]
async fn zero_limits_are_invalid() {
    let (client, _) = tokio::io::duplex(32);
    let limits = Limits {
        max_message_bytes: 0,
        ..Limits::default()
    };
    assert!(
        JsonRpcConnection::from_raw_socket(client, limits)
            .await
            .is_err()
    );
}
