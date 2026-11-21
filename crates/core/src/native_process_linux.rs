use super::NativeProcess;
use sha2::{Digest, Sha256};
use std::{fs, io};

fn birth(stat: &str, boot: &str, pid: u32) -> io::Result<(NativeProcess, bool)> {
    let invalid = || io::Error::from(io::ErrorKind::InvalidData);
    // comm is parenthesized but may itself contain spaces and parentheses.
    let split = stat.rfind(") ").ok_or_else(invalid)?;
    let parsed_pid = stat.split_once(" (").ok_or_else(invalid)?.0.parse::<u32>().map_err(|_| invalid())?;
    let fields: Vec<_> = stat[split + 2..].split_whitespace().collect();
    if pid == 0 || parsed_pid != pid || fields.len() < 20 || boot.trim().len() != 36 { return Err(invalid()); }
    let ticks = fields[19].parse::<u64>().map_err(|_| invalid())?;
    if ticks == 0 || !boot.trim().bytes().all(|b| b.is_ascii_hexdigit() || b == b'-') { return Err(invalid()); }
    let mut identity = Sha256::new();
    identity.update(boot.trim().as_bytes());
    identity.update(ticks.to_le_bytes());
    let created_at = u64::from_le_bytes(identity.finalize()[..8].try_into().unwrap());
    if created_at == 0 { return Err(invalid()); }
    Ok((NativeProcess { pid, created_at }, matches!(fields[0], "Z" | "X" | "x")))
}

fn read(pid: u32) -> io::Result<(NativeProcess, bool)> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat"))?;
    let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id")?;
    birth(&stat, &boot, pid)
}
pub fn capture(pid: u32) -> io::Result<NativeProcess> { read(pid).map(|(identity, _)| identity) }
pub fn terminated(expected: &NativeProcess) -> bool {
    match read(expected.pid) {
        Ok((identity, exited)) => exited || identity != *expected,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            // A missing boot-id mount is not proof that this process ended.
            matches!(fs::metadata(format!("/proc/{}", expected.pid)), Err(e) if e.kind() == io::ErrorKind::NotFound)
        }
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn stat(state: &str, ticks: u64) -> String {
        format!("123 (native (worker) thread) {state} {} {ticks} 999", vec!["0"; 18].join(" "))
    }
    const BOOT: &str = "eeea5abc-7804-4880-8c94-c55901ff7158";
    #[test]
    fn process_name_delimiters_do_not_shift_start_identity() {
        let (first, ended) = birth(&stat("S", 900), BOOT, 123).unwrap();
        assert!(!ended);
        assert_eq!(first, birth(&stat("R", 900), BOOT, 123).unwrap().0);
        assert_ne!(first, birth(&stat("R", 901), BOOT, 123).unwrap().0);
        assert_ne!(first, birth(&stat("R", 900), "eeea5abc-7804-4880-8c94-c55901ff7159", 123).unwrap().0);
        assert!(birth(&stat("Z", 900), BOOT, 123).unwrap().1);
    }
    #[test]
    fn unreadable_or_mismatched_identity_cannot_be_accepted() {
        assert!(birth(&stat("S", 900), BOOT, 124).is_err());
        assert!(birth(&stat("S", 0), BOOT, 123).is_err());
        assert!(birth("123 (truncated)", BOOT, 123).is_err());
        assert!(birth(&stat("S", 900), "", 123).is_err());
    }
    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn live_self_and_exited_child_have_distinct_lifetimes() {
        let current = NativeProcess::capture(std::process::id()).unwrap();
        assert!(!current.terminated());
        let mut child = std::process::Command::new("sh").args(["-c", "sleep 5"]).spawn().unwrap();
        let identity = NativeProcess::capture(child.id()).unwrap();
        assert!(!identity.terminated());
        child.kill().unwrap(); child.wait().unwrap();
        assert!(identity.terminated());
    }
}
