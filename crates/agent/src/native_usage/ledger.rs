//! SQLite ledger: one row per (thread, cumulative point); totals are sums of counted rows.
//!
//! Native usage arrives as a per-thread cumulative counter plus the size of the request
//! that produced it (`last`). Real rollouts (0.167-0.171) always satisfy
//! `cumulative - last == previous cumulative`, repeat the same cumulative many times,
//! re-emit the base point when a thread is resumed or reverted into a new rollout
//! segment, and may restart lower after a revert. So a record is identified by its
//! cumulative point and stored with `last` as its delta:
//!   * replay: the thread already holds this exact cumulative point; ignored.
//!   * counted: the record starts from zero (cumulative == last) or from a point the
//!     thread already holds (cumulative - last). A lower cumulative that is new and
//!     anchored this way is a reset; it opens a new segment and adds only `last`.
//!   * unanchored: anything else (inherited baseline of a resumed or forked thread,
//!     a gap, an out-of-order stray). Kept as an anchor but not counted until the
//!     point it continues from is seen, so the result never depends on arrival order.
//!   * inherited: the fork parent holds the same point; never counted in the child.
use super::{MAX_EVENTS, MAX_THREADS, Observation};
use rusqlite::{Connection, ErrorCode, OpenFlags, OptionalExtension, TransactionBehavior, params};
use std::{path::Path, sync::Mutex, time::Duration};

