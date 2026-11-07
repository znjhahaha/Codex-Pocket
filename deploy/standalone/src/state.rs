use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use axum::http::{HeaderMap, StatusCode};
use bytes::Bytes;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::{
    config::{ConfigError, MAX_PHONES, MAX_ROOMS, RelayConfig, parse_hash, valid_room},
    envelope::Envelope,
};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Role {
    Helper,
    Phone,
}

struct Endpoint {
    generation: Uuid,
    tx: mpsc::Sender<Bytes>,
    cancel: CancellationToken,
}
struct Room {
    helper_hash: [u8; 32],
    phone_hash: [u8; 32],
    helper: Option<Endpoint>,
    phones: HashMap<Uuid, Endpoint>,
}
struct Inner {
    rooms: Mutex<HashMap<String, Room>>,
    queue_capacity: usize,
}

#[derive(Clone)]
pub struct RelayState(Arc<Inner>);

pub(crate) struct Lease {
    state: RelayState,
    room: String,
    role: Role,
    pub(crate) id: Uuid,
    pub(crate) cancel: CancellationToken,
}

impl Drop for Lease {
    fn drop(&mut self) {
        self.state.release(self);
    }
}

fn drop_helper(room: &mut Room) {
    if let Some(helper) = room.helper.take() {
        helper.cancel.cancel();
    }
    for (_, phone) in room.phones.drain() {
        phone.cancel.cancel();
    }
}

fn notify_helper(room: &mut Room, message: Bytes) -> bool {
    if room
        .helper
        .as_ref()
        .is_some_and(|helper| helper.tx.try_send(message).is_ok())
    {
        true
    } else {
        drop_helper(room);
        false
    }
}

impl RelayState {
    pub fn new(config: RelayConfig) -> Result<Self, ConfigError> {
        if config.rooms.is_empty()
            || config.rooms.len() > MAX_ROOMS
            || !(1..=32).contains(&config.queue_capacity)
        {
            return Err(ConfigError);
        }
        let mut rooms = HashMap::new();
        for cfg in config.rooms {
            if !valid_room(&cfg.id) {
                return Err(ConfigError);
            }
            let helper_hash = parse_hash(&cfg.helper_token_sha256)?;
            let phone_hash = parse_hash(&cfg.phone_token_sha256)?;
            if bool::from(helper_hash.ct_eq(&phone_hash)) || rooms.contains_key(&cfg.id) {
                return Err(ConfigError);
            }
            rooms.insert(
                cfg.id,
                Room {
                    helper_hash,
                    phone_hash,
                    helper: None,
                    phones: HashMap::new(),
                },
            );
        }
        Ok(Self(Arc::new(Inner {
            rooms: Mutex::new(rooms),
            queue_capacity: config.queue_capacity,
        })))
    }

    pub(crate) fn authorized(&self, room: &str, role: Role, headers: &HeaderMap) -> bool {
        let mut values = headers.get_all(axum::http::header::AUTHORIZATION).iter();
        let Some(value) = values.next() else {
            return false;
        };
        if values.next().is_some() {
            return false;
        }
        let Ok(value) = value.to_str() else {
            return false;
        };
        let Some(token) = value.strip_prefix("Bearer ") else {
            return false;
        };
        if !(32..=256).contains(&token.len()) || !token.bytes().all(|b| b.is_ascii_graphic()) {
            return false;
        }
        let digest: [u8; 32] = Sha256::digest(token.as_bytes()).into();
        let rooms = self.0.rooms.lock().unwrap_or_else(|p| p.into_inner());
        let expected = rooms.get(room).map(|room| match role {
            Role::Helper => room.helper_hash,
            Role::Phone => room.phone_hash,
        });
        // Always compare 32 bytes, including for an unknown room.
        let matches = bool::from(digest.ct_eq(&expected.unwrap_or([0; 32])));
        matches && expected.is_some()
    }

