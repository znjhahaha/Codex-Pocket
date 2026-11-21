//! Package-free runtime registration. The transparent proxy writes no files.
//! A current-user pipe carries its live identity to the unpackaged background.
use anyhow::{Context, Result, ensure};
use pocket_core::native_process::NativeProcess;
use serde_json::Value;
use std::{os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle}, path::PathBuf, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::windows::named_pipe::{ClientOptions, NamedPipeServer, ServerOptions}};
use windows_sys::Win32::{Foundation::*, Security::{Authorization::*, *}, System::{Pipes::*, Threading::*}};

const MAX_RECORD: usize = 16384;

fn process_sid(pid: u32) -> Result<String> {
    unsafe {
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        ensure!(!process.is_null(), "Registry peer unavailable");
        let _process = OwnedHandle::from_raw_handle(process);
        let mut token = std::ptr::null_mut();
        ensure!(OpenProcessToken(process, TOKEN_QUERY, &mut token) != 0, "Registry peer token unavailable");
        let _token = OwnedHandle::from_raw_handle(token);
        let mut size = 0;
        GetTokenInformation(token, TokenUser, std::ptr::null_mut(), 0, &mut size);
        ensure!(size > 0 && size < 65536, "Invalid token size");
        let mut buffer = vec![0usize; (size as usize).div_ceil(size_of::<usize>())];
        ensure!(GetTokenInformation(token, TokenUser, buffer.as_mut_ptr().cast(), size, &mut size) != 0, "Registry peer identity unavailable");
        let sid = (*(buffer.as_ptr().cast::<TOKEN_USER>())).User.Sid;
        let mut raw = std::ptr::null_mut();
        ensure!(ConvertSidToStringSidW(sid, &mut raw) != 0, "Registry peer SID unavailable");
        let mut len = 0;
        while *raw.add(len) != 0 { len += 1; }
        let text = String::from_utf16_lossy(std::slice::from_raw_parts(raw, len));
        LocalFree(raw.cast());
        Ok(text)
    }
}

pub fn pipe_name() -> Result<String> {
    Ok(format!(r"\\.\pipe\CodexPocket.NativeRegistry.v1.{}", process_sid(std::process::id())?))
}

fn create_pipe(name: &str, first: bool) -> Result<NamedPipeServer> {
    let sid = process_sid(std::process::id())?;
    let sddl: Vec<u16> = format!("O:{sid}D:P(A;;GA;;;{sid})").encode_utf16().chain([0]).collect();
    unsafe {
        let mut descriptor = std::ptr::null_mut();
        ensure!(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.as_ptr(), SDDL_REVISION_1, &mut descriptor, std::ptr::null_mut()) != 0, "Registry ACL unavailable");
        let attributes = SECURITY_ATTRIBUTES { nLength: size_of::<SECURITY_ATTRIBUTES>() as u32, lpSecurityDescriptor: descriptor, bInheritHandle: 0 };
        let result = ServerOptions::new().first_pipe_instance(first).reject_remote_clients(true)
            .create_with_security_attributes_raw(name, (&attributes as *const SECURITY_ATTRIBUTES).cast_mut().cast());
        LocalFree(descriptor);
        Ok(result?)
    }
}

pub struct Registry(tokio::task::JoinHandle<()>);
impl Drop for Registry { fn drop(&mut self) { self.0.abort(); } }
impl Registry {
    pub fn start(directory: PathBuf) -> Result<Self> { Self::start_at(pipe_name()?, directory) }
    pub fn start_at(pipe: String, directory: PathBuf) -> Result<Self> {
        let mut server = create_pipe(&pipe, true)?;
        Ok(Self(tokio::spawn(async move {
            let mut clients = tokio::task::JoinSet::new();
            loop {
                tokio::select! {
                    connected = server.connect() => {
                        if connected.is_err() { break; }
                        let next = match create_pipe(&pipe, false) { Ok(v) => v, Err(_) => break };
                        let client = std::mem::replace(&mut server, next);
                        let directory = directory.clone();
                        if clients.len() < 64 { clients.spawn(async move { let _ = register(client, directory).await; }); }
                    }
                    Some(_) = clients.join_next(), if !clients.is_empty() => {}
                }
            }
        })))
    }
}

