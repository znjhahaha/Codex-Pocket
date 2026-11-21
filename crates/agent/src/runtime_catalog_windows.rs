//! Read-only discovery, independent of the bridge and ordinary launch commands.
use anyhow::{Context, Result, ensure};
use pocket_core::native_process::NativeProcess;
use serde_json::{Value, json};
use std::{
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    path::Path,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::windows::named_pipe::ClientOptions,
};
use windows_sys::Win32::{
    Foundation::{ERROR_NO_MORE_FILES, INVALID_HANDLE_VALUE},
    System::{
        Diagnostics::ToolHelp::{
            CreateToolhelp32Snapshot, PROCESSENTRY32W, Process32FirstW, Process32NextW,
            TH32CS_SNAPPROCESS,
        },
        Pipes::GetNamedPipeServerProcessId,
    },
};

fn same_path(a: &Path, b: &Path) -> bool {
    a.to_string_lossy()
        .replace('/', "\\")
        .eq_ignore_ascii_case(&b.to_string_lossy().replace('/', "\\"))
}

pub(super) async fn desktop() -> Option<String> {
    tokio::time::timeout(Duration::from_secs(2), desktop_connection())
        .await
        .ok()?
        .ok()
}

async fn desktop_connection() -> Result<String> {
    // Tokio opens with SECURITY_IDENTIFICATION. Do not allow a pipe endpoint to
    // impersonate the helper or send any data before its process is verified.
    let pipe = ClientOptions::new().open(r"\\.\pipe\codex-ipc")?;
    let mut pid = 0;
    if unsafe { GetNamedPipeServerProcessId(pipe.as_raw_handle(), &mut pid) } == 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let (identity, version) = tokio::task::spawn_blocking(move || -> Result<_> {
        let identity = NativeProcess::capture(pid)?;
        let image = crate::native::native_binary(pid)?;
        let packages = crate::managed_runtime::discovery::registered_codex_packages()?;
        let package = packages
            .iter()
            .find(|p| same_path(&image, &p.location.join("app/ChatGPT.exe")))
            .context("Pipe peer is not the registered Codex desktop")?;
        let version = package
            .full_name
            .split('_')
            .nth(1)
            .context("Missing package version")?
            .to_owned();
        ensure!(!identity.terminated(), "Desktop process identity changed");
        Ok((identity, version))
    })
    .await??;
    handshake(pipe).await?;
    ensure!(!identity.terminated(), "Desktop exited during handshake");
    Ok(version)
}

async fn handshake(mut io: impl AsyncReadExt + AsyncWriteExt + Unpin) -> Result<()> {
    let request_id = uuid::Uuid::new_v4().to_string();
    let request = serde_json::to_vec(
        &json!({"type":"request","sourceClientId":"initializing-client",
        "method":"initialize","version":0,"params":{"clientType":"codex-pocket"},"requestId":request_id}),
    )?;
    io.write_u32_le(request.len() as u32).await?;
    io.write_all(&request).await?;
    // Some clients broadcast as soon as a connection appears. Only a reply
    // correlated to this handshake can establish protocol compatibility.
    for _ in 0..16 {
        let len = io.read_u32_le().await? as usize;
        ensure!(
            (1..=1024 * 1024).contains(&len),
            "Unsupported desktop IPC frame size"
        );
        let mut bytes = vec![0; len];
        io.read_exact(&mut bytes).await?;
        let reply: Value = serde_json::from_slice(&bytes)?;
        if reply["type"] != "response" || reply["requestId"] != request_id {
            continue;
        }
        ensure!(
            reply["resultType"] == "success"
                && reply["result"]["clientId"]
                    .as_str()
                    .is_some_and(|id| !id.is_empty()),
            "Desktop IPC initialization rejected"
        );
        return Ok(());
    }
    anyhow::bail!("No desktop IPC initialization reply")
}

pub(super) fn cli_running() -> Option<bool> {
    let expected = crate::managed_runtime::discovery::cli_binary()?;
    let name = expected.file_name()?.to_str()?;
    let handle = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) };
    if handle == INVALID_HANDLE_VALUE {
        return None;
    }
    let _handle = unsafe { OwnedHandle::from_raw_handle(handle) };
    let mut row: PROCESSENTRY32W = unsafe { std::mem::zeroed() };
    row.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
    if unsafe { Process32FirstW(handle, &mut row) } == 0 {
        return None;
    }
    let mut inaccessible = false;
    loop {
        let size = row
            .szExeFile
            .iter()
            .position(|ch| *ch == 0)
            .unwrap_or(row.szExeFile.len());
        if String::from_utf16_lossy(&row.szExeFile[..size]).eq_ignore_ascii_case(name) {
            match crate::native::native_binary(row.th32ProcessID) {
                Ok(path) if same_path(&path, &expected) => return Some(true),
                Ok(_) => (), // The bundled desktop app-server is not a CLI session.
                Err(_) => inaccessible = true,
            }
        }
        if unsafe { Process32NextW(handle, &mut row) } == 0 {
            return (!inaccessible
                && std::io::Error::last_os_error().raw_os_error()
                    == Some(ERROR_NO_MORE_FILES as i32))
            .then_some(false);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn accepts_only_the_correlated_successful_handshake() {
        for accepts in [true, false] {
            let (client, mut fixture) = tokio::io::duplex(4096);
            let server = tokio::spawn(async move {
                let len = fixture.read_u32_le().await.unwrap() as usize;
                let mut bytes = vec![0; len];
                fixture.read_exact(&mut bytes).await.unwrap();
                let request: Value = serde_json::from_slice(&bytes).unwrap();
                assert_eq!(request["method"], "initialize");
                assert_eq!(request["params"], json!({"clientType":"codex-pocket"}));
                for request_id in [json!("unrelated"), request["requestId"].clone()] {
                    let result = if request_id == "unrelated" || accepts {
                        "success"
                    } else {
                        "error"
                    };
                    let reply =
                        serde_json::to_vec(&json!({"type":"response","requestId":request_id,
                        "resultType":result,"result":{"clientId":"fixture"}}))
                        .unwrap();
                    fixture.write_u32_le(reply.len() as u32).await.unwrap();
                    fixture.write_all(&reply).await.unwrap();
                }
            });
            assert_eq!(handshake(client).await.is_ok(), accepts);
            server.await.unwrap();
        }
    }

    #[tokio::test]
    #[ignore = "requires Codex already running from the official Windows entry point"]
    async fn real_official_desktop_responds_without_pocket_launch() {
        let version = desktop_connection().await.unwrap();
        assert!(!version.is_empty());
        println!("Official desktop {version}: verified pipe peer and IPC initialize");
    }
}
