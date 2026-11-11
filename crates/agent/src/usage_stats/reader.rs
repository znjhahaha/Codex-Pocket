use super::{
    aggregate::{Aggregate, Cost, Record, normalize},
    decimal::Decimal,
    *,
};
use rusqlite::{Connection, ErrorCode, OpenFlags, Row, params, types::ValueRef};
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    path::Component,
    time::{Duration, Instant},
};

#[derive(Debug, Clone, Copy)]
pub(crate) enum Failure {
    Missing,
    Locked,
    Schema,
    Path,
    Limit,
    Overflow,
    InvalidRow,
    Unavailable,
}
impl From<rusqlite::Error> for Failure {
    fn from(e: rusqlite::Error) -> Self {
        match e.sqlite_error_code() {
            Some(ErrorCode::DatabaseBusy | ErrorCode::DatabaseLocked) => Self::Locked,
            Some(ErrorCode::OperationInterrupted) => Self::Limit,
            Some(ErrorCode::NotADatabase | ErrorCode::DatabaseCorrupt) => Self::Schema,
            _ => Self::Unavailable,
        }
    }
}
impl Failure {
    pub fn details(self) -> (Availability, &'static str, &'static str) {
        match self {
            Self::Missing => (
                Availability::Missing,
                "database_missing",
                "The database file is missing.",
            ),
            Self::Locked => (
                Availability::Locked,
                "database_locked",
                "The database is locked; retry later.",
            ),
            Self::Schema => (
                Availability::UnsupportedSchema,
                "unsupported_schema",
                "The database schema is unsupported.",
            ),
            Self::Path => (
                Availability::InvalidPath,
                "invalid_path",
                "The physical local database path could not be verified.",
            ),
            Self::Limit => (
                Availability::Rejected,
                "query_limit",
                "The query exceeded the row, group, or execution budget.",
            ),
            Self::Overflow => (
                Availability::Rejected,
                "arithmetic_overflow",
                "An exact aggregate exceeded its numeric range.",
            ),
            Self::InvalidRow => (
                Availability::Unavailable,
                "invalid_row",
                "A required record identity or timestamp is invalid.",
            ),
            Self::Unavailable => (
                Availability::Unavailable,
                "database_unavailable",
                "The database could not be read.",
            ),
        }
    }
}
fn io_failure(e: std::io::Error) -> Failure {
    if e.kind() == std::io::ErrorKind::NotFound {
        Failure::Missing
    } else {
        Failure::Unavailable
    }
}

fn verify_local(path: &Path) -> Result<(), Failure> {
    if !path.is_absolute() || path.components().any(|c| matches!(c, Component::ParentDir)) {
        return Err(Failure::Path);
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::GetDriveTypeW;
        let text = path.as_os_str().to_string_lossy();
        if text.starts_with(r"\\") || text.contains(':') && text[2..].contains(':') {
            return Err(Failure::Path);
        }
        let root: Vec<u16> = path
            .components()
            .take(2)
            .collect::<PathBuf>()
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect();
        // DRIVE_FIXED or DRIVE_REMOVABLE only; remote/mapped/unknown drives are rejected.
        if !matches!(unsafe { GetDriveTypeW(root.as_ptr()) }, 2 | 3) {
            return Err(Failure::Path);
        }
    }
    for ancestor in path.ancestors() {
        let metadata = std::fs::symlink_metadata(ancestor).map_err(io_failure)?;
        if metadata.is_symlink() {
            return Err(Failure::Path);
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            if metadata.file_attributes() & 0x400 != 0 {
                return Err(Failure::Path);
            }
        }
    }
    Ok(())
}
fn pin(path: &Path) -> Result<File, Failure> {
    verify_local(path)?;
    if !std::fs::symlink_metadata(path)
        .map_err(io_failure)?
        .is_file()
    {
        return Err(Failure::Path);
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.share_mode(0x1 | 0x2);
    }
    let file = options.open(path).map_err(io_failure)?;
    if !file.metadata().map_err(io_failure)?.is_file() {
        return Err(Failure::Path);
    }
    crate::storage::verify_physical_file(path, &file).map_err(|_| Failure::Path)?;
    Ok(file)
}
pub(super) fn read_projection(path: &Path, limit: u64) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut file = pin(path).ok()?;
    if file.metadata().ok()?.len() > limit {
        return None;
    }
    let mut bytes = vec![];
    (&mut file)
        .take(limit.checked_add(1)?)
        .read_to_end(&mut bytes)
        .ok()?;
    verify_local(path).ok()?;
    crate::storage::verify_physical_file(path, &file).ok()?;
    (bytes.len() as u64 <= limit).then_some(bytes)
}
struct Pins {
    files: Vec<(PathBuf, File)>,
}
impl Pins {
    fn new(path: &Path) -> Result<Self, Failure> {
        let mut pins = Self {
            files: vec![(path.to_owned(), pin(path)?)],
        };
        pins.sidecars(path)?;
        Ok(pins)
    }
    fn sidecars(&mut self, path: &Path) -> Result<(), Failure> {
        for suffix in ["-wal", "-shm"] {
            let mut name = path.as_os_str().to_os_string();
            name.push(suffix);
            let sidecar = PathBuf::from(name);
            if self.files.iter().any(|(p, _)| p == &sidecar) {
                continue;
            }
            match std::fs::symlink_metadata(&sidecar) {
                Ok(_) => self.files.push((sidecar.clone(), pin(&sidecar)?)),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(io_failure(e)),
            }
        }
        Ok(())
    }
    fn verify(&mut self, path: &Path) -> Result<(), Failure> {
        self.sidecars(path)?;
        for (p, file) in &self.files {
            verify_local(p)?;
            crate::storage::verify_physical_file(p, file).map_err(|_| Failure::Path)?;
        }
        Ok(())
    }
}

