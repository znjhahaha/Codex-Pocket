use std::{
    io,
    path::{Path, PathBuf},
};

#[cfg(unix)]
pub type LocalStream = tokio::net::UnixStream;
#[cfg(windows)]
pub use windows::LocalStream;

pub(crate) struct ValidatedEndpoint {
    pub(crate) path: PathBuf,
    #[cfg(windows)]
    pub(crate) guard: std::os::windows::io::OwnedHandle,
}

#[cfg(windows)]
pub(crate) fn validate_endpoint(path: &Path) -> io::Result<ValidatedEndpoint> {
    use std::os::windows::fs::MetadataExt;
    let (path, guard) = crate::windows_socket_validation::validate_private_socket_path(path)?;
    let path = dunce::simplified(&path).to_path_buf();
    crate::validate_windows_socket_path(&path)?;
    let metadata = std::fs::symlink_metadata(&path)?;
    // Windows AF_UNIX endpoints are reparse points but not symbolic links.
    if metadata.file_attributes() & 0x400 == 0 || metadata.file_type().is_symlink() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "daemon endpoint is not a Windows AF_UNIX socket",
        ));
    }
    Ok(ValidatedEndpoint { path, guard })
}

#[cfg(unix)]
pub(crate) fn validate_endpoint(path: &Path) -> io::Result<ValidatedEndpoint> {
    use sha2::{Digest, Sha256};
    use std::os::unix::{
        ffi::OsStrExt,
        fs::{FileTypeExt, MetadataExt},
    };
    let uid = unsafe { libc::geteuid() };
    let directory = std::fs::canonicalize("/tmp")?.join(format!("codex-daemon-{uid}"));
    let metadata = std::fs::symlink_metadata(&directory)?;
    if !metadata.is_dir() || metadata.uid() != uid || metadata.mode() & 0o777 != 0o700 {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "untrusted protected daemon directory",
        ));
    }
    let canonical = std::fs::canonicalize(path.parent().ok_or(io::ErrorKind::InvalidInput)?)?
        .join(path.file_name().ok_or(io::ErrorKind::InvalidInput)?);
    let protected = directory.join(format!(
        "{:x}",
        Sha256::digest(canonical.as_os_str().as_bytes())
    ));
    if std::fs::read_link(path)? != protected {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "untrusted daemon rendezvous alias",
        ));
    }
    let metadata = std::fs::symlink_metadata(&protected)?;
    if !metadata.file_type().is_socket() || metadata.uid() != uid || metadata.mode() & 0o077 != 0 {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "untrusted protected daemon socket",
        ));
    }
    Ok(ValidatedEndpoint { path: protected })
}

#[cfg(unix)]
pub(crate) async fn connect(path: &Path) -> io::Result<LocalStream> {
    let endpoint = validate_endpoint(path)?;
    tokio::net::UnixStream::connect(endpoint.path).await
}

#[cfg(windows)]
pub(crate) async fn connect(path: &Path) -> io::Result<LocalStream> {
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || {
        let endpoint = validate_endpoint(&path)?;
        let socket = uds_windows::UnixStream::connect(&endpoint.path)?;
        use std::os::windows::io::AsRawSocket;
        crate::windows_peer::ensure_non_elevated_peer(socket.as_raw_socket())?;
        windows::LocalStream::new(socket, endpoint.guard)
    })
    .await
    .map_err(|_| io::Error::other("local socket connector task failed"))?
}

#[cfg(windows)]
mod windows {
    use async_io::Async;
    use std::{
        io,
        net::Shutdown,
        os::windows::io::{AsRawSocket, AsSocket, BorrowedSocket, OwnedHandle},
        pin::Pin,
        task::{Context, Poll, ready},
    };
    use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
    use tokio_util::compat::{Compat, FuturesAsyncReadCompatExt};
    /// AF_UNIX stream retaining the validated private directory handle.
    pub struct LocalStream {
        inner: Compat<Async<Socket>>,
        _directory: OwnedHandle,
    }
    impl LocalStream {
        pub(crate) fn peer_process_id(&self) -> io::Result<u32> {
            crate::windows_peer::peer_process_id(self.inner.get_ref().get_ref().0.as_raw_socket())
        }
        pub(super) fn new(
            socket: uds_windows::UnixStream,
            directory: OwnedHandle,
        ) -> io::Result<Self> {
            Ok(Self {
                inner: Async::new(Socket(socket))?.compat(),
                _directory: directory,
            })
        }
    }
    struct Socket(uds_windows::UnixStream);
    impl AsSocket for Socket {
        fn as_socket(&self) -> BorrowedSocket<'_> {
            unsafe { BorrowedSocket::borrow_raw(self.0.as_raw_socket()) }
        }
    }
    impl io::Read for Socket {
        fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
            io::Read::read(&mut self.0, bytes)
        }
    }
    impl io::Write for Socket {
        fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
            io::Write::write(&mut self.0, bytes)
        }
        fn flush(&mut self) -> io::Result<()> {
            io::Write::flush(&mut self.0)
        }
    }
    unsafe impl async_io::IoSafe for Socket {}
    impl AsyncRead for LocalStream {
        fn poll_read(
            self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buffer: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().inner).poll_read(cx, buffer)
        }
    }
    impl AsyncWrite for LocalStream {
        fn poll_write(
            self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buffer: &[u8],
        ) -> Poll<io::Result<usize>> {
            Pin::new(&mut self.get_mut().inner).poll_write(cx, buffer)
        }
        fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().inner).poll_flush(cx)
        }
        fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            let inner = &mut self.get_mut().inner;
            ready!(Pin::new(&mut *inner).poll_flush(cx))?;
            inner.get_ref().get_ref().0.shutdown(Shutdown::Write)?;
            Poll::Ready(Ok(()))
        }
    }
}