    pub(crate) fn reserve(
        &self,
        name: &str,
        role: Role,
    ) -> Result<(Lease, mpsc::Receiver<Bytes>), StatusCode> {
        let mut rooms = self.0.rooms.lock().unwrap_or_else(|p| p.into_inner());
        let room = rooms.get_mut(name).ok_or(StatusCode::UNAUTHORIZED)?;
        let (tx, rx) = mpsc::channel(self.0.queue_capacity);
        let id = Uuid::new_v4();
        let cancel = CancellationToken::new();
        match role {
            Role::Helper => {
                if room.helper.is_some() {
                    return Err(StatusCode::CONFLICT);
                }
                room.helper = Some(Endpoint {
                    generation: id,
                    tx,
                    cancel: cancel.clone(),
                });
            }
            Role::Phone => {
                if room.helper.is_none() {
                    return Err(StatusCode::SERVICE_UNAVAILABLE);
                }
                if room.phones.len() >= MAX_PHONES {
                    return Err(StatusCode::TOO_MANY_REQUESTS);
                }
                if !notify_helper(room, Envelope::Connect(id).encode()) {
                    return Err(StatusCode::SERVICE_UNAVAILABLE);
                }
                room.phones.insert(
                    id,
                    Endpoint {
                        generation: id,
                        tx,
                        cancel: cancel.clone(),
                    },
                );
            }
        }
        Ok((
            Lease {
                state: self.clone(),
                room: name.to_owned(),
                role,
                id,
                cancel,
            },
            rx,
        ))
    }

    fn release(&self, lease: &Lease) {
        let mut rooms = self.0.rooms.lock().unwrap_or_else(|p| p.into_inner());
        let Some(room) = rooms.get_mut(&lease.room) else {
            return;
        };
        match lease.role {
            Role::Helper => {
                if room
                    .helper
                    .as_ref()
                    .is_some_and(|h| h.generation == lease.id)
                {
                    drop_helper(room);
                }
            }
            Role::Phone => {
                if let Some(phone) = room.phones.remove(&lease.id) {
                    phone.cancel.cancel();
                    notify_helper(room, Envelope::Disconnect(lease.id).encode());
                }
            }
        }
    }

    pub(crate) async fn forward(&self, lease: &Lease, bytes: Bytes) -> bool {
        // Do not hold the room lock while a bounded destination queue drains.
        let destination = {
            let mut rooms = self.0.rooms.lock().unwrap_or_else(|p| p.into_inner());
            let Some(room) = rooms.get_mut(&lease.room) else {
                return false;
            };
            match lease.role {
                Role::Phone => {
                    if !room.phones.contains_key(&lease.id) {
                        return false;
                    }
                    room.helper.as_ref().map(|endpoint| {
                        (
                            endpoint.tx.clone(),
                            endpoint.cancel.clone(),
                            Envelope::Data(lease.id, bytes.clone()).encode(),
                            None,
                        )
                    })
                }
                Role::Helper => {
                    if room
                        .helper
                        .as_ref()
                        .is_none_or(|endpoint| endpoint.generation != lease.id)
                    {
                        return false;
                    }
                    match Envelope::decode_helper(&bytes) {
                        Ok(Envelope::Data(id, data)) => room.phones.get(&id).map(|endpoint| {
                            (endpoint.tx.clone(), endpoint.cancel.clone(), data, Some(id))
                        }),
                        _ => None,
                    }
                }
            }
        };
        if let Some((sender, destination_cancel, data, phone_id)) = destination {
            let delivered = tokio::select! {
                biased;
                _ = lease.cancel.cancelled() => return false,
                _ = destination_cancel.cancelled() => false,
                result = tokio::time::timeout(std::time::Duration::from_secs(5), sender.send(data)) => matches!(result, Ok(Ok(()))),
            };
            if delivered {
                return true;
            }
            let mut rooms = self.0.rooms.lock().unwrap_or_else(|p| p.into_inner());
            let Some(room) = rooms.get_mut(&lease.room) else {
                return false;
            };
            match lease.role {
                Role::Phone => {
                    if room.phones.contains_key(&lease.id) {
                        drop_helper(room);
                    }
                    return false;
                }
                Role::Helper => {
                    if room
                        .helper
                        .as_ref()
                        .is_none_or(|endpoint| endpoint.generation != lease.id)
                    {
                        return false;
                    }
                    if let Some(id) = phone_id {
                        if let Some(phone) = room.phones.remove(&id) {
                            phone.cancel.cancel();
                            return notify_helper(room, Envelope::Disconnect(id).encode());
                        }
                        // Release already queued the disconnect. Sending a second
                        // notification could fill the helper queue and close it.
                        return true;
                    }
                    return false;
                }
            }
        }
        let mut rooms = self.0.rooms.lock().unwrap_or_else(|p| p.into_inner());
        let Some(room) = rooms.get_mut(&lease.room) else {
            return false;
        };
        match lease.role {
            Role::Phone => {
                if !room.phones.contains_key(&lease.id) {
                    return false;
                }
                drop_helper(room);
                false
            }
            Role::Helper => {
                if room
                    .helper
                    .as_ref()
                    .is_none_or(|h| h.generation != lease.id)
                {
                    return false;
                }
                match Envelope::decode_helper(&bytes) {
                    Ok(Envelope::Disconnect(id)) => {
                        if let Some(phone) = room.phones.remove(&id) {
                            phone.cancel.cancel();
                            true
                        } else {
                            drop_helper(room);
                            false
                        }
                    }
                    _ => {
                        drop_helper(room);
                        false
                    }
                }
            }
        }
    }

