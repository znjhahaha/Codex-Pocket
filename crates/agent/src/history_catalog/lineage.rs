//! Read-only counterpart of Codex 0.170.0 rollout_lineage/segment_paging.
//! SQL thread_id is an immutable rollout ID after a revert, not the logical ID.
use super::*;
use std::{
    fs::File,
    io::{BufRead, BufReader, Read},
    path::PathBuf,
};

pub(super) struct Segment {
    pub id: String,
    pub start: i64,
    pub end: i64,
}

fn ids(path: &Path) -> Option<(String, String)> {
    let core = path
        .file_name()?
        .to_str()?
        .strip_prefix("rollout-")?
        .strip_suffix(".jsonl")?;
    chrono::NaiveDateTime::parse_from_str(core.get(..19)?, "%Y-%m-%dT%H-%M-%S").ok()?;
    if core.get(19..20)? != "-" {
        return None;
    }
    let names = core.get(20..)?;
    let (owner, rollout) = names.split_once('_').unwrap_or((names, names));
    Some((
        uuid::Uuid::parse_str(owner).ok()?.to_string(),
        uuid::Uuid::parse_str(rollout).ok()?.to_string(),
    ))
}

fn header(home: &Path, path: &Path, owner: &str) -> Result<Value> {
    let root = dunce::canonicalize(home)?;
    let resolved = dunce::canonicalize(path)?;
    ensure!(
        resolved.starts_with(root.join("sessions"))
            || resolved.starts_with(root.join("archived_sessions")),
        "Rollout is outside selected store"
    );
    let file = File::open(path)?;
    crate::storage::verify_physical_file(path, &file)?;
    let mut line = Vec::new();
    BufReader::new(file.take(1024 * 1024 + 1)).read_until(b'\n', &mut line)?;
    ensure!(
        line.len() <= 1024 * 1024 && line.ends_with(b"\n"),
        "Rollout header incomplete or oversized"
    );
    let record: Value = serde_json::from_slice(&line)?;
    ensure!(
        record["type"] == "session_meta" && record["payload"]["id"] == owner,
        "Rollout identity mismatch"
    );
    ensure!(
        record["payload"]["history_mode"] == "paginated",
        "Unsupported rollout history mode"
    );
    Ok(record["payload"].clone())
}

fn find_in(dir: &Path, id: &str, depth: usize) -> Result<Option<PathBuf>> {
    if !dir.is_dir() {
        return Ok(None);
    }
    let mut found = None;
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        if kind.is_symlink() {
            continue;
        }
        let path = entry.path();
        let candidate = if kind.is_file() && ids(&path).is_some_and(|(_, rollout)| rollout == id) {
            Some(path)
        } else if kind.is_dir() && depth > 0 {
            find_in(&path, id, depth - 1)?
        } else {
            None
        };
        if let Some(candidate) = candidate {
            ensure!(found.is_none(), "Ambiguous rollout ancestor");
            found = Some(candidate);
        }
    }
    Ok(found)
}

fn ancestor(home: &Path, state: &Connection, current: &Path, id: &str) -> Result<PathBuf> {
    // A revert's ancestors are normally siblings. Avoid a store-wide walk on
    // each refresh; search the managed trees only when the sibling is absent.
    if let Some(parent) = current.parent() {
        if let Some(path) = find_in(parent, id, 0)? {
            return Ok(path);
        }
    }
    let selected = state
        .query_row("SELECT rollout_path FROM threads WHERE id=?1", [id], |r| {
            r.get::<_, String>(0)
        })
        .optional()?;
    if let Some(path) = selected
        .map(PathBuf::from)
        .filter(|p| p.is_file() && ids(p).is_some_and(|(_, rollout)| rollout == id))
    {
        return Ok(path);
    }
    for root in [home.join("sessions"), home.join("archived_sessions")] {
        if let Some(path) = find_in(&root, id, 3)? {
            return Ok(path);
        }
    }
    bail!("Rollout ancestor unavailable")
}

