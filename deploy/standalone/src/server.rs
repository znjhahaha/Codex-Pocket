use std::time::Duration;

use axum::{
    Json, Router,
    extract::{
        OriginalUri, Path, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use bytes::Bytes;
use futures_util::{SinkExt, StreamExt};
use tokio::{
    sync::{mpsc, watch},
    time::{Instant, interval, timeout},
};

use crate::{
    ENVELOPE_HEADER, MAX_CHUNK, RelayState,
    config::valid_room,
    state::{Lease, Role},
};

const SEND_TIMEOUT: Duration = Duration::from_secs(5);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(20);
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub fn router(state: RelayState) -> Router {
    Router::new()
        .route(
            "/healthz",
            get(|| async { Json(serde_json::json!({"status":"ok"})) }),
        )
        .route("/v1/tunnel/{room}/{role}", get(upgrade))
        .fallback(|| async { StatusCode::NOT_FOUND })
        .with_state(state)
}

async fn upgrade(
    State(state): State<RelayState>,
    Path((room, role)): Path<(String, String)>,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let role = match role.as_str() {
        "helper" => Role::Helper,
        "phone" => Role::Phone,
        _ => return StatusCode::BAD_REQUEST.into_response(),
    };
    if uri.query().is_some() || uri.path().contains('%') || !valid_room(&room) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    if !state.authorized(&room, role, &headers) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let (lease, rx) = match state.reserve(&room, role) {
        Ok(v) => v,
        Err(status) => return status.into_response(),
    };
    let max_size = MAX_CHUNK
        + if role == Role::Helper {
            ENVELOPE_HEADER
        } else {
            0
        };
    ws.max_message_size(max_size)
        .max_frame_size(max_size)
        .read_buffer_size(4096)
        .write_buffer_size(0)
        .max_write_buffer_size(2 * (MAX_CHUNK + ENVELOPE_HEADER))
        .on_upgrade(move |socket| serve_socket(socket, state, lease, rx))
        .into_response()
}

async fn serve_socket(
    socket: WebSocket,
    state: RelayState,
    lease: Lease,
    mut rx: mpsc::Receiver<Bytes>,
) {
    let (mut sink, mut source) = socket.split();
    let (activity, last_received) = watch::channel(Instant::now());
    let (control, mut controls) = mpsc::channel(4);
    // Read and write independently: waiting for another peer's capacity must not
    // stop this socket draining its own outbound queue (full-duplex deadlock).
    let reader = async {
        loop {
            let incoming = tokio::select! {
                _ = lease.cancel.cancelled() => break,
                incoming = source.next() => incoming,
            };
            match incoming {
                Some(Ok(Message::Binary(bytes))) if !bytes.is_empty() => {
                    activity.send_replace(Instant::now());
                    if !state.forward(&lease, bytes).await {
                        break;
                    }
                }
                Some(Ok(Message::Ping(bytes))) => {
                    activity.send_replace(Instant::now());
                    if !matches!(
                        timeout(SEND_TIMEOUT, control.send(Message::Pong(bytes))).await,
                        Ok(Ok(()))
                    ) {
                        break;
                    }
                }
                Some(Ok(Message::Pong(_))) => {
                    activity.send_replace(Instant::now());
                }
                _ => break,
            }
        }
    };
    let writer = async {
        let mut heartbeat = interval(HEARTBEAT_INTERVAL);
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        heartbeat.tick().await;
        loop {
            let outbound = tokio::select! {
                _ = lease.cancel.cancelled() => break,
                message = controls.recv() => match message { Some(message) => message, None => break },
                message = rx.recv() => match message { Some(bytes) => Message::Binary(bytes), None => break },
                _ = heartbeat.tick() => {
                    if last_received.borrow().elapsed() >= IDLE_TIMEOUT { break; }
                    Message::Ping(Bytes::new())
                }
            };
            let sent = tokio::select! {
                biased;
                _ = lease.cancel.cancelled() => false,
                result = timeout(SEND_TIMEOUT, sink.send(outbound)) => matches!(result, Ok(Ok(()))),
            };
            if !sent {
                break;
            }
        }
    };
    tokio::select! { _ = reader => {}, _ = writer => {} }
    // Removing the lease synchronously cancels peers and preserves close ordering.
    // Dropping the socket sends no untrusted close reason and cannot block cleanup.
    drop(lease);
}
