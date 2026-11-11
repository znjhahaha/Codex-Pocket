//! Product signaling path over the existing TLS session, then original pin + device hello.
use std::{sync::Arc,time::Duration};
use pocket_agent::{auth::DeviceStore,broker::Broker,config::Config,storage};
use pocket_p2p::{DataChannelStream,Signal};
use pocket_tunnel::{ServerIdentity,read_json,write_json,pinned_client_config};
use serde_json::{Value,json};
use tokio::io::{AsyncRead,AsyncWrite};

async fn request<S:AsyncRead+AsyncWrite+Unpin>(s:&mut S,method:&str,params:Value)->Value {
    let id=uuid::Uuid::new_v4().to_string();
    write_json(s,&json!({"id":id,"method":method,"params":params})).await.unwrap();
    loop {
        let value:Value=tokio::time::timeout(Duration::from_secs(5),read_json(s)).await.unwrap().unwrap().unwrap();
        if value["id"]==id{return value;}
    }
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
#[ignore="requires built native adapter; set P2P_NATIVE_DIR, no public network/model"]
async fn authenticated_signaling_and_pinned_peer_survive_old_route_close() {
    assert!(pocket_p2p::available(),"adapter required for this explicit fixture");
    let root=tempfile::tempdir().unwrap();
    let identity=Arc::new(ServerIdentity::load_or_create(root.path().join("identity")).unwrap());
    let state=root.path().join("agent");storage::private_directory(&state).unwrap();
    let mut devices=DeviceStore::default();devices.add("phone".into(),"Fixture".into(),"secret",1).unwrap();
    storage::atomic_write(&state.join("devices.json"),&serde_json::to_vec(&devices).unwrap()).unwrap();
    let config=Config::create("wss://fixture.invalid/pocket".into()).unwrap();
    let computer=config.computer_id.clone();
    let (broker,_)=Broker::open(config,state).unwrap();
    let (a,b)=tokio::io::duplex(131072);
    let server=tokio::spawn({let identity=identity.clone();async move {
        let tls=identity.accept_tls(b).await.unwrap();
        pocket_agent::session::serve_with_identity(tls,broker,identity).await
    }});
    let connector=tokio_rustls::TlsConnector::from(Arc::new(pinned_client_config(identity.certificate_der(),&identity.certificate_sha256()).unwrap()));
    let mut old=connector.connect("pocket.local".try_into().unwrap(),a).await.unwrap();
    assert!(request(&mut old,"p2p/start",json!({})).await.get("error").is_some());
    let hello=json!({"protocol":1,"deviceId":"phone","deviceSecret":"secret","capabilities":["p2p.v1"]});
    let response=request(&mut old,"hello",hello.clone()).await;
    assert!(response["result"]["capabilities"].as_array().unwrap().contains(&json!("p2p.v1")));
    let id=uuid::Uuid::new_v4().to_string();let generation=9;
    let envelope=json!({"negotiationId":id,"networkGeneration":generation});
    let start=json!({"negotiationId":id,"networkGeneration":generation,"useStun":false});
    assert_eq!(request(&mut old,"p2p/start",start.clone()).await["result"]["negotiationId"],id);
    assert_eq!(request(&mut old,"p2p/start",start).await["result"]["negotiationId"],id);
    assert!(request(&mut old,"p2p/poll",json!({"negotiationId":id,"networkGeneration":8})).await.get("error").is_some());
    let (tx,mut rx)=tokio::sync::mpsc::channel(256);
    let (stream,control)=DataChannelStream::new(generation,None,move |v|{tx.try_send(v).unwrap();}).unwrap();
    control.start(false).unwrap();
    let connection=tokio::spawn(async move {connector.connect("pocket.local".try_into().unwrap(),stream).await.unwrap()});
    tokio::time::timeout(Duration::from_secs(15),async {
        while !connection.is_finished() {
            while let Ok(signal)=rx.try_recv(){
                let signal=match signal {
                    Signal::Description{sdp,kind,..}=>json!({"kind":"description","sdp":sdp,"type":kind}),
                    Signal::Candidate{candidate,mid,..}=>json!({"kind":"candidate","candidate":candidate,"mid":mid}),
                };
                let mut p=envelope.clone();p["signals"]=json!([signal]);
                assert!(request(&mut old,"p2p/signal",p).await.get("result").is_some());
            }
            let reply=request(&mut old,"p2p/poll",envelope.clone()).await;
            for signal in reply["result"]["signals"].as_array().expect("signals") {
                match signal["kind"].as_str().unwrap(){
                    "description"=>control.remote_description(generation,signal["sdp"].as_str().unwrap(),signal["type"].as_str().unwrap()).unwrap(),
                    "candidate"=>control.remote_candidate(generation,signal["candidate"].as_str().unwrap(),signal["mid"].as_str().unwrap()).unwrap(),_=>panic!(),
                }
            }
        }
    }).await.unwrap();
    let mut direct=connection.await.unwrap();
    let mut direct_hello=hello;direct_hello["p2pNetworkGeneration"]=json!(generation);direct_hello["p2pNegotiationId"]=json!("wrong");
    assert_eq!(request(&mut direct,"hello",direct_hello.clone()).await["error"]["code"],"unauthorized");
    direct_hello["p2pNegotiationId"]=json!(id);
    assert_eq!(request(&mut direct,"hello",direct_hello).await["result"]["computerId"],computer);
    drop(old);
    assert!(request(&mut direct,"ping",json!({})).await.get("result").is_some());
    assert!(request(&mut direct,"sync",json!({"cursors":{}})).await.get("result").is_some());
    assert!(request(&mut direct,"p2p/start",json!({})).await.get("error").is_some());
    drop(direct);control.close(false);server.abort();
}