/// Newest first; each parent is clipped at the immutable history_base cutoff.
pub(super) fn resolve(
    home: &Path,
    state: &Connection,
    thread: &str,
    raw: &str,
) -> Result<Vec<Segment>> {
    let mut path = PathBuf::from(raw);
    if !path.is_file() {
        // Existing standalone SQLite histories remain readable without JSONL.
        // A selected child needs its header: the logical ID cannot stand in for it.
        ensure!(
            ids(&path).is_none_or(|(_, id)| id == thread),
            "Selected rollout unavailable"
        );
        return Ok(vec![Segment {
            id: thread.into(),
            start: 0,
            end: i64::MAX,
        }]);
    }
    let mut segments = Vec::new();
    let mut seen = BTreeSet::new();
    let mut end = i64::MAX;
    for _ in 0..128 {
        let (owner, id) = ids(&path).context("Unsupported rollout filename")?;
        ensure!(
            !segments.is_empty() || owner == thread,
            "Rollout identity mismatch"
        );
        ensure!(seen.insert(id.clone()), "Rollout lineage cycle");
        let meta = header(home, &path, &owner)?;
        let base = &meta["history_base"];
        let cutoff = if base.is_null() {
            None
        } else {
            Some(
                base["end_ordinal_exclusive"]
                    .as_i64()
                    .filter(|n| *n > 0)
                    .context("Invalid rollout cutoff")?,
            )
        };
        let start = cutoff
            .map(|n| n.checked_add(1).context("Rollout ordinal overflow"))
            .transpose()?
            .unwrap_or(1);
        ensure!(start <= end, "Rollout cutoff outside ancestor");
        segments.push(Segment {
            id: id.clone(),
            start,
            end,
        });
        let Some(cutoff) = cutoff else {
            return Ok(segments);
        };
        let parent = base["thread_id"]
            .as_str()
            .context("Rollout ancestor identity missing")?;
        uuid::Uuid::parse_str(parent).context("Invalid rollout ancestor identity")?;
        // Attached agent conversations hide inherited context, as official
        // history-page reads do. Ordinary user forks retain their ancestors.
        let fork_cutoff = meta["forked_from_id"].as_str().and_then(|fork| {
            meta["forked_from_ordinal_exclusive"]
                .as_i64()
                .or_else(|| (fork == parent || id == owner).then_some(cutoff))
        });
        if meta["attached_thread"] == true && fork_cutoff.is_some_and(|fork| cutoff <= fork) {
            return Ok(segments);
        }
        path = ancestor(home, state, &path, parent)?;
        end = cutoff;
    }
    bail!("Rollout lineage exceeds limit")
}

pub(super) type Turn = (
    String,
    i64,
    String,
    Option<String>,
    Option<i64>,
    Option<i64>,
    Option<i64>,
);

pub(super) fn turns(
    history: &Connection,
    segments: &[Segment],
    before: i64,
    limit: usize,
) -> Result<Vec<Turn>> {
    let mut turns = Vec::new();
    for (index, segment) in segments.iter().enumerate() {
        let mut sql = String::from(
            "SELECT turn_id,rollout_ordinal,status,error_json,started_at,completed_at,duration_ms FROM thread_turns AS t WHERE thread_id=? AND rollout_ordinal>=? AND rollout_ordinal<? AND rollout_ordinal<?",
        );
        let mut values: Vec<rusqlite::types::Value> = vec![
            segment.id.clone().into(),
            segment.start.into(),
            segment.end.into(),
            before.into(),
        ];
        // A resumed turn can appear in both segments. Its newer status wins,
        // even when that row was on the preceding page.
        for newer in &segments[..index] {
            sql.push_str(" AND NOT EXISTS (SELECT 1 FROM thread_turns AS n WHERE n.thread_id=? AND n.turn_id=t.turn_id AND n.rollout_ordinal>=? AND n.rollout_ordinal<?)");
            values.extend([
                newer.id.clone().into(),
                newer.start.into(),
                newer.end.into(),
            ]);
        }
        sql.push_str(" ORDER BY rollout_ordinal DESC,turn_id DESC LIMIT ?");
        values.push(((limit - turns.len()) as i64).into());
        turns.extend(
            history
                .prepare(&sql)?
                .query_map(rusqlite::params_from_iter(values), |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                        r.get(5)?,
                        r.get(6)?,
                    ))
                })?
                .collect::<rusqlite::Result<Vec<Turn>>>()?,
        );
        if turns.len() == limit {
            break;
        }
    }
    Ok(turns)
}

pub(super) fn items(history: &Connection, segments: &[Segment], turn: &str) -> Result<Vec<String>> {
    let mut items = std::collections::BTreeMap::new();
    for segment in segments {
        let mut stmt=history.prepare("SELECT item_id,rollout_ordinal,item_json FROM thread_items WHERE thread_id=?1 AND turn_id=?2 AND rollout_ordinal>=?3 AND rollout_ordinal<?4 ORDER BY rollout_ordinal,item_id LIMIT 10001")?;
        let rows = stmt.query_map(params![segment.id, turn, segment.start, segment.end], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, String>(2)?,
            ))
        })?;
        for row in rows {
            let (id, ordinal, raw) = row?;
            items.entry(id.clone()).or_insert((ordinal, id, raw));
            ensure!(items.len() <= 10000, "Native turn exceeds item limit");
        }
    }
    let mut rows = items.into_values().collect::<Vec<_>>();
    rows.sort_by(|a, b| (&a.0, &a.1).cmp(&(&b.0, &b.1)));
    Ok(rows.into_iter().map(|(_, _, raw)| raw).collect())
}
