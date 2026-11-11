//! Optional installed adapter. Never search CWD or arbitrary PATH directories.
use super::{Callback, c_char, c_void};
use std::{path::PathBuf, sync::OnceLock};
use libloading::Library;

struct Api {
    _library: Library,
    create: unsafe extern "C" fn(u64, *const c_char, *const c_char, Callback, *mut c_void) -> u64,
    start: unsafe extern "C" fn(u64,u64,i32)->i32,
    description: unsafe extern "C" fn(u64,u64,*const c_char,*const c_char)->i32,
    candidate: unsafe extern "C" fn(u64,u64,*const c_char,*const c_char)->i32,
    read: unsafe extern "C" fn(u64,*mut u8,i32)->i32,
    write: unsafe extern "C" fn(u64,*const u8,i32)->i32,
    flush: unsafe extern "C" fn(u64)->i32,
    close: unsafe extern "C" fn(u64,i32)->i32,
    status: unsafe extern "C" fn(u64)->i32,
    destroy: unsafe extern "C" fn(u64),
}
fn directory() -> Option<PathBuf> {
    #[cfg(debug_assertions)]
    if let Some(value) = std::env::var_os("P2P_NATIVE_DIR") {
        return std::fs::canonicalize(PathBuf::from(value)).ok();
    }
    std::env::current_exe().ok()?.parent().map(PathBuf::from)
}
fn api() -> Option<&'static Api> {
    static API: OnceLock<Option<Api>> = OnceLock::new();
    API.get_or_init(|| unsafe {
        let name = if cfg!(windows) { "pocket-p2p.dll" } else if cfg!(target_os="macos") { "libpocket-p2p.dylib" } else { "libpocket-p2p.so" };
        let path=directory()?.join(name);
        #[cfg(windows)]
        let library: Library=libloading::os::windows::Library::load_with_flags(path,0x100|0x1000).ok()?.into();
        #[cfg(not(windows))]
        let library=Library::new(path).ok()?;
        Some(Api {
            create:*library.get(b"p2p_create_config\0").ok()?,
            start:*library.get(b"p2p_start\0").ok()?,
            description:*library.get(b"p2p_remote_description\0").ok()?,
            candidate:*library.get(b"p2p_remote_candidate\0").ok()?,
            read:*library.get(b"p2p_read\0").ok()?,
            write:*library.get(b"p2p_write\0").ok()?,
            flush:*library.get(b"p2p_flush\0").ok()?,
            close:*library.get(b"p2p_close\0").ok()?,
            status:*library.get(b"p2p_status\0").ok()?,
            destroy:*library.get(b"p2p_destroy\0").ok()?, _library:library,
        })
    }).as_ref()
}
pub fn available()->bool { api().is_some() }
macro_rules! call {
    ($name:ident, $field:ident, ($($arg:ident:$ty:ty),*), $ret:ty, $fallback:expr) => {
        pub unsafe fn $name($($arg:$ty),*) -> $ret {
            match api() { Some(api)=>unsafe {(api.$field)($($arg),*)},None=>$fallback }
        }
    };
}
call!(p2p_create_config,create,(g:u64,b:*const c_char,s:*const c_char,c:Callback,u:*mut c_void),u64,0);
call!(p2p_start,start,(h:u64,g:u64,o:i32),i32,-3);
call!(p2p_remote_description,description,(h:u64,g:u64,s:*const c_char,k:*const c_char),i32,-3);
call!(p2p_remote_candidate,candidate,(h:u64,g:u64,c:*const c_char,m:*const c_char),i32,-3);
call!(p2p_read,read,(h:u64,b:*mut u8,n:i32),i32,-3);
call!(p2p_write,write,(h:u64,b:*const u8,n:i32),i32,-3);
call!(p2p_flush,flush,(h:u64),i32,-3);
call!(p2p_close,close,(h:u64,g:i32),i32,-3);
call!(p2p_status,status,(h:u64),i32,5);
call!(p2p_destroy,destroy,(h:u64),(),());
