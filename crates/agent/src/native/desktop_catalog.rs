//! Read-only catalog fallback from the verified desktop package and original profile.
//! This child cannot receive session operations and is reaped after the read.
use super::*;
use anyhow::ensure;
use tokio::io::AsyncReadExt;
use tokio::sync::Mutex;

const MAX_FRAME: usize = 64 * 1024 * 1024;
const CACHE_TTL: Duration = Duration::from_secs(60);
type FileStamp = Option<(u64, std::time::SystemTime)>;
#[derive(Clone, PartialEq, Eq)]
struct CacheKey {
    home: PathBuf,
    binary: PathBuf,
    method: String,
    params: String,
    files: [FileStamp; 3],
}
struct Cached { at: tokio::time::Instant, result: Value }
type CacheSlot = Arc<Mutex<Option<Cached>>>;
static CACHE: std::sync::OnceLock<Mutex<Vec<(CacheKey, CacheSlot)>>> = std::sync::OnceLock::new();

fn stamp(path: &Path) -> FileStamp {
    let metadata = std::fs::metadata(path).ok()?;
    Some((metadata.len(), metadata.modified().ok()?))
}

pub(crate) async fn read_profile(home:&Path,method:&str,params:Value)->Result<Value> {
    let home=home.to_owned();
    let profile=tokio::task::spawn_blocking(move||crate::managed_runtime::discovery::for_home(&home)).await??;
    read(profile.native_binary(),profile.config_home(),method,params).await
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> &'static Path {
        static BIN: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();
        BIN.get_or_init(|| {
            let dir = tempfile::tempdir().unwrap().keep();
            let binary = dir.join("catalog-fixture.exe");
            let output = std::process::Command::new("rustc").arg("--edition=2024")
                .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("src/native/catalog_fixture.rs"))
                .arg("-o").arg(&binary).output().unwrap();
            assert!(output.status.success(),"{}",String::from_utf8_lossy(&output.stderr));
            binary
        })
    }
    #[tokio::test]
    async fn catalog_child_reuses_same_profile_page_and_preserves_pagination() {
        let home = tempfile::tempdir().unwrap();
        let params = json!({"limit":1});
        let first = read(fixture(),home.path(),"model/list",params.clone()).await.unwrap();
        let again = read(fixture(),home.path(),"model/list",params).await.unwrap();
        assert_eq!(first["data"],again["data"]);
        assert_eq!(first["nextCursor"],"second");
        let methods = std::fs::read_to_string(home.path().join("catalog-methods")).unwrap();
        assert_eq!(methods,"initialize\ninitialized\nmodel/list\n","same menu/page must not launch another child");
        let next = read(fixture(),home.path(),"model/list",json!({"limit":1,"cursor":"second"})).await.unwrap();
        assert!(next["nextCursor"].is_null());
        let pid: u32 = std::fs::read_to_string(home.path().join("catalog-pid")).unwrap().parse().unwrap();
        assert!(NativeProcess::capture(pid).map_or(true,|p|p.terminated()),"query child must be reaped");
        let other = tempfile::tempdir().unwrap();
        read(fixture(),other.path(),"model/list",json!({"limit":1})).await.unwrap();
        assert!(other.path().join("catalog-methods").is_file(),"profiles cannot share cached results");
        assert!(read(fixture(),home.path(),"thread/resume",json!({"threadId":"t"})).await.is_err());
        std::fs::write(home.path().join("config.toml"), "model_provider = 'changed'\n").unwrap();
        let refreshed=read(fixture(),home.path(),"model/list",json!({"limit":1})).await.unwrap();
        assert_eq!(refreshed["catalogCached"],false,"profile changes must invalidate catalog pages");
        let (one,two)=tokio::join!(
            read(fixture(),home.path(),"collaborationMode/list",json!({})),
            read(fixture(),home.path(),"collaborationMode/list",json!({})));
        assert_eq!(one.unwrap()["data"],two.unwrap()["data"]);
        assert_eq!(std::fs::read_to_string(home.path().join("catalog-methods")).unwrap().matches("collaborationMode/list").count(),1);
    }
    #[tokio::test]
    async fn catalog_child_timeout_reaps_only_its_query_process() {
        let home=tempfile::tempdir().unwrap();
        std::fs::write(home.path().join("catalog-hang"),"").unwrap();
        let profile=crate::managed_runtime::SelectedProfile::new(home.path(),fixture()).unwrap();
        let result=query(&profile,"model/list",json!({}),tokio::time::Instant::now()+Duration::from_secs(1)).await;
        assert!(result.is_err());
        let pid:u32=std::fs::read_to_string(home.path().join("catalog-pid")).unwrap().parse().unwrap();
        assert!(NativeProcess::capture(pid).map_or(true,|p|p.terminated()),"timed-out child must be reaped");
    }
}

pub(super) async fn read(binary:&Path,home:&Path,method:&str,params:Value)->Result<Value> {
    ensure!(matches!(method,"model/list"|"collaborationMode/list"),"Not a catalog read");
    crate::access::validate(method,&params)?;
    let profile=crate::managed_runtime::SelectedProfile::new(home,binary)?;
    let key=CacheKey{home:profile.config_home().into(),binary:profile.native_binary().into(),method:method.into(),params:params.to_string(),
        files:[stamp(profile.native_binary()),stamp(&profile.config_home().join("config.toml")),stamp(&profile.config_home().join("auth.json"))]};
    let slot={
        let mut cache=CACHE.get_or_init(||Mutex::new(Vec::new())).lock().await;
        if let Some((_,slot))=cache.iter().find(|(k,_)|k==&key){slot.clone()}
        else {
            if cache.len()==32 {cache.remove(0);}
            let slot=Arc::new(Mutex::new(None));cache.push((key,slot.clone()));slot
        }
    };
    let deadline=tokio::time::Instant::now()+Duration::from_secs(15);
    let mut cached=tokio::time::timeout_at(deadline,slot.lock()).await.context("Catalog read timed out")?;
    if let Some(value)=cached.as_ref().filter(|c|c.at.elapsed()<CACHE_TTL){
        let mut result=value.result.clone();result["catalogCached"]=json!(true);return Ok(result)
    }
    let mut result=query(&profile,method,params,deadline).await?;
    ensure!(result["data"].is_array(),"Invalid native catalog response");
    result["catalogCached"]=json!(false);
    result["catalogObservedAt"]=json!(crate::storage::now());
    // Bound the cache independently of the native frame limit. It contains only
    // catalog pages, never permission or thread snapshots.
    if serde_json::to_vec(&result)?.len()<=2*1024*1024 {
        *cached=Some(Cached{at:tokio::time::Instant::now(),result:result.clone()});
    }
    Ok(result)
}

async fn query(profile:&crate::managed_runtime::SelectedProfile,method:&str,params:Value,deadline:tokio::time::Instant)->Result<Value> {
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
        let result=tokio::time::timeout_at(deadline,async {
            request(&mut writer,&mut reader,"catalog-init","initialize",json!({"clientInfo":{"name":"codex_pocket_catalog","version":env!("CARGO_PKG_VERSION")},"capabilities":{"experimentalApi":true}})).await?;
            writer.write_all(b"{\"method\":\"initialized\",\"params\":{}}\n").await?;
            request(&mut writer,&mut reader,"catalog-read",method,params).await
        }).await.context("Catalog read timed out").and_then(|r|r);
        drop(writer);
        let _=child.kill().await;
        let _=child.wait().await;
        result
}