pub(crate) const SCHEMA: i64 = 1;
const DDL: &str = "
CREATE TABLE meta(key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE threads(id INTEGER PRIMARY KEY, thread_id TEXT NOT NULL UNIQUE, parent TEXT, segments INTEGER NOT NULL);
CREATE INDEX threads_parent ON threads(parent) WHERE parent IS NOT NULL;
CREATE TABLE events(
  id INTEGER PRIMARY KEY, thread INTEGER NOT NULL,
  cum_input INTEGER NOT NULL, cum_output INTEGER NOT NULL,
  prev_input INTEGER NOT NULL, prev_output INTEGER NOT NULL,
  input INTEGER NOT NULL, cached INTEGER NOT NULL, cache_write INTEGER NOT NULL,
  output INTEGER NOT NULL, reasoning INTEGER NOT NULL,
  state INTEGER NOT NULL, anomaly INTEGER NOT NULL, segment INTEGER NOT NULL,
  at INTEGER NOT NULL, turn_id TEXT, model TEXT, provider TEXT, source INTEGER NOT NULL,
  UNIQUE(thread, cum_input, cum_output));
CREATE INDEX events_prev ON events(thread, prev_input, prev_output);
CREATE INDEX events_at ON events(at);
CREATE TABLE files(
  key TEXT PRIMARY KEY, store TEXT NOT NULL, identity TEXT NOT NULL, head TEXT NOT NULL, tail TEXT NOT NULL,
  size INTEGER NOT NULL, mtime INTEGER NOT NULL, offset INTEGER NOT NULL, complete INTEGER NOT NULL,
  status INTEGER NOT NULL, thread_id TEXT, turn_id TEXT, model TEXT, provider TEXT, parent TEXT) WITHOUT ROWID;
CREATE INDEX files_store ON files(store);
";
pub(crate) const UNANCHORED: i64 = 0;
pub(crate) const COUNTED: i64 = 1;
pub(crate) const INHERITED: i64 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Failure {
    Schema,
    Corrupt,
    Locked,
    Path,
    Limit,
    Overflow,
    Busy,
    Unavailable,
}
impl From<rusqlite::Error> for Failure {
    fn from(e: rusqlite::Error) -> Self {
        match e.sqlite_error_code() {
            Some(ErrorCode::DatabaseBusy | ErrorCode::DatabaseLocked) => Self::Locked,
            Some(ErrorCode::NotADatabase | ErrorCode::DatabaseCorrupt) => Self::Corrupt,
            _ => Self::Unavailable,
        }
    }
}
impl Failure {
    pub(crate) fn details(self) -> (&'static str, &'static str, &'static str) {
        match self {
            Self::Schema => (
                "unsupported_schema",
                "unsupported_schema",
                "The native usage ledger was written by a newer agent and is left untouched.",
            ),
            Self::Corrupt => (
                "unavailable",
                "database_corrupt",
                "The native usage ledger is corrupt or is not a SQLite database.",
            ),
            Self::Locked => (
                "locked",
                "database_locked",
                "The native usage ledger is locked; retry later.",
            ),
            Self::Path => (
                "invalid_path",
                "invalid_path",
                "The native usage ledger path could not be verified.",
            ),
            Self::Limit => (
                "rejected",
                "query_limit",
                "The query exceeded the row, group, or execution budget.",
            ),
            Self::Overflow => (
                "rejected",
                "arithmetic_overflow",
                "An exact aggregate exceeded its numeric range.",
            ),
            Self::Busy => (
                "unavailable",
                "backend_busy",
                "The usage backend is busy; retry later.",
            ),
            Self::Unavailable => (
                "unavailable",
                "database_unavailable",
                "The native usage ledger could not be read.",
            ),
        }
    }
}
fn redirected(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|meta| {
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            if meta.file_attributes() & 0x400 != 0 {
                return true;
            }
        }
        meta.is_symlink() || !meta.is_file()
    })
}
fn version(conn: &Connection) -> Result<(i64, i64), Failure> {
    Ok((
        conn.query_row("PRAGMA user_version", [], |r| r.get(0))?,
        conn.query_row("SELECT COUNT(*) FROM sqlite_master", [], |r| r.get(0))?,
    ))
}
/// Read-only snapshot connection for queries; never creates or migrates.
pub(crate) fn reader(path: &Path) -> Result<Connection, Failure> {
    if redirected(path) {
        return Err(Failure::Path);
    }
    let conn = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    conn.busy_timeout(Duration::from_millis(250))?;
    conn.execute_batch("PRAGMA query_only=ON")?;
    if version(&conn)?.0 != SCHEMA {
        return Err(Failure::Schema);
    }
    Ok(conn)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Applied {
    Counted,
    Unanchored,
    Inherited,
    Replay,
    Ignored,
    Full,
}
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct Outcome {
    pub counted: u64,
    pub unanchored: u64,
    pub inherited: u64,
    pub replayed: u64,
    pub rejected: u64,
    pub changed: bool,
}
pub(crate) struct Ledger {
    conn: Mutex<Connection>,
    caps: (i64, i64),
}
impl Ledger {
    pub(crate) fn open(path: &Path) -> Result<Self, Failure> {
        Self::open_with(path, (MAX_THREADS, MAX_EVENTS))
    }
    pub(crate) fn open_with(path: &Path, caps: (i64, i64)) -> Result<Self, Failure> {
        if redirected(path) {
            return Err(Failure::Path);
        }
        let conn = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        conn.busy_timeout(Duration::from_millis(2000))?;
        match version(&conn)? {
            (0, 0) => conn.execute_batch(&format!(
                "PRAGMA journal_mode=WAL; BEGIN IMMEDIATE; {DDL} PRAGMA user_version={SCHEMA}; COMMIT;"
            ))?,
            (SCHEMA, _) => {}
            // A newer agent (or something else) owns this file: neither migrate nor reset it.
            _ => return Err(Failure::Schema),
        }
        // Everything here can be rebuilt from retained history; favour cheap commits.
        conn.execute_batch("PRAGMA synchronous=NORMAL")?;
        Ok(Self {
            conn: Mutex::new(conn),
            caps,
        })
    }
    /// One immediate transaction on the single writer connection.
    pub(crate) fn write<T>(
        &self,
        work: impl FnOnce(&Connection, (i64, i64)) -> Result<T, Failure>,
    ) -> Result<T, Failure> {
        let mut conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let value = work(&tx, self.caps)?;
        tx.commit()?;
        Ok(value)
    }
    /// Bookkeeping reads on the writer connection; statistics use `reader` snapshots.
    pub(crate) fn read<T>(
        &self,
        work: impl FnOnce(&Connection) -> Result<T, Failure>,
    ) -> Result<T, Failure> {
        work(&self.conn.lock().unwrap_or_else(|e| e.into_inner()))
    }
    pub(crate) fn apply(&self, items: &[Observation]) -> Result<Outcome, Failure> {
        self.write(|tx, caps| {
            let mut outcome = Outcome::default();
            for item in items {
                observe(tx, item, caps, &mut outcome)?;
            }
            bump(tx, "rejected_full", outcome.rejected)?;
            Ok(outcome)
        })
    }
}
pub(crate) fn bump(tx: &Connection, key: &str, by: u64) -> Result<(), Failure> {
    if by > 0 {
        tx.prepare_cached(
            "INSERT INTO meta(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=value+excluded.value",
        )?
        .execute(params![key, by as i64])?;
    }
    Ok(())
}
fn exists(tx: &Connection, sql: &str, p: impl rusqlite::Params) -> Result<bool, Failure> {
    Ok(tx.prepare_cached(sql)?.query(p)?.next()?.is_some())
}
/// A fork child must not count a point its parent already holds. Run when the
/// parent becomes known after some of the child's records were stored.
fn link(tx: &Connection, child: i64, parent: &str) -> Result<usize, Failure> {
    tx.prepare_cached("UPDATE threads SET parent=?2 WHERE id=?1")?
        .execute(params![child, parent])?;
    Ok(tx.prepare_cached(
        "UPDATE events SET state=2 WHERE thread=?1 AND state!=2 AND EXISTS(SELECT 1 FROM events p WHERE p.thread=(SELECT id FROM threads WHERE thread_id=?2) AND p.cum_input=events.cum_input AND p.cum_output=events.cum_output)",
    )?
    .execute(params![child, parent])?)
}
pub(crate) fn observe(
    tx: &Connection,
    o: &Observation,
    caps: (i64, i64),
    outcome: &mut Outcome,
) -> Result<Applied, Failure> {
    let applied = record(tx, o, caps, outcome)?;
    match applied {
        Applied::Counted => outcome.counted += 1,
        Applied::Unanchored => outcome.unanchored += 1,
        Applied::Inherited => outcome.inherited += 1,
        Applied::Replay => outcome.replayed += 1,
        Applied::Full => outcome.rejected += 1,
        Applied::Ignored => {}
    }
    outcome.changed |= !matches!(applied, Applied::Replay | Applied::Ignored | Applied::Full);
    Ok(applied)
}
fn record(
    tx: &Connection,
    o: &Observation,
    caps: (i64, i64),
    outcome: &mut Outcome,
) -> Result<Applied, Failure> {
    let (ci, co) = (o.cum.input as i64, o.cum.output as i64);
    if ci == 0 && co == 0 {
        return Ok(Applied::Ignored);
    }
    let known = tx
        .prepare_cached("SELECT id,parent FROM threads WHERE thread_id=?1")?
        .query_row([&o.thread], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, Option<String>>(1)?))
        })
        .optional()?;
    let parent = o.parent.as_ref().filter(|p| **p != o.thread);
    let (thread, parent) = match known {
        Some((id, None)) => {
            if let Some(p) = parent {
                outcome.changed |= link(tx, id, p)? > 0;
            }
            (id, parent.cloned())
        }
        Some((id, stored)) => (id, stored),
        None => {
            if tx.query_row("SELECT COALESCE(MAX(id),0) FROM threads", [], |r| {
                r.get::<_, i64>(0)
            })? >= caps.0
            {
                return Ok(Applied::Full);
            }
            tx.prepare_cached("INSERT INTO threads(thread_id,parent,segments) VALUES(?1,?2,0)")?
                .execute(params![o.thread, parent])?;
            (tx.last_insert_rowid(), parent.cloned())
        }
    };
    let moved = o.last.input > 0 || o.last.output > 0;
    let existing = tx
        .prepare_cached(
            "SELECT id,at,model IS NULL,input=0 AND output=0 FROM events WHERE thread=?1 AND cum_input=?2 AND cum_output=?3",
        )?
        .query_row(params![thread, ci, co], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, bool>(2)?,
                r.get::<_, bool>(3)?,
            ))
        })
        .optional()?;
    // The earliest sighting is the original: resume, revert and reconnect re-emit a
    // point later, never earlier. Only history names the model and provider.
    if let Some((id, at, unlabeled, _)) = existing {
        if o.at < at {
            outcome.changed |= tx
                .prepare_cached(
                    "UPDATE events SET at=?2,turn_id=?3,source=?4,model=COALESCE(?5,model),provider=COALESCE(?6,provider) WHERE id=?1",
                )?
                .execute(params![id, o.at, o.turn, o.history, o.model, o.provider])?
                > 0;
        } else if unlabeled && o.model.is_some() {
            outcome.changed |= tx
                .prepare_cached(
                    "UPDATE events SET model=?2,provider=COALESCE(?3,provider) WHERE id=?1",
                )?
                .execute(params![id, o.model, o.provider])?
                > 0;
        }
    }
    // A stored point without a delta (post-compaction context estimate, or a first
    // sighting whose `last` was empty) is only a placeholder for the real request.
    let placeholder = match existing {
        Some((id, .., true)) if moved => Some(id),
        Some(_) => return Ok(Applied::Replay),
        None => None,
    };
    if placeholder.is_none()
        && tx.query_row("SELECT COALESCE(MAX(id),0) FROM events", [], |r| {
            r.get::<_, i64>(0)
        })? >= caps.1
    {
        return Ok(Applied::Full);
    }
    let (pi, po) = (ci - o.last.input as i64, co - o.last.output as i64);
    let fresh = moved && pi == 0 && po == 0;
    let anchor = if moved && !fresh {
        tx.prepare_cached(
            "SELECT segment FROM events WHERE thread=?1 AND cum_input=?2 AND cum_output=?3",
        )?
        .query_row(params![thread, pi, po], |r| r.get::<_, i64>(0))
        .optional()?
    } else {
        None
    };
    let inherited = match &parent {
        Some(p) => exists(
            tx,
            "SELECT 1 FROM events WHERE thread=(SELECT id FROM threads WHERE thread_id=?1) AND cum_input=?2 AND cum_output=?3",
            params![p, ci, co],
        )?,
        None => false,
    };
    let state = if inherited {
        INHERITED
    } else if fresh || anchor.is_some() {
        COUNTED
    } else {
        UNANCHORED
    };
    // Diagnostic only: a segment continues while each point has a single successor.
    // A restart from zero, a branch from an earlier point (revert) and an unknown
    // baseline each open a new one. Sums never depend on it.
    let continues = match anchor {
        Some(segment)
            if !exists(
                tx,
                "SELECT 1 FROM events WHERE thread=?1 AND prev_input=?2 AND prev_output=?3 AND (input>0 OR output>0) AND id IS NOT ?4",
                params![thread, pi, po, placeholder],
            )? =>
        {
            Some(segment)
        }
        _ => None,
    };
    let segment = match continues {
        Some(segment) => segment,
        None => {
            tx.prepare_cached(
                "UPDATE threads SET segments=segments+1 WHERE id=?1 RETURNING segments",
            )?
            .query_row([thread], |r| r.get::<_, i64>(0))?
                - 1
        }
    };
    let write = |sql: &str| -> Result<usize, Failure> {
        Ok(tx.prepare_cached(sql)?.execute(params![
            thread,
            ci,
            co,
            pi,
            po,
            o.last.input as i64,
            o.last.cached as i64,
            o.last.cache_write as i64,
            o.last.output as i64,
            o.last.reasoning as i64,
            state,
            o.last.anomaly(),
            segment,
            o.at,
            o.turn,
            o.model,
            o.provider,
            o.history,
            placeholder
        ])?)
    };
    if placeholder.is_some() {
        write(
            "UPDATE events SET prev_input=?4,prev_output=?5,input=?6,cached=?7,cache_write=?8,output=?9,reasoning=?10,state=?11,anomaly=?12,segment=?13,at=min(at,?14),turn_id=COALESCE(?15,turn_id),model=COALESCE(?16,model),provider=COALESCE(?17,provider),source=?18 WHERE id=?19 AND thread=?1 AND cum_input=?2 AND cum_output=?3",
        )?;
    } else {
        write(
            "INSERT INTO events(thread,cum_input,cum_output,prev_input,prev_output,input,cached,cache_write,output,reasoning,state,anomaly,segment,at,turn_id,model,provider,source,id) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19)",
        )?;
        // Records that arrived before the point they continue from count once it is known.
        tx.prepare_cached(
            "UPDATE events SET state=1 WHERE thread=?1 AND prev_input=?2 AND prev_output=?3 AND state=0 AND (input>0 OR output>0)",
        )?
        .execute(params![thread, ci, co])?;
        // The same rule as `link`, for a parent point that arrives after the child's copy.
        tx.prepare_cached(
            "UPDATE events SET state=2 WHERE cum_input=?2 AND cum_output=?3 AND state!=2 AND thread IN (SELECT id FROM threads WHERE parent=?1)",
        )?
        .execute(params![o.thread, ci, co])?;
    }
    Ok(match state {
        COUNTED => Applied::Counted,
        INHERITED => Applied::Inherited,
        _ => Applied::Unanchored,
    })
}
