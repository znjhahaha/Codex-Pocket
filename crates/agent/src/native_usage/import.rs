//! Bounded incremental import of native rollout files. Roots come only from the agent's
//! configuration and connected runtimes; links are never followed and only identifiers,
//! timestamps and token numbers leave a record.
use super::{
    Observation, identity, label,
    ledger::{self, Failure, Ledger, Outcome},
};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::{File, Metadata},
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    time::{Duration, Instant, UNIX_EPOCH},
};

/// Session headers stay well below this; longer lines are message bodies and are skipped unread.
const MAX_LINE: usize = 1024 * 1024;
const PROBE: usize = 64 * 1024;
const CHUNK: usize = 256 * 1024;
const MAX_FILES: usize = 100_000;
const MAX_DIRS: usize = 20_000;
const HEAD: u64 = 256;
const TAIL: u64 = 64;
const UNSUPPORTED: i64 = 1;
const UNREADABLE: i64 = 2;

struct Budget {
    bytes: u64,
    deadline: Instant,
}
impl Budget {
    fn spent(&self) -> bool {
        self.bytes == 0 || Instant::now() >= self.deadline
    }
}
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct Pass {
    pub files: u64,
    pub pending_files: u64,
    pub pending_bytes: u64,
    pub skipped_files: u64,
    pub truncated: bool,
    pub read_bytes: u64,
    pub changed: bool,
}
struct Found {
    key: String,
    path: PathBuf,
    size: u64,
    mtime: i64,
}
#[derive(Default)]
struct Context {
    thread: Option<String>,
    turn: Option<String>,
    model: Option<String>,
    provider: Option<String>,
    parent: Option<String>,
}
struct Row {
    identity: String,
    head: String,
    tail: String,
    offset: u64,
    context: Context,
}
struct Done {
    complete: bool,
    status: i64,
    offset: u64,
    read: u64,
    changed: bool,
}
fn plain(meta: &Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if meta.file_attributes() & 0x400 != 0 {
            return false;
        }
    }
    !meta.is_symlink()
}
/// The ledger keeps a digest instead of any path below the Codex home.
fn key(store: &str, relative: &str) -> String {
    hex::encode(Sha256::digest(format!("{store}\0{relative}")))
}
fn discover(root: &Path, store: &str, out: &mut Vec<Found>, dirs: &mut usize) -> bool {
    let mut stack = vec![
        (root.join("archived_sessions"), "archived_sessions".to_owned(), 0),
        (root.join("sessions"), "sessions".to_owned(), 3usize),
    ];
    while let Some((dir, relative, depth)) = stack.pop() {
        if !std::fs::symlink_metadata(&dir).is_ok_and(|m| m.is_dir() && plain(&m)) {
            continue;
        }
        *dirs += 1;
        if *dirs > MAX_DIRS {
            return true;
        }
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            // Directory entry metadata describes the entry itself, never a link target.
            let Ok(meta) = entry.metadata() else { continue };
            let name = entry.file_name();
            let Some(name) = name.to_str().filter(|n| n.len() <= 255) else {
                continue;
            };
            if !plain(&meta) {
                continue;
            }
            if meta.is_dir() {
                if depth > 0 {
                    stack.push((entry.path(), format!("{relative}/{name}"), depth - 1));
                }
            } else if meta.is_file() && name.starts_with("rollout-") && name.ends_with(".jsonl") {
                if out.len() >= MAX_FILES {
                    return true;
                }
                out.push(Found {
                    key: key(store, &format!("{relative}/{name}")),
                    path: entry.path(),
                    size: meta.len(),
                    mtime: meta
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                        .map_or(0, |d| d.as_nanos().min(i64::MAX as u128) as i64),
                });
            }
        }
    }
    false
}
fn open(path: &Path) -> Option<File> {
    let meta = std::fs::symlink_metadata(path).ok()?;
    if !meta.is_file() || !plain(&meta) {
        return None;
    }
    let file = File::open(path).ok()?;
    if !file.metadata().ok()?.is_file() {
        return None;
    }
    crate::storage::verify_physical_file(path, &file).ok()?;
    Some(file)
}
fn file_identity(file: &File) -> String {
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        use windows_sys::Win32::Storage::FileSystem::{
            BY_HANDLE_FILE_INFORMATION, GetFileInformationByHandle,
        };
        let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
        if unsafe { GetFileInformationByHandle(file.as_raw_handle(), &mut info) } == 0 {
            return String::new();
        }
        format!(
            "{:x}-{:x}-{:x}",
            info.dwVolumeSerialNumber, info.nFileIndexHigh, info.nFileIndexLow
        )
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        file.metadata()
            .map(|m| format!("{:x}-{:x}", m.dev(), m.ino()))
            .unwrap_or_default()
    }
    #[cfg(not(any(windows, unix)))]
    {
        let _ = file;
        String::new()
    }
}
fn stamp(file: &mut File, start: u64, len: u64) -> Option<String> {
    let mut bytes = vec![0u8; len.min(HEAD) as usize];
    file.seek(SeekFrom::Start(start)).ok()?;
    file.read_exact(&mut bytes).ok()?;
    Some(format!(
        "{}:{}",
        bytes.len(),
        hex::encode(&Sha256::digest(&bytes)[..16])
    ))
}
fn stamped(file: &mut File, stored: &str, start: impl Fn(u64) -> Option<u64>) -> bool {
    let Some(len) = stored
        .split_once(':')
        .and_then(|(n, _)| n.parse::<u64>().ok())
        .filter(|n| (1..=HEAD).contains(n))
    else {
        return false;
    };
    start(len)
        .and_then(|start| stamp(file, start, len))
        .is_some_and(|actual| actual == stored)
}
fn has(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}
/// Returns false only when the first line is not a usable native session header.
fn line(
    context: &mut Context,
    first: bool,
    bytes: &[u8],
    out: &mut Vec<Observation>,
    invalid: &mut u64,
) -> bool {
    if first {
        let Ok(v) = serde_json::from_slice::<Value>(bytes) else {
            return false;
        };
        let p = &v["payload"];
        let Some(thread) = p["id"]
            .as_str()
            .filter(|s| v["type"] == "session_meta" && identity(s))
        else {
            return false;
        };
        context.provider = label(&p["model_provider"]);
        // Forked subagents copy their parent's context (never its token counts);
        // a parent link lets the ledger refuse any copied cumulative point.
        context.parent = p["forked_from_id"]
            .as_str()
            .filter(|s| identity(s) && *s != thread)
            .map(str::to_owned);
        context.thread = Some(thread.to_owned());
        return true;
    }
    let Some(thread) = &context.thread else {
        return false;
    };
    let probe = &bytes[..bytes.len().min(PROBE)];
    let count = has(probe, b"token_count");
    if !count && !has(probe, b"turn_context") {
        return true;
    }
    let Ok(v) = serde_json::from_slice::<Value>(bytes) else {
        *invalid += u64::from(count);
        return true;
    };
    let p = &v["payload"];
    if v["type"] == "turn_context" {
        context.turn = p["turn_id"]
            .as_str()
            .filter(|s| identity(s))
            .map(str::to_owned);
        if let Some(model) = label(&p["model"]) {
            context.model = Some(model);
        }
    } else if v["type"] == "event_msg" && p["type"] == "token_count" && !p["info"].is_null() {
        match Observation::history(&p["info"], &v["timestamp"], thread) {
            Some(mut o) => {
                o.turn = context.turn.clone();
                o.model = context.model.clone();
                o.provider = context.provider.clone();
                o.parent = context.parent.clone();
                out.push(o);
            }
            None => *invalid += 1,
        }
    }
    true
}
fn row(conn: &Connection, key: &str) -> Result<Option<Row>, Failure> {
    Ok(conn
        .prepare_cached(
            "SELECT identity,head,tail,offset,thread_id,turn_id,model,provider,parent FROM files WHERE key=?1",
        )?
        .query_row([key], |r| {
            Ok(Row {
                identity: r.get(0)?,
                head: r.get(1)?,
                tail: r.get(2)?,
                offset: r.get::<_, i64>(3)?.max(0) as u64,
                context: Context {
                    thread: r.get(4)?,
                    turn: r.get(5)?,
                    model: r.get(6)?,
                    provider: r.get(7)?,
                    parent: r.get(8)?,
                },
            })
        })
        .optional()?)
}
fn import(
    ledger: &Ledger,
    store: &str,
    found: &Found,
    budget: &mut Budget,
) -> Result<Done, Failure> {
    let previous = ledger.read(|conn| row(conn, &found.key))?;
    let mut context = Context::default();
    let (mut offset, mut status, mut complete, mut read) = (0u64, 0i64, true, 0u64);
    let (mut identity, mut head, mut tail) = (String::new(), String::new(), String::new());
    let (mut observations, mut invalid, mut rewound) = (Vec::new(), 0u64, 0u64);
    match open(&found.path) {
        None => status = UNREADABLE,
        Some(mut file) => {
            identity = file_identity(&file);
            let size = file.metadata().map_or(0, |m| m.len());
            // Progress is trusted only for the same physical file whose first and last
            // consumed bytes are unchanged; a truncated or replaced file restarts at
            // zero, which is safe because the ledger ignores points it already holds.
            match previous {
                Some(r)
                    if r.offset > 0
                        && r.offset <= size
                        && r.identity == identity
                        && stamped(&mut file, &r.head, |_| Some(0))
                        && stamped(&mut file, &r.tail, |len| r.offset.checked_sub(len)) =>
                {
                    offset = r.offset;
                    context = r.context;
                }
                Some(r) => rewound = u64::from(r.offset > 0),
                None => {}
            }
            let mut chunk = vec![0u8; CHUNK];
            let (mut current, mut partial, mut oversized) = (Vec::new(), 0u64, false);
            if file.seek(SeekFrom::Start(offset)).is_err() {
                status = UNREADABLE;
            }
            'read: while status == 0 {
                // Never stop inside a line that is being discarded: no slice could
                // ever get past a body longer than its own budget.
                if budget.spent() && !oversized {
                    complete = false;
                    break;
                }
                let want = chunk.len().min(budget.bytes.max(1) as usize);
                let n = match file.read(&mut chunk[..want]) {
                    Ok(0) => break,
                    Ok(n) => n,
                    Err(_) => {
                        status = UNREADABLE;
                        break;
                    }
                };
                budget.bytes = budget.bytes.saturating_sub(n as u64);
                read += n as u64;
                let mut rest = &chunk[..n];
                while let Some(end) = rest.iter().position(|b| *b == b'\n') {
                    oversized |= current.len() + end > MAX_LINE;
                    if !oversized {
                        current.extend_from_slice(&rest[..end]);
                    }
                    if !line(
                        &mut context,
                        offset == 0,
                        if oversized { &[] } else { &current },
                        &mut observations,
                        &mut invalid,
                    ) {
                        status = UNSUPPORTED;
                        break 'read;
                    }
                    offset += partial + end as u64 + 1;
                    (partial, oversized) = (0, false);
                    current.clear();
                    rest = &rest[end + 1..];
                }
                // An unterminated tail is left for the pass that sees its newline.
                partial += rest.len() as u64;
                oversized |= current.len() + rest.len() > MAX_LINE;
                if oversized {
                    current.clear();
                } else {
                    current.extend_from_slice(rest);
                }
            }
            if offset > 0 {
                head = stamp(&mut file, 0, offset.min(HEAD)).unwrap_or_default();
                let len = offset.min(TAIL);
                tail = stamp(&mut file, offset - len, len).unwrap_or_default();
            }
        }
    }
    let outcome = ledger.write(|tx, caps| {
        let mut outcome = Outcome::default();
        for o in &observations {
            ledger::observe(tx, o, caps, &mut outcome)?;
        }
        ledger::bump(tx, "invalid_records", invalid)?;
        ledger::bump(tx, "rejected_full", outcome.rejected)?;
        ledger::bump(tx, "rewound_files", rewound)?;
        tx.prepare_cached(
            "INSERT OR REPLACE INTO files(key,store,identity,head,tail,size,mtime,offset,complete,status,thread_id,turn_id,model,provider,parent) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
        )?
        .execute(params![
            found.key,
            store,
            identity,
            head,
            tail,
            found.size as i64,
            found.mtime,
            offset as i64,
            complete,
            status,
            context.thread,
            context.turn,
            context.model,
            context.provider,
            context.parent
        ])?;
        Ok(outcome)
    })?;
    Ok(Done {
        complete,
        status,
        offset,
        read,
        changed: outcome.changed || invalid > 0,
    })
}
/// One rate-limited slice of work: list the known homes, then read what changed,
/// newest first, until the byte or time budget is spent. Safe to repeat and to interrupt.
pub(crate) fn pass(
    ledger: &Ledger,
    homes: &[(String, PathBuf)],
    bytes: u64,
    time: Duration,
) -> Result<Pass, Failure> {
    let mut summary = Pass::default();
    let mut work = Vec::new();
    let mut dirs = 0;
    for (store, home) in homes {
        let Ok(root) = dunce::canonicalize(home) else {
            continue;
        };
        let mut found = Vec::new();
        summary.truncated |= discover(&root, store, &mut found, &mut dirs);
        let known = ledger.read(|conn| {
            conn.prepare_cached(
                "SELECT key,size,mtime,complete,status,offset FROM files WHERE store=?1",
            )?
            .query_map([store], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    (
                        r.get::<_, i64>(1)?,
                        r.get::<_, i64>(2)?,
                        r.get::<_, bool>(3)?,
                        r.get::<_, i64>(4)?,
                        r.get::<_, i64>(5)?,
                    ),
                ))
            })?
            .collect::<Result<HashMap<_, _>, _>>()
            .map_err(Failure::from)
        })?;
        for file in found {
            summary.files += 1;
            match known.get(&file.key) {
                Some(&(size, mtime, true, status, _))
                    if size == file.size as i64 && mtime == file.mtime =>
                {
                    summary.skipped_files += u64::from(status != 0)
                }
                state => work.push((
                    store.clone(),
                    state.map_or(0, |s| s.4.max(0) as u64),
                    file,
                )),
            }
        }
    }
    // Recent windows are what a phone asks for first.
    work.sort_by(|a, b| b.2.mtime.cmp(&a.2.mtime));
    // Listing is metadata only and bounded by entry counts; the clock covers reading.
    let budget = &mut Budget {
        bytes,
        deadline: Instant::now() + time,
    };
    for (store, offset, file) in work {
        if budget.spent() {
            summary.pending_files += 1;
            summary.pending_bytes += file.size.saturating_sub(offset);
            continue;
        }
        let done = import(ledger, &store, &file, budget)?;
        summary.read_bytes += done.read;
        summary.changed |= done.changed;
        if !done.complete {
            summary.pending_files += 1;
            summary.pending_bytes += file.size.saturating_sub(done.offset);
        }
        summary.skipped_files += u64::from(done.status != 0);
    }
    Ok(summary)
}