struct Budget {
    deadline: Instant,
    remaining: usize,
}
struct Progress<'a> {
    conn: &'a Connection,
    _budget: Box<Budget>,
}
unsafe extern "C" fn progress(context: *mut std::ffi::c_void) -> std::ffi::c_int {
    // The Box lives until the handler is detached by Progress::drop. SQLite calls
    // synchronously on this connection's query thread; the pointer is never shared.
    let budget = unsafe { &mut *context.cast::<Budget>() };
    if budget.remaining <= 1 || Instant::now() >= budget.deadline {
        return 1;
    }
    budget.remaining -= 1;
    0
}
impl<'a> Progress<'a> {
    fn check(&self) -> Result<(), Failure> {
        if Instant::now() >= self._budget.deadline {
            Err(Failure::Limit)
        } else {
            Ok(())
        }
    }
    fn new(conn: &'a Connection) -> Self {
        let mut budget = Box::new(Budget {
            deadline: Instant::now() + Duration::from_secs(2),
            remaining: 5000,
        });
        unsafe {
            rusqlite::ffi::sqlite3_progress_handler(
                conn.handle(),
                1000,
                Some(progress),
                (&mut *budget as *mut Budget).cast(),
            );
        }
        Self {
            conn,
            _budget: budget,
        }
    }
}
impl Drop for Progress<'_> {
    fn drop(&mut self) {
        unsafe {
            rusqlite::ffi::sqlite3_progress_handler(
                self.conn.handle(),
                0,
                None,
                std::ptr::null_mut(),
            );
        }
    }
}

