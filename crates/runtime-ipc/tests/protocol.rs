use pocket_runtime_ipc::support::{MAX_FRAME, key};
use pocket_runtime_ipc::{read_frame, write_frame};
use serde_json::json;
use tokio::io::{AsyncWriteExt, BufReader};

#[tokio::test]
async fn frames_preserve_unicode_ids_and_message_boundaries() {
    let (mut writer, reader) = tokio::io::duplex(4096);
    let messages = [
        json!({"id":"operation-一","method":"host/status","params":{}}),
        json!({"method":"host/event","params":{"event":{"text":"你好\n第二行"}}}),
    ];
    for message in &messages {
        write_frame(&mut writer, message).await.unwrap();
    }
    writer.shutdown().await.unwrap();
    let mut reader = BufReader::new(reader);
    for message in messages {
        assert_eq!(read_frame(&mut reader).await.unwrap(), Some(message));
    }
    assert!(read_frame(&mut reader).await.unwrap().is_none());
}

#[tokio::test]
async fn malformed_partial_and_non_object_frames_are_rejected_without_echo() {
    for input in [
        b"{\"body\":\"fixture-private\"}".as_slice(),
        b"[\"fixture-private\"]\n",
        b"fixture-private\n",
    ] {
        let error = read_frame(&mut BufReader::new(input)).await.unwrap_err();
        assert!(!format!("{error:#}").contains("fixture-private"));
    }
    let mut oversized = vec![b' '; MAX_FRAME + 1];
    oversized[MAX_FRAME] = b'\n';
    assert!(
        read_frame(&mut BufReader::new(oversized.as_slice()))
            .await
            .is_err()
    );
}

#[tokio::test]
async fn frame_limit_includes_newline_and_rejects_before_writing() {
    let base = serde_json::to_vec(&json!({"body":""})).unwrap().len();
    let value = json!({"body":"x".repeat(MAX_FRAME - 1 - base)});
    assert_eq!(serde_json::to_vec(&value).unwrap().len(), MAX_FRAME - 1);
    let (mut writer, reader) = tokio::io::duplex(MAX_FRAME + 1);
    write_frame(&mut writer, &value).await.unwrap();
    writer.shutdown().await.unwrap();
    assert_eq!(
        read_frame(&mut BufReader::new(reader)).await.unwrap(),
        Some(value)
    );

    let oversized = json!({"body":"x".repeat(MAX_FRAME - base)});
    let (mut writer, reader) = tokio::io::duplex(1);
    assert!(write_frame(&mut writer, &oversized).await.is_err());
    writer.shutdown().await.unwrap();
    assert!(
        read_frame(&mut BufReader::new(reader))
            .await
            .unwrap()
            .is_none()
    );
}

#[test]
fn operation_keys_keep_numeric_and_string_ids_distinct_and_bounded() {
    assert_eq!(key(&json!("17")), Some("s:17".into()));
    assert_eq!(key(&json!(17)), Some("n:17".into()));
    assert_eq!(key(&json!(-17)), Some("n:-17".into()));
    assert!(key(&json!(u64::MAX)).is_some());
    assert!(key(&json!("x".repeat(256))).is_some());
    for invalid in [
        json!(null),
        json!(true),
        json!([]),
        json!({}),
        json!(1.5),
        json!(""),
        json!("x".repeat(257)),
    ] {
        assert!(key(&invalid).is_none());
    }
}
