//! Read-only catalog fallback from the verified desktop package and original profile.
//! This child cannot receive session operations and is reaped after the read.
use super::*;

pub(super) async fn read(binary:&Path,home:&Path,method:&str,params:Value)->Result<Value> {
    ensure!(matches!(method,"model/list"|"collaborationMode/list"),"Not a catalog read");
    tokio::time::timeout(Duration::from_secs(15),async {
        let profile=crate::managed_runtime::SelectedProfile::new(home,binary)?;
        let mut child=tokio::process::Command::new(profile.native_binary())
            .arg("app-server").env("CODEX_HOME",profile.config_home()).env_remove("CODEX_CLI_PATH")
            .creation_flags(0x08000000).kill_on_drop(true)
            .stdin(std::process::Stdio::piped()).stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::null()).spawn()?;
        let mut writer=child.stdin.take().context("Missing catalog input")?;
        let mut reader=BufReader::new(child.stdout.take().context("Missing catalog output")?);
        async fn request(writer:&mut tokio::process::ChildStdin,reader:&mut BufReader<tokio::process::ChildStdout>,id:&str,method:&str,params:Value)->Result<Value> {
            writer.write_all(format!("{}\n",json!({"id":id,"method":method,"params":params})).as_bytes()).await?;
            loop {
                let mut bytes=Vec::new();
                (&mut *reader).take(MAX_FRAME as u64+1).read_until(b'\n',&mut bytes).await?;
                ensure!(!bytes.is_empty()&&bytes.len()<=MAX_FRAME,"Invalid catalog response");
                let value:Value=serde_json::from_slice(&bytes)?;
                if value["id"]==id {
                    ensure!(value.get("error").is_none(),"Catalog read rejected: {}",value["error"]["message"]);
                    return Ok(value["result"].clone())
                }
            }
        }
        let result=async {
            request(&mut writer,&mut reader,"catalog-init","initialize",json!({"clientInfo":{"name":"codex_pocket_catalog","version":env!("CARGO_PKG_VERSION")},"capabilities":{"experimentalApi":true}})).await?;
            writer.write_all(b"{\"method\":\"initialized\",\"params\":{}}\n").await?;
            request(&mut writer,&mut reader,"catalog-read",method,params).await
        }.await;
        drop(writer);
        let _=child.kill().await;
        let _=child.wait().await;
        result
    }).await.context("Catalog read timed out")?
}
