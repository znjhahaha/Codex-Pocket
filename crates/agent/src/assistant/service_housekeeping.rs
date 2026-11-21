//! Typed coordinator housekeeping. User provenance and controller grants are
//! authority; model-supplied paths, memories and civil-time rules never are.
use super::*;
use serde_json::{Value, json};

fn schedule_view(s: &Schedule) -> Value {
    let kind = match s.spec.kind {
        ScheduleKind::Once { at_ms } => SchedulePattern::Once { at_ms },
        ScheduleKind::Daily { hour, minute } => SchedulePattern::Daily { hour, minute },
        ScheduleKind::Weekly {
            weekday,
            hour,
            minute,
        } => SchedulePattern::Weekly {
            weekday,
            hour,
            minute,
        },
        ScheduleKind::Interval {
            anchor_ms,
            every_ms,
        } => SchedulePattern::Interval {
            anchor_ms,
            every_ms,
        },
    };
    json!({"id":s.id,"kind":kind,"timezone":s.spec.zone.name,"prompt":collaboration::clip_bytes(&s.spec.prompt,4096),"truncated":s.spec.prompt.len()>4096,"directory":s.spec.directory,"writes":s.spec.writes,"next_ms":s.next_ms,"missed":s.missed,"error":s.error})
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum SchedulePattern {
    Once { at_ms: i64 },
    Daily { hour: u8, minute: u8 },
    Weekly { weekday: u8, hour: u8, minute: u8 },
    Interval { anchor_ms: i64, every_ms: i64 },
}
impl SchedulePattern {
    fn kind(&self) -> ScheduleKind {
        match *self {
            Self::Once { at_ms } => ScheduleKind::Once { at_ms },
            Self::Daily { hour, minute } => ScheduleKind::Daily { hour, minute },
            Self::Weekly {
                weekday,
                hour,
                minute,
            } => ScheduleKind::Weekly {
                weekday,
                hour,
                minute,
            },
            Self::Interval {
                anchor_ms,
                every_ms,
            } => ScheduleKind::Interval {
                anchor_ms,
                every_ms,
            },
        }
    }
    fn validate(&self) -> Result<()> {
        match *self {
            Self::Once { at_ms } => clock(at_ms),
            Self::Daily { hour, minute } => {
                ensure!(hour < 24 && minute < 60, "Invalid daily time");
                Ok(())
            }
            Self::Weekly {
                weekday,
                hour,
                minute,
            } => {
                ensure!(
                    weekday < 7 && hour < 24 && minute < 60,
                    "Invalid weekly time"
                );
                Ok(())
            }
            Self::Interval {
                anchor_ms,
                every_ms,
            } => {
                clock(anchor_ms)?;
                ensure!(
                    (1_000..=366 * 86_400_000i64).contains(&every_ms),
                    "Invalid interval"
                );
                Ok(())
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum HousekeepingTool {
    ScheduleCreate {
        user_message_id: String,
        timezone: String,
        kind: SchedulePattern,
        prompt: String,
        directory: PathBuf,
        writes: bool,
    },
    ScheduleDelete {
        user_message_id: String,
        schedule_id: String,
    },
    ScheduleList {
        limit: usize,
        #[serde(default)]
        after: Option<String>,
    },
    MemoryRead {
        limit: usize,
        #[serde(default)]
        after: Option<String>,
        #[serde(default)]
        memory_id: Option<String>,
        #[serde(default)]
        offset: usize,
    },
    MemoryAdd {
        user_message_id: String,
        text: String,
        #[serde(default)]
        scope: Option<String>,
    },
    MemoryUpdate {
        user_message_id: String,
        memory_id: String,
        text: String,
    },
    MemoryDelete {
        user_message_id: String,
        memory_id: String,
    },
}
impl HousekeepingTool {
    pub(super) fn mutates(&self) -> bool {
        self.user_message_id().is_some()
    }
    pub fn validate(&self) -> Result<()> {
        match self {
            Self::ScheduleCreate {
                user_message_id,
                timezone,
                kind,
                prompt,
                directory,
                ..
            } => {
                text(user_message_id, 256)?;
                text(timezone, 256)?;
                text(prompt, 65536)?;
                text(&directory.to_string_lossy(), 32768)?;
                ensure!(
                    directory.is_absolute(),
                    "Schedule directory must be absolute"
                );
                kind.validate()?;
            }
            Self::ScheduleDelete {
                user_message_id,
                schedule_id,
            } => {
                text(user_message_id, 256)?;
                text(schedule_id, 256)?;
            }
            Self::MemoryAdd {
                user_message_id,
                text: value,
                scope,
            } => {
                ensure!(scope.as_deref().is_none_or(|s|matches!(s,"personal"|"facts"|"project")),"Unknown memory scope");
                text(user_message_id, 256)?;
                text(value, 16384)?;
            }
            Self::MemoryUpdate {
                user_message_id,
                memory_id,
                text: value,
            } => {
                text(user_message_id, 256)?;
                text(memory_id, 256)?;
                text(value, 16384)?;
            }
            Self::MemoryDelete {
                user_message_id,
                memory_id,
            } => {
                text(user_message_id, 256)?;
                text(memory_id, 256)?;
            }
            Self::ScheduleList { limit, after } | Self::MemoryRead { limit, after, .. } => {
                ensure!((1..=100).contains(limit), "Page limit must be 1..100");
                if let Some(id) = after {
                    text(id, 256)?;
                }
            }
        }
        if let Self::MemoryRead {
            memory_id,
            offset,
            after,
            ..
        } = self
        {
            ensure!(*offset <= 16384, "Memory offset out of range");
            ensure!(
                memory_id.is_some() || *offset == 0,
                "Offset needs memory_id"
            );
            ensure!(
                memory_id.is_none() || after.is_none(),
                "Choose memory_id or after"
            );
            if let Some(id) = memory_id {
                text(id, 256)?;
            }
        }
        Ok(())
    }
    fn user_message_id(&self) -> Option<&str> {
        match self {
            Self::ScheduleCreate {
                user_message_id, ..
            }
            | Self::ScheduleDelete {
                user_message_id, ..
            }
            | Self::MemoryAdd {
                user_message_id, ..
            }
            | Self::MemoryUpdate {
                user_message_id, ..
            }
            | Self::MemoryDelete {
                user_message_id, ..
            } => Some(user_message_id),
            _ => None,
        }
    }
}

impl<E: Executor> AssistantService<E> {
    /// Read durable effect receipts, never an optimistic in-memory journal.
    /// A later service/answer save failure cannot turn a committed effect into
    /// a native rejection that invites a fresh call ID.
    pub fn tool_effect_committed(
        &self,
        scope: &NativeMapping,
        call_id: &str,
        call: &ToolCall,
    ) -> Result<bool> {
        let ToolCall::Housekeeping(tool) = call else {
            return Ok(false);
        };
        if !tool.mutates() {
            return Ok(false);
        }
        let op = self
            .state
            .operations
            .values()
            .find(|op| op.role == Role::Coordinator && op.mapping.as_ref() == Some(scope))
            .context("Unowned housekeeping receipt")?;
        let journal_id = format!("tool:{}:{call_id}", op.id);
        let effect_id = format!("housekeeping:{}", fingerprint(&journal_id)?);
        match tool {
            HousekeepingTool::MemoryAdd { .. }
            | HousekeepingTool::MemoryUpdate { .. }
            | HousekeepingTool::MemoryDelete { .. } => self.store.command_applied(&effect_id),
            HousekeepingTool::ScheduleCreate { .. } | HousekeepingTool::ScheduleDelete { .. } => {
                let key = if matches!(tool, HousekeepingTool::ScheduleCreate { .. }) {
                    &journal_id
                } else {
                    &effect_id
                };
                Ok(self.store.db.query_row(
                    "SELECT EXISTS(SELECT 1 FROM assistant_service_state,json_each(data,'$.journal') AS journal WHERE assistant_id=?1 AND journal.key=?2)",
                    params![self.store.assistant_id, key], |row| row.get(0),
                )?)
            }
            _ => Ok(false),
        }
    }

    pub(super) fn housekeeping_tool(
        &mut self,
        op: &Operation,
        journal_id: &str,
        hash: &str,
        call: HousekeepingTool,
        now: i64,
    ) -> Result<Value> {
        call.validate()?;
        if let Some(id) = call.user_message_id() {
            ensure!(
                op.user_message_ids.iter().any(|actual| actual == id)
                    && self
                        .conversation_entry(id)
                        .is_some_and(|entry| entry.role == "User"),
                "Housekeeping needs a current explicit user handoff"
            );
        }
        // A separate stable command receipt covers the crash between the local
        // effect and the generic tool answer journal. It never includes memory text.
        let effect_id = format!("housekeeping:{}", fingerprint(&journal_id)?);
        match call {
            HousekeepingTool::ScheduleCreate {
                timezone,
                kind,
                prompt,
                directory,
                writes,
                ..
            } => {
                let directory = canonical_directory(&directory)?;
                ensure!(
                    op.authorized_directories
                        .iter()
                        .any(|root| canonical_directory(root).is_ok_and(
                            |actual| actual == *root && within_directory(&directory, root)
                        )),
                    "Schedule directory not authorized by this handoff"
                );
                self.executor.validate_directory(&directory, writes)?;
                let spec = ScheduleSpec {
                    kind: kind.kind(),
                    zone: crate::assistant_product::zone_rules(&timezone, now)?,
                    prompt,
                    directory,
                    writes,
                };
                spec.validate()?;
                ensure!(
                    spec.next_after(now - 1)?.is_some(),
                    "Schedule has no future occurrence"
                );
                // Persist the schedule with the original typed-call fingerprint,
                // atomically. Replays never recompute timezone/next due or insert.
                let result = self.insert_schedule(journal_id, hash.into(), spec, now, |s| {
                    Ok(schedule_view(s))
                })?;
                Ok(schedule_view(&result))
            }
            HousekeepingTool::ScheduleDelete { schedule_id, .. } => {
                self.remove_schedule(&effect_id, &schedule_id)?;
                Ok(json!({"deleted":schedule_id}))
            }
            HousekeepingTool::ScheduleList { limit, after } => {
                let ids = self.state.schedules.keys().collect::<Vec<_>>();
                let start = match after {
                    Some(id) => {
                        ids.iter()
                            .position(|key| **key == id)
                            .context("Schedule cursor no longer exists")?
                            + 1
                    }
                    None => 0,
                };
                let mut schedules = vec![];
                let mut end = start;
                for id in ids.iter().skip(start).take(limit) {
                    let s = &self.state.schedules[*id];
                    // Detailed civil rules are host-owned; list a bounded prompt
                    // preview plus the immutable zone and execution contract.
                    let item = schedule_view(s);
                    if serde_json::to_vec(&schedules)?.len() + serde_json::to_vec(&item)?.len()
                        > 48000
                    {
                        break;
                    }
                    schedules.push(item);
                    end += 1;
                }
                ensure!(
                    end > start || start == ids.len(),
                    "Schedule entry exceeds tool page limit"
                );
                Ok(json!({"schedules":schedules,"nextAfter":(end<ids.len()).then(|| ids[end-1])}))
            }
            HousekeepingTool::MemoryAdd { text, user_message_id, scope } => Ok(serde_json::to_value(self.remember_with_source(
                &effect_id, &text, &user_message_id, scope.as_deref().unwrap_or("personal"), true, now,
            )?)?),
            HousekeepingTool::MemoryUpdate {
                memory_id, text, ..
            } => Ok(serde_json::to_value(self.apply(
                &effect_id,
                Command::EditMemory { memory_id, text },
                now,
            )?)?),
            HousekeepingTool::MemoryDelete { memory_id, .. } => Ok(serde_json::to_value(
                self.apply(&effect_id, Command::DeleteMemory { memory_id }, now)?,
            )?),
            HousekeepingTool::MemoryRead { .. } => unreachable!(),
        }
    }

    pub(super) fn memory_read_tool(
        &mut self,
        scope: &NativeMapping,
        call_id: &str,
        journal_id: &str,
        hash: &str,
        call: &HousekeepingTool,
    ) -> Result<Value> {
        call.validate()?;
        self.replay(journal_id, hash)?; // identity check, without obsolete memory copies
        if self.executor.reconcile_tool_answer(scope, call_id)? {
            return Ok(json!({"read":true}));
        }
        let HousekeepingTool::MemoryRead {
            limit,
            after,
            memory_id,
            offset,
        } = call
        else {
            unreachable!()
        };
        let page = self
            .store
            .memory_page(after.as_deref(), memory_id.as_deref(), *limit + 1)?;
        let mut memories = vec![];
        for memory in page.iter().take(*limit) {
            ensure!(
                *offset <= memory.text.len() && memory.text.is_char_boundary(*offset),
                "Memory offset must be a UTF-8 boundary"
            );
            let remaining = &memory.text[*offset..];
            let chunk = collaboration::clip_bytes(remaining, 4096);
            let next = *offset + chunk.len();
            let item = json!({"id":memory.id,"text":chunk,"nextOffset":(next<memory.text.len()).then_some(next)});
            if serde_json::to_vec(&memories)?.len() + serde_json::to_vec(&item)?.len() > 48000 {
                break;
            }
            memories.push(item);
        }
        let next = (memories.len() < page.len()).then(|| memories.last().unwrap()["id"].clone());
        let result = json!({"memories":memories,"nextAfter":next});
        // A read receipt retains only a hash. Answer uncertainty lives in Executor;
        // edits/deletes cannot leave a second durable text copy in the tool journal.
        self.record(
            journal_id,
            hash.into(),
            json!({"readFingerprint":fingerprint(&result)?}),
        );
        self.save()?;
        self.executor.tools(ToolResponse {
            scope: scope.clone(),
            call_id: call_id.into(),
            result: result.clone(),
        })?;
        Ok(result)
    }
}
