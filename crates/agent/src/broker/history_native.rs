//! Read native history without acquiring execution ownership or resuming a thread.
use super::*;
use anyhow::ensure;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};

const PREFIX: &str = "native-history:";

impl Broker {
    pub(super) async fn native_history_page(&self, store: &str, thread: &str, cursor: Option<&str>, limit: usize) -> Result<Option<Value>> {
        let bound = cursor.filter(|c| c.starts_with(PREFIX)).map(|c| -> Result<Value> {
            ensure!(c.len() < 16384, "History cursor too large");
            let v: Value=serde_json::from_slice(&URL_SAFE_NO_PAD.decode(&c[PREFIX.len()..])?)?;
            ensure!(v["store"]==store && v["thread"]==thread && v["cursor"].is_string(), "History cursor does not match thread");
            Ok(v)
        }).transpose().map_err(|e|classify(e,Code::InvalidParams))?;
        if cursor.is_some() && bound.is_none() {return Ok(None)} // Keep disk paging on its original source.
        let read=json!({"threadId":thread,"includeTurns":false});
        let mut page=json!({"threadId":thread,"limit":limit,"sortDirection":"desc","itemsView":"full"});
        if let Some(b)=&bound {page["cursor"]=b["cursor"].clone();}
        let candidate={let s=self.state.lock().await;
            s.runtimes.iter().filter(|(id,r)|r.store==store&&r.rpc.online()&&
                !matches!(r.role,native::NativeRole::ManagedHost{..}|native::NativeRole::DesktopFollower)&&
                bound.as_ref().is_none_or(|b| b["runtime"]==id.as_str()&&b["generation"]==r.connection_id)&&
                r.capabilities.supports("thread/read",&read)&&r.capabilities.supports("thread/turns/list",&page))
                // An unloaded reader can project another process's live turn as
                // interrupted. Read the loaded execution owner before a catalog.
                .min_by_key(|(_,r)|(!r.loaded.contains(thread),r.source!="cli"))
                .map(|(id,r)|(id.clone(),r.connection_id.clone(),r.rpc.clone()))};
        let Some((runtime,generation,rpc))=candidate else {
            if bound.is_some(){return Err(classify(anyhow::anyhow!("History reader disconnected"),Code::RuntimeOffline))}
            return Ok(None)
        };
        let result=tokio::time::timeout(Duration::from_secs(8),async {
            let metadata=rpc.call("thread/read",read).await?;
            ensure!(metadata["thread"]["id"]==thread,"Native history identity mismatch");
            let rows=rpc.call("thread/turns/list",page).await?;
            ensure!(rows["data"].is_array(),"Native history page missing");
            self.check_generation(&runtime,&generation).await?;
            let next=rows["nextCursor"].as_str().map(|c|format!("{PREFIX}{}",URL_SAFE_NO_PAD.encode(json!({
                "store":store,"thread":thread,"runtime":runtime,"generation":generation,"cursor":c}).to_string())));
            let empty=rows["data"].as_array().unwrap().is_empty()&&cursor.is_none();
            Ok::<_,anyhow::Error>(json!({"thread":metadata["thread"],"data":rows["data"],"nextCursor":next,
                "historyState":if empty{"empty"}else{"ready"},"readOnly":true,"readSource":"nativeHistory","historyEpoch":generation}))
        }).await;
        match result {
            Ok(Ok(v))=>Ok(Some(v)),
            _ if bound.is_none()=>Ok(None), // A first page can fall back to verified on-disk history.
            Ok(Err(e))=>Err(classify(e,Code::NativeHistoryUnavailable)),
            Err(e)=>Err(classify(e.into(),Code::RequestTimeout)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::broker::tests::{fixture,peer};
    #[tokio::test]
    async fn live_desktop_history_wins_over_unloaded_cli_projection() {
        let (_dir,b)=fixture().await;
        let mut cli=peer(&b,"cli","cli").await;
        let mut desktop=peer(&b,"desktop","desktop").await;
        let store={let mut state=b.state.lock().await;
            for r in state.runtimes.values_mut() {
                r.capabilities.0.insert("thread/read".into(),["threadId","includeTurns"].map(str::to_owned).into_iter().collect());
                r.capabilities.0.insert("thread/turns/list".into(),["threadId","limit","sortDirection","itemsView"].map(str::to_owned).into_iter().collect());
            }
            let r=state.runtimes.get_mut("desktop").unwrap();r.loaded.insert("t".into());r.inventory_ready=true;r.store.clone()};
        let task=tokio::spawn({let b=b.clone();async move {b.native_history_page(&store,"t",None,2).await}});
        let read=tokio::time::timeout(Duration::from_secs(1),desktop.recv()).await.expect("read the live owner");
        assert_eq!(read["method"],"thread/read");
        desktop.reply(&read,json!({"thread":{"id":"t","status":{"type":"active"}}})).await;
        let page=desktop.recv().await;
        desktop.reply(&page,json!({"data":[{"id":"current","status":"inProgress","items":[]}],"nextCursor":null})).await;
        let result=task.await.unwrap().unwrap().unwrap();
        assert_eq!(result["data"][0]["status"],"inProgress");
        cli.assert_idle().await;
    }
}