    /// Stop every active socket. Useful during graceful shutdown.
    pub fn shutdown(&self) {
        let mut rooms = self.0.rooms.lock().unwrap_or_else(|p| p.into_inner());
        for room in rooms.values_mut() {
            drop_helper(room);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RoomConfig;

    fn state() -> RelayState {
        RelayState::new(RelayConfig {
            queue_capacity: 1,
            rooms: vec![RoomConfig {
                id: "r".into(),
                helper_token_sha256: "11".repeat(32),
                phone_token_sha256: "22".repeat(32),
            }],
            ..Default::default()
        })
        .unwrap()
    }

    #[tokio::test]
    async fn helper_queue_congestion_cancels_every_peer_without_silent_drop() {
        let state = state();
        let (h, _hrx) = state.reserve("r", Role::Helper).unwrap();
        let (p, _prx) = state.reserve("r", Role::Phone).unwrap();
        assert!(
            !state
                .forward(&p, Bytes::from_static(b"more than the queued connect"))
                .await
        );
        assert!(h.cancel.is_cancelled());
        assert!(p.cancel.is_cancelled());
        assert!(state.reserve("r", Role::Helper).is_ok());
    }

    #[tokio::test]
    async fn phone_queue_congestion_cancels_phone_and_notifies_helper() {
        let state = state();
        let (h, mut hrx) = state.reserve("r", Role::Helper).unwrap();
        let (p, _prx) = state.reserve("r", Role::Phone).unwrap();
        let _ = hrx.recv().await.unwrap();
        assert!(
            state
                .forward(
                    &h,
                    Envelope::Data(p.id, Bytes::from_static(b"one")).encode()
                )
                .await
        );
        assert!(
            state
                .forward(
                    &h,
                    Envelope::Data(p.id, Bytes::from_static(b"two")).encode()
                )
                .await
        );
        assert!(p.cancel.is_cancelled());
        assert!(!h.cancel.is_cancelled());
        assert_eq!(
            Envelope::decode(&hrx.recv().await.unwrap()).unwrap(),
            Envelope::Disconnect(p.id)
        );
    }

    #[tokio::test]
    async fn transient_phone_congestion_waits_and_preserves_every_byte() {
        let state = state();
        let (helper, mut helper_rx) = state.reserve("r", Role::Helper).unwrap();
        let (phone, mut phone_rx) = state.reserve("r", Role::Phone).unwrap();
        helper_rx.recv().await.unwrap();
        assert!(
            state
                .forward(
                    &helper,
                    Envelope::Data(phone.id, Bytes::from_static(b"first")).encode()
                )
                .await
        );
        let second = state.forward(
            &helper,
            Envelope::Data(phone.id, Bytes::from_static(b"second")).encode(),
        );
        tokio::pin!(second);
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(30), &mut second)
                .await
                .is_err()
        );
        assert_eq!(phone_rx.recv().await.unwrap(), Bytes::from_static(b"first"));
        assert!(second.await);
        assert_eq!(
            phone_rx.recv().await.unwrap(),
            Bytes::from_static(b"second")
        );
        assert!(!phone.cancel.is_cancelled());
        assert!(!helper.cancel.is_cancelled());
    }