fn table(conn: &Connection, name: &str) -> Result<bool, Failure> {
    let mut stmt =
        conn.prepare("SELECT type FROM sqlite_master WHERE name=?1 AND type IN ('table','view')")?;
    let mut rows = stmt.query([name])?;
    match rows.next()? {
        None => Ok(false),
        Some(r) => {
            if r.get::<_, String>(0)? != "table" {
                return Err(Failure::Schema);
            }
            Ok(true)
        }
    }
}
fn columns(conn: &Connection, table: &str, required: &[(&str, &str)]) -> Result<(), Failure> {
    // The WHERE clause projects only allowed column metadata, even for providers.
    let names = required
        .iter()
        .map(|(name, _)| format!("'{name}'"))
        .collect::<Vec<_>>()
        .join(",");
    let mut stmt = conn.prepare(&format!(
        "SELECT name,type FROM pragma_table_info(?1) WHERE name IN ({names})"
    ))?;
    let columns = stmt
        .query_map([table], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })?
        .collect::<Result<BTreeMap<_, _>, _>>()?;
    if required.iter().any(|(name, kind)| {
        columns
            .get(*name)
            .is_none_or(|s| !s.eq_ignore_ascii_case(kind))
    }) {
        return Err(Failure::Schema);
    }
    Ok(())
}
fn schema(conn: &Connection, version: &mut Option<i64>) -> Result<(bool, bool), Failure> {
    let observed: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    *version = Some(observed);
    if observed != 24 || !table(conn, "proxy_request_logs")? {
        return Err(Failure::Schema);
    }
    columns(
        conn,
        "proxy_request_logs",
        &[
            ("request_id", "TEXT"),
            ("provider_id", "TEXT"),
            ("app_type", "TEXT"),
            ("model", "TEXT"),
            ("input_tokens", "INTEGER"),
            ("output_tokens", "INTEGER"),
            ("cache_read_tokens", "INTEGER"),
            ("cache_creation_tokens", "INTEGER"),
            ("input_token_semantics", "INTEGER"),
            ("input_cost_usd", "TEXT"),
            ("output_cost_usd", "TEXT"),
            ("cache_read_cost_usd", "TEXT"),
            ("cache_creation_cost_usd", "TEXT"),
            ("total_cost_usd", "TEXT"),
            ("status_code", "INTEGER"),
            ("created_at", "INTEGER"),
            ("data_source", "TEXT"),
            ("latency_ms", "INTEGER"),
            ("first_token_ms", "INTEGER"),
            ("is_streaming", "INTEGER"),
        ],
    )?;
    let (primary,count):(i64,i64)=conn.query_row("SELECT COALESCE(MAX(CASE WHEN name='request_id' THEN pk END),0),SUM(CASE WHEN pk>0 THEN 1 ELSE 0 END) FROM pragma_table_info('proxy_request_logs')",[],|r|Ok((r.get(0)?,r.get(1)?)))?;
    if primary != 1 || count != 1 {
        return Err(Failure::Schema);
    }
    let providers = table(conn, "providers")?;
    if providers {
        columns(
            conn,
            "providers",
            &[("id", "TEXT"), ("app_type", "TEXT"), ("name", "TEXT")],
        )?;
    }
    Ok((providers, table(conn, "usage_daily_rollups")?))
}
fn identity(row: &Row<'_>, at: usize) -> Result<String, Failure> {
    match row.get_ref(at)? {
        ValueRef::Text(v) if !v.is_empty() && v.len() <= 256 => std::str::from_utf8(v)
            .map(str::to_owned)
            .map_err(|_| Failure::InvalidRow),
        _ => Err(Failure::InvalidRow),
    }
}
fn integer(row: &Row<'_>, at: usize) -> Option<i64> {
    match row.get_ref(at).ok()? {
        ValueRef::Integer(v) => Some(v),
        _ => None,
    }
}
fn cost(row: &Row<'_>, at: usize) -> Result<Cost, Failure> {
    Ok(match row.get_ref(at)? {
        ValueRef::Null => Cost::Missing,
        ValueRef::Text(v) if v.len() <= 128 => match std::str::from_utf8(v) {
            Ok(s) if s.trim().is_empty() => Cost::Missing,
            Ok(s) => Decimal::parse(s).map_or(Cost::Invalid, Cost::Known),
            Err(_) => Cost::Invalid,
        },
        _ => Cost::Invalid,
    })
}
fn record(row: &Row<'_>, q: &UsageQuery) -> Result<Record, Failure> {
    let provider_id = identity(row, 0)?;
    let model = identity(row, 1)?;
    let at = integer(row, 2)
        .filter(|v| *v >= q.start_at && *v < q.end_at)
        .ok_or(Failure::InvalidRow)?;
    let status = integer(row, 3).ok_or(Failure::InvalidRow)?;
    let parsed = (4..8)
        .map(|i| integer(row, i).and_then(|v| u64::try_from(v).ok()))
        .collect::<Option<Vec<_>>>();
    let (tokens, token_anomaly) = match (parsed, integer(row, 8)) {
        (Some(v), Some(semantics)) => {
            normalize(&q.filters.app_type, semantics, v[0], v[2], v[3], v[1])
        }
        _ => (None, true),
    };
    Ok(Record {
        at,
        provider_id,
        model,
        status,
        tokens,
        token_anomaly,
        costs: [
            cost(row, 9)?,
            cost(row, 10)?,
            cost(row, 11)?,
            cost(row, 12)?,
            cost(row, 13)?,
        ],
        measurements: measurements(row, q)?,
    })
}
fn measured_integer(
    row: &Row<'_>,
    at: usize,
    min: i64,
) -> Result<super::performance::Observation<u64>, Failure> {
    use super::performance::Observation;
    Ok(match row.get_ref(at)? {
        ValueRef::Null => Observation::Missing,
        ValueRef::Integer(v) if v >= min => Observation::Sample(v as u64),
        _ => Observation::Invalid,
    })
}
fn measurements(
    row: &Row<'_>,
    q: &UsageQuery,
) -> Result<super::performance::Measurements, Failure> {
    use super::performance::{Measurements, Observation::*};
    if q.filters.data_source != DataSource::Proxy {
        return Ok(Measurements {
            ttft: Excluded,
            duration: Excluded,
            speed: Excluded,
        });
    }
    let duration = measured_integer(row, 14, 1)?;
    let streaming = measured_integer(row, 16, 0)?;
    let raw_ttft = measured_integer(row, 15, 0)?;
    let ttft = match (streaming, &duration, raw_ttft) {
        (Sample(0), _, _) => Excluded,
        (Missing, _, _) => Missing,
        (Sample(1), Sample(total), Sample(first)) if first <= *total => Sample(first),
        (Sample(1), Invalid, _) | (Sample(1), _, Invalid) => Invalid,
        (Sample(1), Missing, _) | (Sample(1), _, Missing) => Missing,
        _ => Invalid,
    };
    let output = measured_integer(row, 5, 0)?;
    let speed = match (&ttft, &duration, output) {
        (Excluded, _, _) => Excluded,
        (Invalid, _, _) | (_, _, Invalid) => Invalid,
        (Missing, _, _) | (_, _, Missing) => Missing,
        (Sample(first), Sample(total), Sample(tokens)) => {
            let generation = total - first;
            if tokens >= 100 && generation >= 100 {
                Sample((tokens, generation))
            } else {
                Excluded
            }
        }
        _ => Invalid,
    };
    Ok(Measurements {
        ttft,
        duration,
        speed,
    })
}
pub(crate) struct ReadResult {
    pub data: UsageData,
    pub first: Option<i64>,
    pub last: Option<i64>,
    pub rollups: bool,
}
pub(crate) fn connect(path: &Path) -> Result<Connection, Failure> {
    let conn = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    conn.busy_timeout(Duration::from_millis(250))?;
    conn.execute_batch("PRAGMA query_only=ON")?;
    if conn.query_row("PRAGMA query_only", [], |r| r.get::<_, i64>(0))? != 1
        || !conn.is_readonly(rusqlite::DatabaseName::Main)?
    {
        return Err(Failure::Unavailable);
    }
    Ok(conn)
}
pub(crate) fn read(
    path: &Path,
    q: &UsageQuery,
    version: &mut Option<i64>,
) -> Result<ReadResult, Failure> {
    let mut pins = Pins::new(path)?;
    let conn = connect(path)?;
    pins.verify(path)?;
    let _progress = Progress::new(&conn);
    let tx = conn.unchecked_transaction()?;
    let (providers, rollups) = schema(&tx, version)?;
    let mut stmt=tx.prepare("SELECT provider_id,model,created_at,status_code,input_tokens,output_tokens,cache_read_tokens,cache_creation_tokens,input_token_semantics,input_cost_usd,output_cost_usd,cache_read_cost_usd,cache_creation_cost_usd,total_cost_usd,latency_ms,first_token_ms,is_streaming FROM proxy_request_logs WHERE app_type=?1 AND COALESCE(data_source,'proxy')=?2 AND created_at>=?3 AND created_at<?4 AND (?5 IS NULL OR provider_id=?5) AND (?6 IS NULL OR model=?6) AND (?7='all' OR (?7='success' AND status_code>=200 AND status_code<300) OR (?7='error' AND (status_code<200 OR status_code>=300))) LIMIT ?8")?;
    let status = match q.filters.status {
        StatusFilter::All => "all",
        StatusFilter::Success => "success",
        StatusFilter::Error => "error",
    };
    let mut rows = stmt.query(params![
        q.filters.app_type,
        q.filters.data_source.as_str(),
        q.start_at,
        q.end_at,
        q.filters.provider_id,
        q.filters.model,
        status,
        super::MAX_ROWS as i64 + 1
    ])?;
    let mut aggregate = Aggregate::new(q);
    while let Some(row) = rows.next()? {
        _progress.check()?;
        aggregate.push(record(row, q)?, q)?;
    }
    drop(rows);
    drop(stmt);
    let mut names = BTreeMap::new();
    if providers {
        let mut stmt =
            tx.prepare("SELECT name FROM providers WHERE id=?1 AND app_type=?2 LIMIT 2")?;
        for id in aggregate.provider_ids() {
            _progress.check()?;
            let mut rows = stmt.query(params![id, q.filters.app_type])?;
            let name = match rows.next()? {
                None => None,
                Some(row) => match row.get_ref(0)? {
                    ValueRef::Null => None,
                    ValueRef::Text(v) if v.len() <= 256 => Some(
                        std::str::from_utf8(v)
                            .map_err(|_| Failure::InvalidRow)?
                            .to_owned(),
                    ),
                    _ => return Err(Failure::InvalidRow),
                },
            };
            if rows.next()?.is_some() {
                return Err(Failure::Schema);
            }
            names.insert(id.clone(), name);
        }
    }
    let first = aggregate.first;
    let last = aggregate.last;
    let data = aggregate.finish(q, names);
    tx.commit()?;
    pins.verify(path)?;
    _progress.check()?;
    Ok(ReadResult {
        data,
        first,
        last,
        rollups,
    })
}

/// Schema-only source health, independent of all remote statistical filters.
pub(crate) fn probe(path: &Path, version: &mut Option<i64>) -> Result<(), Failure> {
    let mut pins = Pins::new(path)?;
    let conn = connect(path)?;
    pins.verify(path)?;
    let progress = Progress::new(&conn);
    let tx = conn.unchecked_transaction()?;
    schema(&tx, version)?;
    tx.commit()?;
    pins.verify(path)?;
    progress.check()?;
    Ok(())
}
