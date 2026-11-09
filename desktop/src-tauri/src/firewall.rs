use crate::commands::{Context,preflight,fail,join_error};
use pocket_desktop_runtime::{connection::OperationError,launcher,process,storage};
use anyhow::{Result,bail};
use base64::Engine;
use serde_json::{Value,json};
use std::{net::{IpAddr,SocketAddr},path::Path,process::Command};
use tauri::State;

fn literal(value:&str)->String{format!("'{}'",value.replace('\'',"''"))}
fn encoded(script:&str)->String{base64::engine::general_purpose::STANDARD.encode(script.encode_utf16().flat_map(u16::to_le_bytes).collect::<Vec<_>>())}

// Compiled template: no script, program, profile, subnet, or port supplied by the webview.
fn rule_script(program:&Path,sha256:&str,bind:&str,computer_id:&str)->Result<String>{
    let address:SocketAddr=bind.parse()?;
    let private=match address.ip(){IpAddr::V4(ip)=>ip.is_private(),IpAddr::V6(ip)=>ip.segments()[0]&0xfe00==0xfc00};
    if !private||address.port()==0{bail!("A configured private LAN listener is required")}
    if program.file_name().and_then(|s|s.to_str())!=Some("pocket-agent.exe")||!program.is_absolute()||program.to_string_lossy().chars().any(char::is_control){bail!("Invalid verified agent path")}
    if sha256.len()!=64||!sha256.bytes().all(|b|b.is_ascii_hexdigit())||computer_id.is_empty()||computer_id.len()>64||!computer_id.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'){bail!("Invalid installed identity")}
    let script=r#"$ErrorActionPreference='Stop'
try {
 Import-Module "$env:SystemRoot\System32\WindowsPowerShell\v1.0\Modules\NetSecurity\NetSecurity.psd1" -ErrorAction Stop
 $program=__PROGRAM__
 if ((Get-FileHash -LiteralPath $program -Algorithm SHA256).Hash -ne '__HASH__') { throw 'Agent integrity changed' }
 $name='CodexPocket-LAN-__ID__'
 $rule=Get-NetFirewallRule -PolicyStore PersistentStore -Name $name -ErrorAction SilentlyContinue
 if ($rule -and (@($rule).Count -ne 1 -or $rule.Group -ne 'Codex Pocket LAN')) { throw 'Foreign firewall rule' }
 $parameters=@{Profile='Private';Direction='Inbound';Action='Allow';Enabled='True';Protocol='TCP';LocalAddress='__IP__';LocalPort=__PORT__;RemoteAddress='LocalSubnet';Program=$program;EdgeTraversalPolicy='Block'}
 if ($rule) { $rule | Set-NetFirewallRule @parameters | Out-Null }
 else { New-NetFirewallRule -PolicyStore PersistentStore -Name $name -DisplayName 'Codex Pocket - private LAN only' -Group 'Codex Pocket LAN' @parameters | Out-Null }
 $rule=Get-NetFirewallRule -PolicyStore PersistentStore -Name $name
 $app=$rule | Get-NetFirewallApplicationFilter
 $port=$rule | Get-NetFirewallPortFilter
 $address=$rule | Get-NetFirewallAddressFilter
 if ([string]$rule.Profile -ne 'Private' -or [string]$rule.Direction -ne 'Inbound' -or [string]$rule.Action -ne 'Allow' -or [string]$rule.Enabled -ne 'True' -or $app.Program -ne $program -or [string]$port.Protocol -notin @('TCP','6') -or [string]$port.LocalPort -ne '__PORT__' -or [string]$address.LocalAddress -ne '__IP__' -or [string]$address.RemoteAddress -ne 'LocalSubnet') { throw 'Firewall verification failed' }
 exit 0
} catch { exit 1 }
"#;
    Ok(script.replace("__HASH__",sha256).replace("__ID__",computer_id).replace("__IP__",&address.ip().to_string()).replace("__PORT__",&address.port().to_string()).replace("__PROGRAM__",&literal(&program.to_string_lossy())))
}

