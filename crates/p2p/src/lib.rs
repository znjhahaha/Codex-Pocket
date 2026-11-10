//! Reliable ordered DataChannel carrying the existing Pocket TLS ciphertext stream.
//! This does not authenticate signaling, certificates or devices. Controller validates the
//! active generation and authenticated signaling before applying remote descriptions/candidates.
use std::{
    cell::Cell,
    ffi::{CStr, CString, c_char, c_void},
    io,
    pin::Pin,
    sync::{Arc, Mutex},
    task::{Context, Poll, Waker},
};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
const AGAIN: i32 = -1;
const CLOSED: i32 = -2;
const FAILED: i32 = -3;
const OPEN: i32 = 1;
const END: i32 = 4;
const ERROR: i32 = 5;
type Callback = unsafe extern "C" fn(*mut c_void, u64, u64, i32, *const c_char, *const c_char);
unsafe extern "C" {
    fn p2p_create(generation: u64, bind: *const c_char, cb: Callback, user: *mut c_void) -> u64;
    fn p2p_start(handle: u64, generation: u64, offerer: i32) -> i32;
    fn p2p_remote_description(
        handle: u64,
        generation: u64,
        sdp: *const c_char,
        kind: *const c_char,
    ) -> i32;
    fn p2p_remote_candidate(
        handle: u64,
        generation: u64,
        candidate: *const c_char,
        mid: *const c_char,
    ) -> i32;
    fn p2p_read(handle: u64, bytes: *mut u8, capacity: i32) -> i32;
    fn p2p_write(handle: u64, bytes: *const u8, length: i32) -> i32;
    fn p2p_flush(handle: u64) -> i32;
    fn p2p_close(handle: u64, graceful: i32) -> i32;
    fn p2p_status(handle: u64) -> i32;
    fn p2p_destroy(handle: u64);
}
#[derive(Debug, Clone)]
pub enum Signal {
    Description {
        generation: u64,
        sdp: String,
        kind: String,
    },
    Candidate {
        generation: u64,
        candidate: String,
        mid: String,
    },
}
#[derive(Default)]
struct Waiters {
    read: Option<Waker>,
    write: Option<Waker>,
    open: Vec<(u64, Waker)>,
    next_open: u64,
}
struct Callbacks {
    waiters: Mutex<Waiters>,
    signal: Box<dyn Fn(Signal) + Send + Sync>,
}
thread_local! { static CALLBACK_HANDLE: Cell<u64> = const { Cell::new(0) }; }
unsafe extern "C" fn callback(
    user: *mut c_void,
    handle: u64,
    generation: u64,
    event: i32,
    a: *const c_char,
    b: *const c_char,
) {
    // Box is stable and freed only after p2p_destroy joins every native application callback.
    let cb = unsafe { &*user.cast::<Callbacks>() };
    let previous = CALLBACK_HANDLE.replace(handle);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if event == 6 || event == 7 {
            let a = unsafe { CStr::from_ptr(a) }.to_string_lossy().into_owned();
            let b = unsafe { CStr::from_ptr(b) }.to_string_lossy().into_owned();
            (cb.signal)(if event == 6 {
                Signal::Description {
                    generation,
                    sdp: a,
                    kind: b,
                }
            } else {
                Signal::Candidate {
                    generation,
                    candidate: a,
                    mid: b,
                }
            });
        } else {
            let (r, w, o) = {
                let mut wait = cb.waiters.lock().unwrap_or_else(|e| e.into_inner());
                (
                    if event == 2 || event == END || event == ERROR {
                        wait.read.take()
                    } else {
                        None
                    },
                    if event == OPEN || event == 3 || event == END || event == ERROR {
                        wait.write.take()
                    } else {
                        None
                    },
                    if event == OPEN || event == END || event == ERROR {
                        std::mem::take(&mut wait.open)
                    } else {
                        Vec::new()
                    },
                )
            };
            for waker in [r, w]
                .into_iter()
                .flatten()
                .chain(o.into_iter().map(|(_, w)| w))
            {
                waker.wake();
            }
        }
    }));
    if result.is_err() {
        unsafe {
            p2p_close(handle, 0);
        }
    }
    CALLBACK_HANDLE.set(previous);
}
struct Native {
    handle: u64,
    callbacks: Option<Box<Callbacks>>,
}
impl Native {
    fn callbacks(&self) -> &Callbacks {
        self.callbacks.as_ref().unwrap()
    }
}
impl Drop for Native {
    fn drop(&mut self) {
        if CALLBACK_HANDLE.get() == self.handle {
            // A controller may release its last owner inside a signal callback. Keep the callback
            // Box alive while another thread joins the native worker; never free it on that worker.
            let callbacks = self.callbacks.take().unwrap();
            let handle = self.handle;
            std::thread::spawn(move || {
                unsafe {
                    p2p_destroy(handle);
                }
                drop(callbacks);
            });
        } else {
            unsafe {
                p2p_destroy(self.handle);
            }
        }
    }
}
struct OpenRegistration {
    native: Arc<Native>,
    id: u64,
}
impl Drop for OpenRegistration {
    fn drop(&mut self) {
        self.native
            .callbacks()
            .waiters
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .open
            .retain(|(id, _)| *id != self.id);
    }
}
/// Control may be cloned; only one stream reader/writer is constructed per peer.
#[derive(Clone)]
pub struct DataChannelControl {
    native: Arc<Native>,
    generation: u64,
}
pub struct DataChannelStream {
    native: Arc<Native>,
    shutdown: bool,
}
impl Drop for DataChannelStream {
    fn drop(&mut self) {
        unsafe {
            p2p_close(self.native.handle, 0);
        }
    }
}
fn error(code: i32) -> io::Error {
    io::Error::new(
        match code {
            CLOSED => io::ErrorKind::BrokenPipe,
            FAILED => io::ErrorKind::ConnectionAborted,
            -4 | -5 => io::ErrorKind::InvalidInput,
            AGAIN => io::ErrorKind::WouldBlock,
            _ => io::ErrorKind::Other,
        },
        format!("DataChannel transport error {code}"),
    )
}
fn success(code: i32) -> io::Result<()> {
    if code < 0 { Err(error(code)) } else { Ok(()) }
}
fn string(value: &str) -> io::Result<CString> {
    CString::new(value).map_err(|_| error(-5))
}
impl DataChannelStream {
    /// `signal` runs on a native worker: enqueue to the controller and return promptly.
    /// Native queues: TX including RTC buffer <=1 MiB, resume <=256 KiB, RX <=1 MiB.
    pub fn new(
        generation: u64,
        bind: Option<&str>,
        signal: impl Fn(Signal) + Send + Sync + 'static,
    ) -> io::Result<(Self, DataChannelControl)> {
        let mut callbacks = Box::new(Callbacks {
            waiters: Mutex::new(Waiters::default()),
            signal: Box::new(signal),
        });
        let addr = bind.map(string).transpose()?;
        let handle = unsafe {
            p2p_create(
                generation,
                addr.as_ref().map_or(std::ptr::null(), |s| s.as_ptr()),
                callback,
                (&mut *callbacks as *mut Callbacks).cast(),
            )
        };
        if handle == 0 {
            return Err(error(-5));
        }
        let native = Arc::new(Native {
            handle,
            callbacks: Some(callbacks),
        });
        Ok((
            Self {
                native: native.clone(),
                shutdown: false,
            },
            DataChannelControl { native, generation },
        ))
    }
}
impl DataChannelControl {
    pub fn generation(&self) -> u64 {
        self.generation
    }
    pub fn start(&self, offerer: bool) -> io::Result<()> {
        success(unsafe { p2p_start(self.native.handle, self.generation, offerer.into()) })
    }
    pub fn remote_description(&self, generation: u64, sdp: &str, kind: &str) -> io::Result<()> {
        let (sdp, kind) = (string(sdp)?, string(kind)?);
        success(unsafe {
            p2p_remote_description(self.native.handle, generation, sdp.as_ptr(), kind.as_ptr())
        })
    }
    pub fn remote_candidate(&self, generation: u64, candidate: &str, mid: &str) -> io::Result<()> {
        let (candidate, mid) = (string(candidate)?, string(mid)?);
        success(unsafe {
            p2p_remote_candidate(
                self.native.handle,
                generation,
                candidate.as_ptr(),
                mid.as_ptr(),
            )
        })
    }
    pub async fn opened(&self) -> io::Result<()> {
        let id = {
            let mut wait = self
                .native
                .callbacks()
                .waiters
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            wait.next_open = wait.next_open.wrapping_add(1);
            wait.next_open
        };
        let _registration = OpenRegistration {
            native: self.native.clone(),
            id,
        };
        std::future::poll_fn(|cx| {
            let mut wait = self
                .native
                .callbacks()
                .waiters
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            match unsafe { p2p_status(self.native.handle) } {
                OPEN => Poll::Ready(Ok(())),
                END | ERROR => Poll::Ready(Err(error(CLOSED))),
                _ => {
                    if let Some((_, waker)) = wait.open.iter_mut().find(|(key, _)| *key == id) {
                        *waker = cx.waker().clone();
                    } else {
                        if wait.open.len() >= 64 {
                            return Poll::Ready(Err(error(AGAIN)));
                        }
                        wait.open.push((id, cx.waker().clone()));
                    }
                    Poll::Pending
                }
            }
        })
        .await
    }
    pub fn close(&self, graceful: bool) {
        unsafe {
            p2p_close(self.native.handle, graceful.into());
        }
    }
}
impl AsyncRead for DataChannelStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        if buf.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        // Register before inspecting native state under the same lock the callback uses. A
        // callback between the read returning AGAIN and poll returning Pending cannot be lost.
        let mut wait = self
            .native
            .callbacks()
            .waiters
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        wait.read = Some(cx.waker().clone());
        let bytes = buf.initialize_unfilled();
        let n = unsafe {
            p2p_read(
                self.native.handle,
                bytes.as_mut_ptr(),
                bytes.len().min(i32::MAX as usize) as i32,
            )
        };
        if n == AGAIN {
            return Poll::Pending;
        }
        wait.read = None;
        if n < 0 {
            Poll::Ready(Err(error(n)))
        } else {
            buf.advance(n as usize);
            Poll::Ready(Ok(()))
        }
    }
}
impl AsyncWrite for DataChannelStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bytes: &[u8],
    ) -> Poll<io::Result<usize>> {
        if self.shutdown {
            return Poll::Ready(Err(error(CLOSED)));
        }
        let mut wait = self
            .native
            .callbacks()
            .waiters
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        wait.write = Some(cx.waker().clone());
        let n = unsafe {
            p2p_write(
                self.native.handle,
                bytes.as_ptr(),
                bytes.len().min(65536) as i32,
            )
        };
        if n == AGAIN {
            return Poll::Pending;
        }
        wait.write = None;
        if n < 0 {
            Poll::Ready(Err(error(n)))
        } else {
            Poll::Ready(Ok(n as usize))
        }
    }
    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let mut wait = self
            .native
            .callbacks()
            .waiters
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        wait.write = Some(cx.waker().clone());
        let n = unsafe { p2p_flush(self.native.handle) };
        if n == AGAIN {
            return Poll::Pending;
        }
        wait.write = None;
        Poll::Ready(success(n))
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if !self.shutdown {
            self.shutdown = true;
            unsafe {
                p2p_close(self.native.handle, 1);
            }
        }
        let mut wait = self
            .native
            .callbacks()
            .waiters
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        wait.write = Some(cx.waker().clone());
        match unsafe { p2p_status(self.native.handle) } {
            END => {
                wait.write = None;
                Poll::Ready(Ok(()))
            }
            ERROR => {
                wait.write = None;
                Poll::Ready(Err(error(FAILED)))
            }
            _ => Poll::Pending,
        }
    }
}
