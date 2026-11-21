use super::*;
use anyhow::ensure;

impl Broker {
    pub(super) async fn restore_desktop_followers(&self) {
        #[cfg(windows)] {
            let saved={let state=self.state.lock().await;
                state.ownership.iter().filter(|(id,claim)|id.starts_with("desktop-follower-")&&claim.native_process.as_ref().is_some_and(|p|!p.terminated())&&
                    state.runtimes.get(*id).is_none_or(|r|!r.rpc.online()))
                    .flat_map(|(_,c)|c.advertisement.loaded_native_thread_ids.iter().map(|thread|(c.advertisement.config_store_id.clone(),thread.clone())))
                    .take(32).collect::<Vec<_>>()};
            for (store,thread) in saved {let _=self.desktop_control(&json!({"configStoreId":store,"threadId":thread})).await;}
        }
    }
    /// A read-only follower subscription to the current owner, never a resume.
    pub(super) async fn desktop_control(&self,p:&Value)->Result<Value> {
        let _attachment=self.desktop_attach.lock().await;
        ensure!(p.as_object().is_some_and(|o|o.keys().all(|k|matches!(k.as_str(),"computerId"|"configStoreId"|"threadId"))),"Invalid control lookup");
        let store=field(p,"configStoreId")?;let thread=field(p,"threadId")?;
        ensure!(crate::settings::valid_thread(thread),"Invalid thread");
        ensure!(p["computerId"].as_str().is_none_or(|id|id==self.config.computer_id),"Computer mismatch");
        let home=self.history_homes().await.remove(store).context("Unknown profile")?;
        ensure!(!self.coordinator_visibility()?.internal(store,Some(thread)),"Private assistant thread");
        let key=ThreadKey{computer_id:self.config.computer_id.clone(),config_store_id:store.into(),native_thread_id:thread.into()};
        let existing={let state=self.state.lock().await;self.directory(&state)?.resolve_write(&key).ok().map(|o|o.runtime_id)};
        if let Some(runtime)=existing {
            return Ok(json!({"runtimeId":runtime,"runtimes":self.public_runtime_values(&*self.state.lock().await)}))
        }
        #[cfg(not(windows))] {let _=home;return Ok(json!({"runtimeId":null,"reason":"desktop_control_unavailable"}));}
        #[cfg(windows)] {
            let catalog=self.state.lock().await.runtimes.values().find(|r|r.store==store&&matches!(r.role,native::NativeRole::CliDaemon)&&r.rpc.online()).map(|r|r.rpc.clone());
            let connected=match tokio::time::timeout(Duration::from_secs(12),native::desktop_ipc::Client::connect(&home,thread,catalog,self.native_events.clone())).await {
                Ok(Ok(c))=>c,
                _=>return Ok(json!({"runtimeId":null,"reason":"desktop_owner_unavailable"})),
            };
            let runtime=connected.runtime_id.clone();
            self.add_runtime(connected).await?;
            {
                let mut state=self.state.lock().await;
                let r=state.runtimes.get_mut(&runtime).context("Desktop runtime disappeared")?;
                r.loaded.insert(thread.into());r.inventory_ready=true;r.inventory_revision=uuid::Uuid::new_v4();
                self.save_ownership(&mut state)?;
                self.directory(&state)?.resolve_write(&key).map_err(|e|anyhow::anyhow!("Control owner ambiguous: {e:?}"))?;
                let _=self.notifications.send(json!({"method":"runtimes/changed","params":{"data":self.public_runtime_values(&state)}}));
            }
            Ok(json!({"runtimeId":runtime,"runtimes":self.public_runtime_values(&*self.state.lock().await)}))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn control_lookup_rejects_foreign_computer_and_private_profile_before_connecting() {
        let (dir,b)=super::super::tests::fixture().await;
        let store=storage::config_store(dir.path()).unwrap();
        assert!(b.desktop_control(&json!({"computerId":"foreign","configStoreId":store,"threadId":"t"})).await.is_err());
        assert!(b.desktop_control(&json!({"configStoreId":"unknown","threadId":"t"})).await.is_err());
        assert!(b.desktop_control(&json!({"configStoreId":store,"threadId":"t","command":"resume"})).await.is_err());
    }
}
