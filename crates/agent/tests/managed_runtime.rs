//! Path harness keeps lib/main/Cargo in the integrator's write set.
pub use pocket_agent::{auth, capabilities, native};
#[path = "../src/managed_runtime.rs"]
mod managed_runtime;
mod storage {
    pub use pocket_agent::storage::*;
    pub(crate) fn verify_physical_file(
        path: &std::path::Path,
        file: &std::fs::File,
    ) -> anyhow::Result<()> {
        #[cfg(windows)]
        {
            use std::os::windows::io::AsRawHandle;
            use windows_sys::Win32::Storage::FileSystem::GetFinalPathNameByHandleW;
            let mut buffer = vec![0u16; 32768];
            let count = unsafe {
                GetFinalPathNameByHandleW(
                    file.as_raw_handle(),
                    buffer.as_mut_ptr(),
                    buffer.len() as u32,
                    0,
                )
            };
            anyhow::ensure!(
                count > 0 && (count as usize) < buffer.len(),
                "Physical handle unavailable"
            );
            let actual = String::from_utf16(&buffer[..count as usize])?;
            let expected = dunce::canonicalize(path)?.to_string_lossy().into_owned();
            let normalize = |v: &str| v.strip_prefix(r"\\?\").unwrap_or(v).to_lowercase();
            anyhow::ensure!(
                normalize(&actual) == normalize(&expected),
                "Opened file identity mismatch"
            );
        }
        #[cfg(not(windows))]
        let _ = (path, file);
        Ok(())
    }
}
#[path = "../src/managed_runtime/tests/suite.rs"]
mod suite;