#[tauri::command]
pub async fn allow_lan_firewall(context:State<'_,Context>)->Result<Value,OperationError>{
    let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{
        let _operation=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;
        preflight(&c)?;
        let dir=storage::control(&c.state);storage::private_dir(&dir).map_err(|e|fail("firewall",e))?;
        let _routes=storage::Lock::acquire(&dir.join("routes.lock")).map_err(|e|fail("firewall",e))?;
        let program=launcher::verified_exe(&c.bin,"pocket-agent.exe").map_err(|e|fail("installation",e))?;
        let report=process::agent(&program,&c.state,"routes/read",&[]).map_err(|e|fail("routes",e))?;
        let config:Value=storage::read(&c.state.join("config.json")).map_err(|e|fail("firewall",e))?;
        let manifest:Value=storage::read(&c.bin.join("windows-manifest.json")).map_err(|e|fail("installation",e))?;
        let hash=manifest["files"].as_array().and_then(|rows|rows.iter().find(|v|v["path"]=="pocket-agent.exe")).and_then(|v|v["sha256"].as_str()).unwrap_or("");
        let bind=report["routePreferences"]["lan"]["bind"].as_str().unwrap_or("");
        let script=rule_script(&program,hash,bind,config["computerId"].as_str().unwrap_or("")).map_err(|e|fail("firewall",e))?;
        let powershell=storage::powershell();
        let wrapper=format!("$ErrorActionPreference='Stop';try{{$p=Start-Process -FilePath {} -Verb RunAs -WindowStyle Hidden -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand','{}') -PassThru;if(-not $p.WaitForExit(60000)){{exit 124}};exit $p.ExitCode}}catch{{exit 1}}",literal(&powershell.to_string_lossy()),encoded(&script));
        let mut command=Command::new(powershell);command.args(["-NoProfile","-NonInteractive","-EncodedCommand",&encoded(&wrapper)]);
        process::run_bounded(command,90).map_err(|_|OperationError{code:"firewall_not_configured".into(),stage:"firewall".into(),message:"防火墙未配置：授权已取消、等待超时或规则创建失败。可重试；不会改动公共网络规则。".into(),..OperationError::new("firewall","firewall_not_configured",true)})?;
        Ok(json!({"configured":true,"bind":bind,"profile":"Private","remoteAddress":"LocalSubnet"}))
    }).await.map_err(join_error)?
}

#[cfg(test)]mod tests{
    use super::*;
    #[test]fn rule_is_limited_to_verified_program_port_address_and_private_subnet(){
        let script=rule_script(Path::new("C:/Pocket O'Brien/pocket-agent.exe"),&"a".repeat(64),"192.168.1.7:17891","fixture-computer").unwrap();
        assert!(script.contains("'C:/Pocket O''Brien/pocket-agent.exe'"));
        for bound in ["Profile='Private'","RemoteAddress='LocalSubnet'","LocalPort=17891","LocalAddress='192.168.1.7'","Program=$program","Protocol='TCP'","Get-FileHash","Get-NetFirewallApplicationFilter"]{assert!(script.contains(bound),"{bound}");}
        assert!(!script.contains("Profile='Public'"));assert!(!script.contains("Program='Any'"));
    }
    #[test]fn unsafe_or_missing_listener_cannot_generate_an_elevated_rule(){
        for bind in ["","0.0.0.0:17891","127.0.0.1:17891","8.8.8.8:17891","192.168.1.1:0","[::]:17891","192.168.1.1:17891;whoami"]{assert!(rule_script(Path::new("C:/Pocket/pocket-agent.exe"),&"a".repeat(64),bind,"fixture").is_err(),"{bind}");}
        assert!(rule_script(Path::new("C:/Pocket/cmd.exe"),&"a".repeat(64),"192.168.1.1:17891","fixture").is_err());
        assert!(rule_script(Path::new("C:/Pocket/pocket-agent.exe"),"invalid","192.168.1.1:17891","fixture").is_err());
    }
    #[test]fn generated_script_parses_without_executing_firewall_commands(){
        let script=rule_script(Path::new("C:/Pocket/$literal ' path/pocket-agent.exe"),&"a".repeat(64),"192.168.1.7:17891","fixture").unwrap();
        let check=format!("$source=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('{}'));$t=$null;$e=$null;[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$t,[ref]$e);if($e.Count){{exit 1}}",encoded(&script));
        let mut command=Command::new(storage::powershell());command.args(["-NoProfile","-NonInteractive","-EncodedCommand",&encoded(&check)]);
        process::run_bounded(command,10).unwrap();
    }
}
