//! Windows user-bound encrypted storage. No plaintext credential is persisted.
use anyhow::{Result,bail};
use std::path::Path;
use crate::storage;

pub fn save(root:&Path,name:&str,value:&str)->Result<()> {
    if !matches!(name,"tunnel-token"|"cloudflare-api") || value.is_empty() || value.len()>16384 {bail!("Invalid credential")}
    storage::private_dir(root)?;
    let encrypted=protect(value.as_bytes())?;
    use base64::Engine;
    storage::atomic(&root.join(format!("{name}.secret")),&base64::engine::general_purpose::STANDARD.encode(encrypted))
}
pub fn load(root:&Path,name:&str)->Result<String> {
    if !matches!(name,"tunnel-token"|"cloudflare-api"){bail!("Invalid credential selector")}
    let encoded:String=storage::read(&root.join(format!("{name}.secret")))?;
    use base64::Engine;
    let data=base64::engine::general_purpose::STANDARD.decode(encoded).map_err(|_|anyhow::anyhow!("Credential record is invalid"))?;
    String::from_utf8(unprotect(&data)?).map_err(|_|anyhow::anyhow!("Credential encoding is invalid"))
}
#[cfg(windows)]
fn transform(bytes:&[u8],encrypt:bool)->Result<Vec<u8>> {
    use windows_sys::Win32::{Foundation::LocalFree,Security::Cryptography::*};
    let input=CRYPT_INTEGER_BLOB{cbData:u32::try_from(bytes.len())?,pbData:bytes.as_ptr() as *mut u8};
    let mut output=CRYPT_INTEGER_BLOB{cbData:0,pbData:std::ptr::null_mut()};
    let success=unsafe{if encrypt {
        CryptProtectData(&input,std::ptr::null(),std::ptr::null(),std::ptr::null_mut(),std::ptr::null(),CRYPTPROTECT_UI_FORBIDDEN,&mut output)
    } else {
        CryptUnprotectData(&input,std::ptr::null_mut(),std::ptr::null(),std::ptr::null_mut(),std::ptr::null(),CRYPTPROTECT_UI_FORBIDDEN,&mut output)
    }};
    if success==0{bail!("Windows credential protection failed")}
    let result=unsafe{std::slice::from_raw_parts(output.pbData,output.cbData as usize).to_vec()};
    unsafe{LocalFree(output.pbData as _)};Ok(result)
}
#[cfg(windows)] fn protect(b:&[u8])->Result<Vec<u8>>{transform(b,true)}
#[cfg(windows)] fn unprotect(b:&[u8])->Result<Vec<u8>>{transform(b,false)}
#[cfg(not(windows))] fn protect(_:&[u8])->Result<Vec<u8>>{bail!("Credential storage requires Windows")}
#[cfg(not(windows))] fn unprotect(_:&[u8])->Result<Vec<u8>>{bail!("Credential storage requires Windows")}

#[cfg(all(test,windows))]
mod tests {
    use super::*;
    #[test] fn user_protected_roundtrip_and_no_plaintext(){let secret=b"fixture-credential";let e=protect(secret).unwrap();assert!(!e.windows(secret.len()).any(|w|w==secret));assert_eq!(unprotect(&e).unwrap(),secret);}
}