struct Lease { path: PathBuf, id: String }
impl Drop for Lease {
    fn drop(&mut self) {
        // A late disconnect must not erase a descriptor renewed by a successor.
        if std::fs::read(&self.path).ok().and_then(|b|serde_json::from_slice::<Value>(&b).ok())
            .is_some_and(|v|v["registryLease"]==self.id) { let _ = std::fs::remove_file(&self.path); }
    }
}

async fn register(mut pipe: NamedPipeServer, directory: PathBuf) -> Result<()> {
    let mut pid = 0;
    ensure!(unsafe { GetNamedPipeClientProcessId(pipe.as_raw_handle(), &mut pid) } != 0, "Registry client identity unavailable");
    ensure!(process_sid(pid)? == process_sid(std::process::id())?, "Registry user mismatch");
    let expected = NativeProcess::capture(pid)?;
    let mut value: Value = tokio::time::timeout(Duration::from_secs(3), async {
        let len = pipe.read_u32_le().await? as usize;
        ensure!(len > 0 && len <= MAX_RECORD, "Registry record too large");
        let mut bytes = vec![0;len]; pipe.read_exact(&mut bytes).await?;
        Ok::<Value, anyhow::Error>(serde_json::from_slice(&bytes)?)
    }).await??;
    let id = value["runtimeId"].as_str().context("Registry runtime missing")?.to_owned();
    ensure!(uuid::Uuid::parse_str(&id)?.to_string()==id && value["protocol"]==1 && value["pid"]==pid, "Invalid registry identity");
    ensure!(serde_json::from_value::<NativeProcess>(value["proxyProcess"].clone())? == expected, "Registry process mismatch");
    let native: NativeProcess = serde_json::from_value(value["nativeProcess"].clone())?;
    ensure!(NativeProcess::capture(native.pid)? == native && process_sid(native.pid)? == process_sid(pid)?, "Registry native changed");
    ensure!(value["port"].as_u64().is_some_and(|p|p > 0 && p <= 65535), "Invalid registry port");
    ensure!(value["token"].as_str().is_some_and(|t|t.len() >= 32 && t.len() <= 256 && t.is_ascii()), "Invalid registry token");
    for key in ["configHome", "binary"] {
        ensure!(value[key].as_str().is_some_and(|p|std::path::Path::new(p).is_absolute()), "Invalid registry path");
    }
    let lease = Lease { path: directory.join(format!("{id}.json")), id: uuid::Uuid::new_v4().to_string() };
    value["registryLease"] = Value::String(lease.id.clone());
    crate::descriptor::publish(&directory, &lease.path, &serde_json::to_vec(&value)?)?;
    pipe.write_u8(1).await?;
    // Lifetime follows the actual pipe peer, not a heartbeat timeout.
    let mut byte = [0];
    let _ = pipe.read(&mut byte).await;
    drop(lease);
    Ok(())
}

pub struct Publication(tokio::task::JoinHandle<()>);
impl Drop for Publication { fn drop(&mut self) { self.0.abort(); } }
pub fn publish_until_closed(name: String, record: Value) -> Publication {
    Publication(tokio::spawn(async move {
        let Ok(bytes) = serde_json::to_vec(&record) else { return };
        if bytes.len() > MAX_RECORD { return; }
        loop {
            let attempt = async {
                let mut client = ClientOptions::new().open(&name)?;
                let mut pid = 0;
                ensure!(unsafe{ GetNamedPipeServerProcessId(client.as_raw_handle(), &mut pid) }!=0 && process_sid(pid)?==process_sid(std::process::id())?, "Registry server user mismatch");
                tokio::time::timeout(Duration::from_secs(3), async {
                    client.write_u32_le(bytes.len() as u32).await?;
                    client.write_all(&bytes).await?;
                    ensure!(client.read_u8().await? == 1, "Registry rejected proxy");
                    Ok::<(),anyhow::Error>(())
                }).await??;
                let mut byte = [0];
                let _ = client.read(&mut byte).await?;
                Ok::<(),anyhow::Error>(())
            };
            let _ = attempt.await;
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
    }))
}
