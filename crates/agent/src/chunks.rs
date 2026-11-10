//! Negotiated reply chunks; ordinary protocol-1 peers keep complete replies.
use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::{Value, json};
pub const CAPABILITY: &str = "transport.chunkedReplies.v1";
pub const CHUNK_BYTES: usize = 16 * 1024;
pub const MAX_REPLY_BYTES: usize = 16 * 1024 * 1024;
pub const QUEUE_BYTES: usize = 32 * 1024 * 1024;

pub struct ReplyChunks { id: Value, bytes: Vec<u8>, offset: usize }
impl ReplyChunks {
    pub fn new(reply: &Value) -> anyhow::Result<Self> {
        anyhow::ensure!(reply["id"].as_str().is_some_and(|id| !id.is_empty()&&id.len()<=128), "Invalid reply ID");
        let bytes=serde_json::to_vec(reply)?;
        anyhow::ensure!(bytes.len()<=MAX_REPLY_BYTES,"Reply exceeds chunk limit");
        Ok(Self{id:reply["id"].clone(),bytes,offset:0})
    }
    pub fn bytes(&self)->usize{self.bytes.len()}
    pub fn next_frame(&mut self)->Option<Value>{
        if self.offset==self.bytes.len(){return None}
        let end=(self.offset+CHUNK_BYTES).min(self.bytes.len());
        let frame=json!({"method":"pocket/replyChunk","params":{"replyId":self.id,"sequence":self.offset/CHUNK_BYTES,"totalBytes":self.bytes.len(),"data":STANDARD.encode(&self.bytes[self.offset..end])}});
        self.offset=end;Some(frame)
    }
    pub fn finished(&self)->bool{self.offset==self.bytes.len()}
}

#[cfg(test)]mod tests {
    use super::*;
    #[test]fn chunks_reassemble_unicode_and_keep_reply_identity(){
        let reply=json!({"id":"r","result":{"text":"测试🚀".repeat(8000)}});
        let mut chunks=ReplyChunks::new(&reply).unwrap();let mut bytes=vec![];let mut sequence=0;
        while let Some(frame)=chunks.next_frame(){let p=&frame["params"];assert_eq!(p["replyId"],"r");assert_eq!(p["sequence"],sequence);let decoded=STANDARD.decode(p["data"].as_str().unwrap()).unwrap();assert!(decoded.len()<=CHUNK_BYTES);bytes.extend(decoded);sequence+=1;}
        assert!(sequence>1);assert!(chunks.finished());assert_eq!(serde_json::from_slice::<Value>(&bytes).unwrap(),reply);
    }
    #[test]fn oversized_or_unbound_reply_never_enters_queue(){
        assert!(ReplyChunks::new(&json!({"result":{}})).is_err());
        assert!(ReplyChunks::new(&json!({"id":"r","result":"x".repeat(MAX_REPLY_BYTES)})).is_err());
    }
}
