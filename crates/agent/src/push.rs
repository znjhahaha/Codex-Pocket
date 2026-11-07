//! Optional Huawei delivery. The gateway receives only a device route and opaque event UUID.
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::HashMap, path::PathBuf, time::Duration};
use tokio::sync::Mutex;
use crate::storage;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Route { device: String, runtime: String, thread: String, expires_at: u64 }
#[derive(Clone, Default, Serialize, Deserialize)]
struct Saved { devices: Vec<String>, routes: HashMap<String, Route> }
struct Presence { thread: Option<String>, foreground: bool, until: u64 }
pub struct PushDelivery {
    path: PathBuf,
    saved: Mutex<Saved>,
    presence: Mutex<HashMap<String, Presence>>,
    gateway: Option<(String, String)>,
    client: reqwest::Client,
}
impl PushDelivery {
    pub fn open(state: PathBuf) -> Result<Self> {
        let path = state.join("push-routes.json");
        let saved = if path.exists() { serde_json::from_slice(&std::fs::read(&path)?)? } else { Saved::default() };
        let gateway = match (std::env::var("POCKET_PUSH_GATEWAY"), std::env::var("POCKET_PUSH_SECRET")) {
            (Ok(url), Ok(secret)) if secret.len() >= 32 && secret.len() <= 256 => {
                let parsed = url::Url::parse(&url)?;
                if parsed.scheme() != "https" || parsed.host_str().is_none() || !parsed.username().is_empty() || parsed.password().is_some() || parsed.query().is_some() || parsed.fragment().is_some() { bail!("Invalid push gateway configuration") }
                Some((url.trim_end_matches('/').to_owned(), secret))
            }
            _ => None,
        };
        let client = reqwest::Client::builder().timeout(Duration::from_secs(12)).redirect(reqwest::redirect::Policy::none()).build()?;
        Ok(Self { path, saved: Mutex::new(saved), presence: Mutex::new(HashMap::new()), gateway, client })
    }
    pub fn configured(&self) -> bool { self.gateway.is_some() }
    async fn post(&self, path: &str, body: Value) -> Result<()> {
        let Some((origin, secret)) = &self.gateway else { bail!("Push service is not configured") };
        let response = self.client.post(format!("{origin}{path}")).bearer_auth(secret).json(&body).send().await.map_err(|_| anyhow::anyhow!("Push service is unavailable"))?;
        if !response.status().is_success() { bail!("Push service did not confirm delivery") }
        Ok(())
    }
    pub async fn register(&self, device: &str, token: &str) -> Result<Value> {
        if uuid::Uuid::parse_str(device).is_err() || token.is_empty() || token.len() > 4096 || !token.bytes().all(|b| b.is_ascii_graphic()) { bail!("Invalid push registration") }
        self.post("/register", json!({"deviceId":device,"token":token})).await?;
        let mut saved = self.saved.lock().await;
        let mut next = saved.clone();
        if !next.devices.iter().any(|id| id == device) { next.devices.push(device.to_owned()); }
        storage::atomic_write(&self.path, &serde_json::to_vec(&next)?)?;
        *saved = next;
        Ok(json!({"registered":true}))
    }
    pub async fn unregister(&self, device: &str) -> Result<Value> {
        // Remove local authority first; provider failure cannot resurrect revoked delivery.
        { let mut saved = self.saved.lock().await; saved.devices.retain(|id| id != device); saved.routes.retain(|_,route| route.device != device); storage::atomic_write(&self.path,&serde_json::to_vec(&*saved)?)?; }
        self.presence.lock().await.remove(device);
        if self.configured() { self.post("/unregister", json!({"deviceId":device})).await?; }
        Ok(json!({"revoked":true}))
    }
    pub async fn presence(&self, device: &str, thread: Option<String>, foreground: bool) -> Value {
        self.presence.lock().await.insert(device.to_owned(), Presence { thread, foreground, until: storage::now() + 45 });
        json!({"ok":true})
    }
    pub async fn resolve(&self, device: &str, event: &str) -> Result<Value> {
        if uuid::Uuid::parse_str(event).is_err() { bail!("Invalid notification") }
        let saved = self.saved.lock().await;
        let route = saved.routes.get(event).filter(|r| r.device == device && r.expires_at > storage::now()).ok_or_else(|| anyhow::anyhow!("Notification is unavailable"))?;
        Ok(json!({"runtimeId":route.runtime,"threadId":route.thread}))
    }
    pub async fn emit(&self, runtime: &str, thread: &str, authorized_devices: &[String]) -> Result<()> {
        if !self.configured() || thread.is_empty() { return Ok(()) }
        let now = storage::now();
        let mut targets = Vec::new();
        {
            let mut saved = self.saved.lock().await;
            let presence = self.presence.lock().await;
            saved.routes.retain(|_,route| route.expires_at > now);
            for device in saved.devices.clone() {
                if !authorized_devices.contains(&device) { continue }
                if presence.get(&device).is_some_and(|p| p.foreground && p.until > now && p.thread.as_deref() == Some(thread)) { continue }
                let event = uuid::Uuid::new_v4().to_string();
                saved.routes.insert(event.clone(), Route { device: device.clone(), runtime: runtime.to_owned(), thread: thread.to_owned(), expires_at: now + 86400 });
                targets.push((device, event));
            }
            // A bounded private route ledger is persisted before delivery, allowing cold-start taps.
            if saved.routes.len() > 4096 {
                let mut order = saved.routes.iter().map(|(id,r)| (id.clone(),r.expires_at)).collect::<Vec<_>>();
                order.sort_by_key(|(_,expiry)| *expiry);
                for (id,_) in order.into_iter().take(saved.routes.len() - 4096) { saved.routes.remove(&id); }
            }
            storage::atomic_write(&self.path, &serde_json::to_vec(&*saved)?)?;
        }
        let mut result = Ok(());
        for (device,event) in targets {
            if let Err(error) = self.post("/send", json!({"deviceId":device,"eventId":event})).await { result = Err(error); }
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    fn service(path: PathBuf) -> PushDelivery {
        PushDelivery { path, saved: Mutex::new(Saved::default()), presence: Mutex::new(HashMap::new()), gateway: None, client: reqwest::Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none()).build().unwrap() }
    }
    async fn gateway(responses: Vec<(u16, Value)>) -> (String, tokio::sync::mpsc::UnboundedReceiver<Value>, tokio::task::JoinHandle<()>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        let task = tokio::spawn(async move {
            for (status, response) in responses {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut request = Vec::new();
                let header_end = loop {
                    request.push(stream.read_u8().await.unwrap());
                    if request.ends_with(b"\r\n\r\n") { break request.len(); }
                };
                let headers = String::from_utf8_lossy(&request).to_lowercase();
                let size: usize = headers.lines().find_map(|line| line.strip_prefix("content-length: ")).unwrap().parse().unwrap();
                request.resize(header_end + size, 0);
                stream.read_exact(&mut request[header_end..]).await.unwrap();
                let _ = tx.send(serde_json::from_slice(&request[header_end..]).unwrap());
                let body = response.to_string();
                let response = format!("HTTP/1.1 {status} Test\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
                stream.write_all(response.as_bytes()).await.unwrap();
            }
        });
        (origin, rx, task)
    }
    #[tokio::test]
    async fn routes_require_matching_device_and_do_not_expose_thread_without_authentication() {
        let directory = tempfile::tempdir().unwrap();
        let service = service(directory.path().join("push-routes.json"));
        let id = uuid::Uuid::new_v4().to_string();
        service.saved.lock().await.routes.insert(id.clone(), Route { device:"owner".into(), runtime:"r".into(), thread:"t".into(), expires_at:storage::now()+60 });
        assert!(service.resolve("other", &id).await.is_err());
        assert_eq!(service.resolve("owner", &id).await.unwrap()["threadId"], "t");
        service.unregister("owner").await.unwrap();
        assert!(service.resolve("owner", &id).await.is_err());
    }

    #[tokio::test]
    async fn routes_expire_at_the_exact_boundary() {
        let directory = tempfile::tempdir().unwrap();
        let service = service(directory.path().join("push-routes.json"));
        let event = uuid::Uuid::new_v4().to_string();
        service.saved.lock().await.routes.insert(event.clone(), Route { device: "owner".into(), runtime: "private-runtime".into(), thread: "private-thread".into(), expires_at: storage::now() });
        assert!(service.resolve("owner", &event).await.is_err());
    }

    #[tokio::test]
    async fn emit_suppresses_only_current_foreground_chat_and_authorized_devices() {
        let directory = tempfile::tempdir().unwrap();
        let mut service = service(directory.path().join("push-routes.json"));
        let (origin, mut requests, server) = gateway(vec![(202, json!({"status":"submitted"})); 3]).await;
        service.gateway = Some((origin, "fixture-secret".repeat(3)));
        service.saved.lock().await.devices = ["foreground", "other-thread", "background", "expired", "revoked"].map(str::to_owned).to_vec();
        service.presence("foreground", Some("private-thread".into()), true).await;
        service.presence("other-thread", Some("elsewhere".into()), true).await;
        service.presence("background", Some("private-thread".into()), false).await;
        service.presence.lock().await.insert("expired".into(), Presence { thread: Some("private-thread".into()), foreground: true, until: storage::now() });
        service.emit("private-runtime", "private-thread", &["foreground".into(), "other-thread".into(), "background".into(), "expired".into()]).await.unwrap();
        server.await.unwrap();
        for expected in ["other-thread", "background", "expired"] {
            let body = requests.recv().await.unwrap();
            assert_eq!(body["deviceId"], expected);
            assert_eq!(body.as_object().unwrap().len(), 2);
            assert!(uuid::Uuid::parse_str(body["eventId"].as_str().unwrap()).is_ok());
            assert!(!body.to_string().contains("private-"));
        }
        let saved: Saved = serde_json::from_slice(&std::fs::read(&service.path).unwrap()).unwrap();
        assert_eq!(saved.routes.len(), 3);
        assert!(saved.routes.values().all(|route| route.device != "revoked" && route.device != "foreground"));
    }

    #[tokio::test]
    async fn failed_recipient_does_not_prevent_other_authorized_deliveries() {
        let directory = tempfile::tempdir().unwrap();
        let mut service = service(directory.path().join("push-routes.json"));
        let (origin, mut requests, server) = gateway(vec![(502, json!({"error":"unconfirmed"})), (202, json!({"status":"submitted"}))]).await;
        service.gateway = Some((origin, "fixture-secret".repeat(3)));
        let devices = vec!["first".into(), "second".into()];
        service.saved.lock().await.devices = devices.clone();
        assert!(service.emit("runtime", "thread", &devices).await.is_err());
        assert_eq!(requests.recv().await.unwrap()["deviceId"], "first");
        assert_eq!(requests.try_recv().unwrap()["deviceId"], "second");
        server.await.unwrap();
    }

    #[tokio::test]
    async fn revocation_is_durable_even_when_gateway_fails() {
        let directory = tempfile::tempdir().unwrap();
        let mut service = service(directory.path().join("push-routes.json"));
        let (origin, _, server) = gateway(vec![(502, json!({"error":"fixture-secret"}))]).await;
        service.gateway = Some((origin, "fixture-secret".repeat(3)));
        let event = uuid::Uuid::new_v4().to_string();
        service.saved.lock().await.devices.push("owner".into());
        service.saved.lock().await.routes.insert(event.clone(), Route { device: "owner".into(), runtime: "runtime".into(), thread: "thread".into(), expires_at: storage::now() + 60 });
        service.presence("owner", Some("thread".into()), true).await;
        let error = service.unregister("owner").await.unwrap_err();
        assert!(!error.to_string().contains("fixture-secret"));
        assert!(service.resolve("owner", &event).await.is_err());
        assert!(!service.presence.lock().await.contains_key("owner"));
        let saved: Saved = serde_json::from_slice(&std::fs::read(&service.path).unwrap()).unwrap();
        assert!(saved.devices.is_empty() && saved.routes.is_empty());
        server.await.unwrap();
    }

    #[tokio::test]
    async fn failed_registration_persistence_does_not_grant_local_delivery_authority() {
        let directory = tempfile::tempdir().unwrap();
        let mut service = service(directory.path().to_owned());
        let (origin, _, server) = gateway(vec![(200, json!({"registered":true}))]).await;
        service.gateway = Some((origin, "fixture-secret".repeat(3)));
        let device = uuid::Uuid::new_v4().to_string();
        assert!(service.register(&device, "fixture-token").await.is_err());
        assert!(service.saved.lock().await.devices.is_empty());
        server.await.unwrap();
    }
}