    #[tokio::test]
    async fn source_shutdown_interrupts_a_capacity_wait_without_touching_replacement() {
        let state = state();
        let (helper, mut helper_rx) = state.reserve("r", Role::Helper).unwrap();
        let (phone, _phone_rx) = state.reserve("r", Role::Phone).unwrap();
        helper_rx.recv().await.unwrap();
        assert!(
            state
                .forward(
                    &helper,
                    Envelope::Data(phone.id, Bytes::from_static(b"first")).encode()
                )
                .await
        );
        let next = state.forward(
            &helper,
            Envelope::Data(phone.id, Bytes::from_static(b"second")).encode(),
        );
        tokio::pin!(next);
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(30), &mut next)
                .await
                .is_err()
        );
        state.shutdown();
        let (replacement, _rx) = state.reserve("r", Role::Helper).unwrap();
        assert!(!next.await);
        assert!(!replacement.cancel.is_cancelled());
    }

    #[tokio::test]
    async fn departing_phone_during_capacity_wait_notifies_helper_only_once() {
        let state = state();
        let (helper, mut helper_rx) = state.reserve("r", Role::Helper).unwrap();
        let (phone, _phone_rx) = state.reserve("r", Role::Phone).unwrap();
        helper_rx.recv().await.unwrap();
        let id = phone.id;
        assert!(
            state
                .forward(
                    &helper,
                    Envelope::Data(id, Bytes::from_static(b"first")).encode()
                )
                .await
        );
        let waiting = state.forward(
            &helper,
            Envelope::Data(id, Bytes::from_static(b"second")).encode(),
        );
        tokio::pin!(waiting);
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(30), &mut waiting)
                .await
                .is_err()
        );

        drop(phone);
        assert!(
            waiting.await,
            "a departed destination must not disconnect the source"
        );
        assert!(!helper.cancel.is_cancelled());
        assert_eq!(
            Envelope::decode(&helper_rx.recv().await.unwrap()).unwrap(),
            Envelope::Disconnect(id)
        );
        assert!(helper_rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn stale_helper_lease_cannot_remove_replacement() {
        let state = state();
        let (old, _) = state.reserve("r", Role::Helper).unwrap();
        state.shutdown();
        let (new, _) = state.reserve("r", Role::Helper).unwrap();
        drop(old);
        assert!(!new.cancel.is_cancelled());
        assert!(matches!(
            state.reserve("r", Role::Helper),
            Err(StatusCode::CONFLICT)
        ));
    }

    #[tokio::test]
    async fn disconnect_notification_congestion_also_disconnects_helper() {
        let state = state();
        let (h, _) = state.reserve("r", Role::Helper).unwrap();
        // Keep the receiver so this exercises capacity rather than receiver closure.
        drop(h);
        let (h, _hrx) = state.reserve("r", Role::Helper).unwrap();
        let (p, _) = state.reserve("r", Role::Phone).unwrap();
        drop(p);
        assert!(h.cancel.is_cancelled());
    }
}
