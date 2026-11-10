//! Old public paths must remain aliases, including with server features disabled.
use std::any::TypeId;

#[test]
fn legacy_client_and_protocol_types_are_ipc_types() {
    macro_rules! same_type {
        ($old:ty, $new:ty) => {
            assert_eq!(TypeId::of::<$old>(), TypeId::of::<$new>());
        };
    }
    same_type!(
        pocket_runtime_host::Descriptor,
        pocket_runtime_ipc::Descriptor
    );
    same_type!(
        pocket_runtime_host::client::HostClient,
        pocket_runtime_ipc::HostClient
    );
    same_type!(
        pocket_runtime_host::client::Discovery,
        pocket_runtime_ipc::Discovery
    );
    same_type!(
        pocket_runtime_host::protocol::Capabilities,
        pocket_runtime_ipc::Capabilities
    );
    same_type!(
        pocket_runtime_host::protocol::Health,
        pocket_runtime_ipc::Health
    );
    same_type!(
        pocket_runtime_host::protocol::Receipt,
        pocket_runtime_ipc::Receipt
    );
    same_type!(
        pocket_runtime_host::protocol::Replay,
        pocket_runtime_ipc::Replay
    );
    same_type!(
        pocket_runtime_host::protocol::ReplayEvent,
        pocket_runtime_ipc::ReplayEvent
    );
    assert_eq!(
        pocket_runtime_host::protocol::PROTOCOL,
        pocket_runtime_ipc::PROTOCOL
    );
    let _: fn(
        &std::path::Path,
        &std::path::Path,
    ) -> anyhow::Result<pocket_runtime_ipc::Descriptor> =
        pocket_runtime_host::client::load_descriptor;
}

#[tokio::test]
async fn old_and_new_frame_functions_interoperate() {
    use tokio::io::{AsyncWriteExt, BufReader};
    let (mut writer, reader) = tokio::io::duplex(1024);
    let first = serde_json::json!({"id":"compat","method":"host/status","params":{}});
    let second = serde_json::json!({"id":"compat","result":{"protocol":2}});
    pocket_runtime_host::write_frame(&mut writer, &first)
        .await
        .unwrap();
    pocket_runtime_ipc::write_frame(&mut writer, &second)
        .await
        .unwrap();
    writer.shutdown().await.unwrap();
    let mut reader = BufReader::new(reader);
    assert_eq!(
        pocket_runtime_ipc::read_frame(&mut reader).await.unwrap(),
        Some(first)
    );
    assert_eq!(
        pocket_runtime_host::read_frame(&mut reader).await.unwrap(),
        Some(second)
    );
    assert!(
        pocket_runtime_ipc::read_frame(&mut reader)
            .await
            .unwrap()
            .is_none()
    );
}
