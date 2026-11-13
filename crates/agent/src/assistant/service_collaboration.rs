//! Conversation-local membership and durable delegation. Native execution stays in Executor.
use super::*;
use serde_json::{Value, json};
use std::collections::BTreeSet;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct TaskAssignment {
    pub member_id: Option<String>,
    pub group_id: Option<String>,
    #[serde(default)]
    pub dependencies: Vec<String>,
    #[serde(default)]
    pub completion_criteria: String,
    #[serde(default)]
    pub continue_from_task: Option<String>,
    /// Root of an anonymous continuation chain. Older state derives it from
    /// validated predecessor links before reuse.
    #[serde(default)]
    pub lineage_root: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Member {
    pub id: String,
    pub name: String,
    pub duty: String,
    pub main_thread_id: String,
    pub directory: PathBuf,
    pub active: bool,
    pub thread_id: Option<String>,
    #[serde(default)]
    pub previous_threads: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TaskGroup {
    pub id: String,
    pub title: String,
    pub origin_operation: String,
    pub requires_summary: bool,
    pub sealed: bool,
    pub summary_enqueued: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ConversationEntry {
    pub id: String,
    pub role: String,
    pub text: String,
    pub at_ms: i64,
    pub member_id: Option<String>,
    pub task_id: Option<String>,
    pub group_id: Option<String>,
    pub source_thread_id: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Delivery {
    pub id: String,
    /// Submission order is independent of user-supplied IDs and wall clocks.
    #[serde(default)]
    pub sequence: u64,
    /// Accepted work for an unsettled group, retained through fallback delivery.
    #[serde(default)]
    pub group_id: Option<String>,
    pub message: String,
    pub member_id: Option<String>,
    pub task_id: Option<String>,
    pub directory: PathBuf,
    pub writes: bool,
    pub at_ms: i64,
    pub status: String,
    pub delivered_task_id: Option<String>,
    #[serde(default)]
    pub operation_id: Option<String>,
    #[serde(default)]
    pub reassign: bool,
    #[serde(default)]
    pub cancelled: bool,
    #[serde(default)]
    pub detail: Option<String>,
}

impl Delivery {
    fn awaiting_delivery(&self) -> bool {
        matches!(self.status.as_str(), "queued" | "sending" | "unknown")
    }

    /// A native append targets the original task; a fallback owns a new task.
    /// The persisted identities also distinguish handoffs saved by older versions.
    fn fallback_task_id(&self) -> Option<&str> {
        self.delivered_task_id.as_deref().filter(|id| {
            self.status == "sent" && !self.reassign && Some(*id) != self.task_id.as_deref()
        })
    }

    fn pending_fallback(&self, tasks: &[Task]) -> bool {
        self.fallback_task_id().is_some_and(|id| {
            tasks.iter().find(|task| task.id == id).is_none_or(|task| {
                task.active_run_id.is_some()
                    || task.reconciliation_required
                    || !matches!(
                        task.status,
                        TaskStatus::Done | TaskStatus::Failed | TaskStatus::Paused
                    )
            })
        })
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub(super) struct Collaboration {
    #[serde(default)]
    pub last_delivery_sequence: u64,
    #[serde(default)]
    pub coordinator_successors: BTreeMap<String, String>,
    pub members: BTreeMap<String, Member>,
    pub groups: BTreeMap<String, TaskGroup>,
    pub deliveries: BTreeMap<String, Delivery>,
    pub operation_assignments: BTreeMap<String, TaskAssignment>,
    pub conversation: Vec<ConversationEntry>,
}

impl Collaboration {
    pub(super) fn migrate_delivery_sequences(&mut self) -> Result<()> {
        let mut witnessed = Vec::new();
        let mut missing = Vec::new();
        let mut witnessed_order = Vec::new();
        for delivery in self.deliveries.values() {
            // Main submissions use a separate replay journal but share user:<id>.
            // Only the actual delivery's content and attribution prove its position.
            let position = self.conversation.iter().position(|entry| {
                entry.id == format!("user:{}", delivery.id)
                    && matches!(entry.role.as_str(), "User" | "Delegation")
                    && entry.text == delivery.message
                    && entry.at_ms == delivery.at_ms
                    && entry.member_id == delivery.member_id
                    && entry.task_id == delivery.task_id
                    && entry.group_id == delivery.group_id
                    && entry.source_thread_id.is_none()
            });
            if let Some(position) = position {
                witnessed_order.push((position, delivery.id.clone()));
            }
            if delivery.sequence != 0 {
                continue;
            }
            if let Some(position) = position {
                witnessed.push((position, delivery));
            } else {
                missing.push(delivery);
            }
        }
        witnessed_order.sort_by_key(|(position, _)| *position);
        let mut sequenced: Vec<_> = self
            .deliveries
            .values()
            .filter(|d| d.sequence > 0)
            .collect();
        sequenced.sort_by_key(|d| d.sequence);
        let sequenced_order: Vec<_> = sequenced.iter().map(|d| d.id.clone()).collect();
        let witnessed_order: Vec<_> = witnessed_order.into_iter().map(|(_, id)| id).collect();
        let mut predecessors: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        for chain in [&witnessed_order, &sequenced_order] {
            for pair in chain.windows(2) {
                predecessors
                    .entry(pair[1].clone())
                    .or_default()
                    .insert(pair[0].clone());
            }
        }
        witnessed.sort_by_key(|(position, _)| *position);
        missing.sort_by_key(|d| (d.at_ms, &d.id));
        let mut witnessed = witnessed.into_iter().map(|(_, d)| d).peekable();
        let mut missing = missing.into_iter().peekable();
        let mut legacy = Vec::new();
        // Merge timestamp-only records with the witnessed chain. Keeping each
        // chain's order avoids a cyclic comparator when the clock moved backwards.
        // Equal-time missing records use IDs; a witnessed head wins a time tie.
        while witnessed.peek().is_some() || missing.peek().is_some() {
            let take_witnessed = match (witnessed.peek(), missing.peek()) {
                (Some(known), Some(unknown)) => known.at_ms <= unknown.at_ms,
                (Some(_), None) => true,
                _ => false,
            };
            let delivery = if take_witnessed {
                witnessed.next()
            } else {
                missing.next()
            }
            .unwrap();
            legacy.push(delivery.id.clone());
        }
        let offset = legacy.len() as u64;
        if offset > 0 {
            for delivery in self.deliveries.values_mut().filter(|d| d.sequence > 0) {
                delivery.sequence = delivery
                    .sequence
                    .checked_add(offset)
                    .context("Delivery sequence exhausted")?;
            }
            self.last_delivery_sequence = self
                .last_delivery_sequence
                .checked_add(offset)
                .context("Delivery sequence exhausted")?;
            for (index, id) in legacy.iter().enumerate() {
                self.deliveries.get_mut(id).unwrap().sequence = index as u64 + 1;
            }
            // A correction could only target a fallback after its owner created
            // that task, even if parent history is missing and the clock rolled back.
            // Witnessed submission and existing sequence order are constraints too;
            // timestamps only prioritize records whose known predecessors are ready.
            let mut pending: Vec<_> = self.deliveries.values().collect();
            pending.sort_by_key(|delivery| delivery.sequence);
            let sequence_slots: Vec<_> = pending.iter().map(|delivery| delivery.sequence).collect();
            let owners: BTreeMap<_, _> = pending
                .iter()
                .filter_map(|delivery| {
                    delivery
                        .fallback_task_id()
                        .map(|task| (task, delivery.id.as_str()))
                })
                .collect();
            let mut remaining: BTreeSet<_> = pending
                .iter()
                .map(|delivery| delivery.id.as_str())
                .collect();
            let mut ordered = Vec::with_capacity(pending.len());
            while !pending.is_empty() {
                let next = pending
                    .iter()
                    .position(|delivery| {
                        predecessors
                            .get(&delivery.id)
                            .is_none_or(|ids| ids.iter().all(|id| !remaining.contains(id.as_str())))
                            && delivery
                                .task_id
                                .as_deref()
                                .and_then(|task| owners.get(task))
                                .is_none_or(|owner| !remaining.contains(owner))
                    })
                    .context("Delivery ordering evidence contains a cycle")?;
                let delivery = pending.remove(next);
                remaining.remove(delivery.id.as_str());
                ordered.push(delivery.id.clone());
            }
            for (id, sequence) in ordered.into_iter().zip(sequence_slots) {
                self.deliveries.get_mut(&id).unwrap().sequence = sequence;
            }
        }
        self.last_delivery_sequence = self.last_delivery_sequence.max(
            self.deliveries
                .values()
                .map(|d| d.sequence)
                .max()
                .unwrap_or(0),
        );
        Ok(())
    }

    pub(super) fn cancel_deliveries(&mut self, task_id: Option<&str>, preserve: Option<&str>) {
        for delivery in self.deliveries.values_mut().filter(|d| {
            task_id.is_none_or(|id| d.task_id.as_deref() == Some(id))
                && preserve != Some(d.id.as_str())
        }) {
            if matches!(delivery.status.as_str(), "queued" | "sending" | "unknown") {
                delivery.cancelled = true;
                if delivery.status == "queued" {
                    delivery.status = "paused".into();
                }
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum CollaborationTool {
    CreateMember {
        name: String,
        duty: String,
        directory: PathBuf,
    },
    ListMembers,
    RetireMember {
        member_id: String,
    },
    CreateGroup {
        title: String,
        requires_summary: bool,
    },
    SealGroup {
        group_id: String,
    },
    AssignTask {
        prompt: String,
        directory: PathBuf,
        writes: bool,
        #[serde(default)]
        member_id: Option<String>,
        #[serde(default)]
        group_id: Option<String>,
        #[serde(default)]
        dependencies: Vec<String>,
        completion_criteria: String,
    },
    SendToMember {
        member_id: String,
        message: String,
    },
    ReassignTask {
        task_id: String,
        member_id: String,
        message: String,
    },
    ReadMain {
        #[serde(default)]
        before: Option<usize>,
        #[serde(default)]
        limit: Option<usize>,
        #[serde(default)]
        entry_id: Option<String>,
        #[serde(default)]
        offset: usize,
    },
}

impl<E: Executor> AssistantService<E> {
    pub fn import_legacy_conversation(&mut self, entries: Vec<ConversationEntry>) -> Result<()> {
        if self.state.collaboration.conversation.is_empty() {
            self.state.collaboration.conversation = entries;
            self.save()?;
        }
        Ok(())
    }
    pub fn conversation_entry(&self, id: &str) -> Option<&ConversationEntry> {
        self.state
            .collaboration
            .conversation
            .iter()
            .find(|entry| entry.id == id)
    }
    pub(super) fn same_member_operations(&self, first: &Operation, second: &Operation) -> bool {
        let a = self
            .state
            .collaboration
            .operation_assignments
            .get(&first.id);
        let b = self
            .state
            .collaboration
            .operation_assignments
            .get(&second.id);
        match (
            a.and_then(|a| a.member_id.as_ref()),
            b.and_then(|b| b.member_id.as_ref()),
        ) {
            (Some(a), Some(b)) => a == b,
            (None, None) => {
                let root =
                    |op: &Operation, assignment: Option<&TaskAssignment>| -> Result<String> {
                        if let Some(root) = assignment.and_then(|a| a.lineage_root.as_ref()) {
                            // Captured only after validating the complete chain at
                            // dispatch. Later reassignment cannot rewrite history.
                            return Ok(root.clone());
                        }
                        let mut task = self
                            .store
                            .task(op.task_id.as_deref().context("Missing task")?)?;
                        task.assignment = assignment.cloned();
                        self.anonymous_lineage_root(&task)
                    };
                matches!((root(first, a), root(second, b)), (Ok(a), Ok(b)) if a == b)
            }
            _ => false,
        }
    }
    pub fn operation_assignment(&self, id: &str) -> Option<&TaskAssignment> {
        self.state.collaboration.operation_assignments.get(id)
    }
    fn anonymous_lineage_root(&self, task: &Task) -> Result<String> {
        let mut current = task.clone();
        let mut seen = BTreeSet::new();
        let mut declared = Vec::new();
        loop {
            ensure!(
                seen.insert(current.id.clone()),
                "Continuation lineage contains a cycle"
            );
            ensure!(
                current.directory == task.directory && current.writes == task.writes,
                "Continuation directory or permissions changed"
            );
            let assignment = current.assignment.as_ref();
            ensure!(
                assignment.and_then(|a| a.member_id.as_ref()).is_none(),
                "Anonymous continuation crosses a member boundary"
            );
            if let Some(root) = assignment.and_then(|a| a.lineage_root.clone()) {
                declared.push(root);
            }
            match assignment.and_then(|a| a.continue_from_task.as_deref()) {
                Some(parent) => current = self.store.task(parent)?,
                None => {
                    ensure!(
                        declared.iter().all(|root| root == &current.id),
                        "Continuation root mismatch"
                    );
                    return Ok(current.id);
                }
            }
        }
    }
    fn pending_reassignment(&self, task_id: &str) -> bool {
        self.state.collaboration.deliveries.values().any(|d| {
            d.reassign
                && !d.cancelled
                && d.status == "queued"
                && d.task_id.as_deref() == Some(task_id)
        })
    }
    pub(super) fn cancel_deliveries(&mut self, task_id: Option<&str>, preserve: Option<&str>) {
        self.state
            .collaboration
            .cancel_deliveries(task_id, preserve);
    }
    pub fn members(&self) -> Vec<Member> {
        self.state.collaboration.members.values().cloned().collect()
    }
    pub fn deliveries(&self) -> Vec<Delivery> {
        let mut deliveries: Vec<_> = self
            .state
            .collaboration
            .deliveries
            .values()
            .cloned()
            .collect();
        deliveries.sort_by_key(|d| d.sequence);
        deliveries
    }

    /// Follow immutable delivery handoffs, not mutable member assignments, to
    /// keep anonymous sibling/descendant corrections in the same submission queue.
    fn delivery_order_root<'a>(&'a self, delivery: &'a Delivery) -> Result<Option<&'a str>> {
        let Some(mut task_id) = delivery.task_id.as_deref() else {
            return Ok(None);
        };
        let mut seen = BTreeSet::new();
        loop {
            ensure!(
                seen.insert(task_id),
                "Delivery continuation contains a cycle"
            );
            let parent = self.state.collaboration.deliveries.values().find(|prior| {
                prior.member_id.is_none() && prior.fallback_task_id() == Some(task_id)
            });
            match parent.and_then(|prior| prior.task_id.as_deref()) {
                Some(id) => task_id = id,
                None => return Ok(Some(task_id)),
            }
        }
    }

    fn same_delivery_target(&self, first: &Delivery, second: &Delivery) -> Result<bool> {
        if first
            .task_id
            .as_ref()
            .is_some_and(|id| second.task_id.as_ref() == Some(id))
            || first
                .member_id
                .as_ref()
                .is_some_and(|id| second.member_id.as_ref() == Some(id))
        {
            return Ok(true);
        }
        if first.member_id.is_none() && second.member_id.is_none() {
            let root = self.delivery_order_root(first)?;
            return Ok(root.is_some() && root == self.delivery_order_root(second)?);
        }
        Ok(false)
    }

    fn earlier_delivery_pending(
        &self,
        delivery: &Delivery,
        tasks: &[Task],
        include_fallback: bool,
    ) -> Result<bool> {
        for prior in self
            .state
            .collaboration
            .deliveries
            .values()
            .filter(|prior| {
                prior.sequence < delivery.sequence
                    && (prior.awaiting_delivery()
                        || (include_fallback && prior.pending_fallback(tasks)))
            })
        {
            if self.same_delivery_target(prior, delivery)? {
                return Ok(true);
            }
        }
        Ok(false)
    }

    fn group_settled(&self, group: &TaskGroup, tasks: &[Task]) -> bool {
        group.sealed
            && !self.state.collaboration.deliveries.values().any(|d| {
                d.group_id.as_deref() == Some(&group.id)
                    && matches!(d.status.as_str(), "queued" | "sending" | "unknown")
            })
            && tasks
                .iter()
                .filter(|t| {
                    t.assignment.as_ref().and_then(|a| a.group_id.as_ref()) == Some(&group.id)
                })
                .all(|t| {
                    !self.pending_reassignment(&t.id)
                        && t.active_run_id.is_none()
                        && matches!(
                            t.status,
                            TaskStatus::Done | TaskStatus::Failed | TaskStatus::Paused
                        )
                })
    }
    pub fn uncertain_deliveries(&self) -> bool {
        self.state
            .collaboration
            .deliveries
            .values()
            .any(|d| matches!(d.status.as_str(), "sending" | "unknown"))
    }
    pub fn reconcile_deliveries(&mut self) -> Result<()> {
        let mut changed = false;
        for delivery in self
            .deliveries()
            .into_iter()
            .filter(|d| matches!(d.status.as_str(), "sending" | "unknown"))
        {
            let mapping = delivery
                .operation_id
                .as_ref()
                .and_then(|id| self.state.operations.get(id))
                .and_then(|op| op.mapping.clone());
            let Some(mapping) = mapping else { continue };
            let outcome = self
                .executor
                .append_status(&delivery.id, &mapping, &delivery.message);
            let status = match outcome {
                Ok(AppendOutcome::Delivered) => "sent",
                Ok(AppendOutcome::Unsupported | AppendOutcome::Rejected)
                    if !self.is_paused() && !delivery.cancelled =>
                {
                    "queued"
                }
                Ok(AppendOutcome::Unsupported | AppendOutcome::Rejected) => "paused",
                _ => "unknown",
            };
            let current = self
                .state
                .collaboration
                .deliveries
                .get_mut(&delivery.id)
                .unwrap();
            if current.status != status {
                current.status = status.into();
                changed = true;
            }
            if status == "sent" {
                current.delivered_task_id = delivery.task_id;
            }
        }
        if changed {
            self.save()?;
        }
        Ok(())
    }
    pub fn assignments(&self) -> Result<Vec<Value>> {
        Ok(self
            .store
            .tasks()?
            .into_iter()
            .filter_map(|task| {
                task.assignment.map(|assignment| {
                    let mut value =
                        serde_json::to_value(assignment).expect("serializable assignment");
                    value["task_id"] = json!(task.id);
                    value
                })
            })
            .collect())
    }
    pub fn groups(&self) -> Result<Vec<Value>> {
        let tasks = self.store.tasks()?;
        Ok(self
            .state
            .collaboration
            .groups
            .values()
            .map(|group| {
                let members: Vec<_> = tasks
                    .iter()
                    .filter(|task| {
                        task.assignment.as_ref().and_then(|a| a.group_id.as_ref())
                            == Some(&group.id)
                    })
                    .collect();
                let terminal = self.group_settled(group, &tasks);
                let status = if !group.sealed {
                    "planning"
                } else if terminal {
                    if members.iter().all(|t| t.status == TaskStatus::Done) {
                        "done"
                    } else {
                        "incomplete"
                    }
                } else if members.iter().any(|t| t.status == TaskStatus::WaitingUser) {
                    "waiting_user"
                } else {
                    "running"
                };
                let mut value = serde_json::to_value(group).expect("serializable group");
                value["task_ids"] = json!(members.iter().map(|t| &t.id).collect::<Vec<_>>());
                value["status"] = json!(status);
                value
            })
            .collect())
    }
    pub fn conversation_page(&self, before: Option<usize>, limit: usize) -> Result<Value> {
        ensure!(
            (1..=100).contains(&limit),
            "Conversation limit must be 1..100"
        );
        let entries = &self.state.collaboration.conversation;
        let end = before.unwrap_or(entries.len()).min(entries.len());
        let start = end.saturating_sub(limit);
        let mut page = Vec::new();
        let mut bytes = 0;
        let mut first = end;
        for entry in entries[start..end].iter().rev() {
            let mut value = serde_json::to_value(entry)?;
            let preview = clip_bytes(&entry.text, 8192);
            value["text"] = json!(preview);
            value["truncated"] = json!(preview.len() != entry.text.len());
            let size = serde_json::to_vec(&value)?.len();
            if bytes + size > 48000 && !page.is_empty() {
                break;
            }
            bytes += size;
            first -= 1;
            page.push(value);
        }
        page.reverse();
        Ok(json!({"messages":page,"nextBefore":if first>0 {Some(first)}else{None}}))
    }
    fn conversation_text(&self, id: &str, offset: usize) -> Result<Value> {
        let entry = self
            .conversation_entry(id)
            .context("Conversation entry not found")?;
        ensure!(
            offset <= entry.text.len() && entry.text.is_char_boundary(offset),
            "Invalid text offset"
        );
        let text = clip_bytes(&entry.text[offset..], 8192);
        let end = offset + text.len();
        Ok(
            json!({"id":id,"text":text,"offset":offset,"nextOffset":if end<entry.text.len(){Some(end)}else{None}}),
        )
    }
    pub fn task_context(&self, task_id: &str) -> Result<Value> {
        let task = self.store.task(task_id)?;
        let operations: Vec<_> = self
            .state
            .operations
            .values()
            .filter(|o| o.task_id.as_deref() == Some(task_id))
            .cloned()
            .collect();
        Ok(json!({"task":task,"operations":operations}))
    }
    pub(super) fn remember_entry(&mut self, entry: ConversationEntry) {
        if !self
            .state
            .collaboration
            .conversation
            .iter()
            .any(|e| e.id == entry.id)
        {
            self.state.collaboration.conversation.push(entry);
        }
    }
    fn authorized_member(&self, id: &str) -> Result<Member> {
        let member = self
            .state
            .collaboration
            .members
            .get(id)
            .context("Member not found")?;
        ensure!(
            member.active
                && self.state.coordinator_thread.as_deref() == Some(&member.main_thread_id),
            "Member belongs to another or inactive conversation"
        );
        ensure!(
            canonical_directory(&member.directory)? == member.directory,
            "Member directory changed"
        );
        Ok(member.clone())
    }
    fn authorize_task_directory(&self, op: &Operation, directory: &Path) -> Result<PathBuf> {
        let directory = canonical_directory(directory)?;
        ensure!(
            op.authorized_directories
                .iter()
                .any(|root| canonical_directory(root)
                    .is_ok_and(|actual| actual == *root && within_directory(&directory, root))),
            "Task directory not authorized by this handoff"
        );
        Ok(directory)
    }
    pub(super) fn collaboration_tool(
        &mut self,
        op: &Operation,
        journal: &str,
        call: CollaborationTool,
        now: i64,
    ) -> Result<Value> {
        match call {
            CollaborationTool::CreateMember {
                name,
                duty,
                directory,
            } => {
                text(&name, 128)?;
                text(&duty, 4096)?;
                let directory = self.authorize_task_directory(op, &directory)?;
                ensure!(
                    self.members().iter().filter(|m| m.active).count() < 10,
                    "Member limit is 10 per conversation"
                );
                ensure!(
                    !self
                        .members()
                        .iter()
                        .any(|m| m.active
                            && m.name.trim().to_lowercase() == name.trim().to_lowercase()),
                    "Member name already exists"
                );
                let id = format!("member:{}", fingerprint(&journal)?);
                let member = Member {
                    id: id.clone(),
                    name: name.trim().into(),
                    duty,
                    main_thread_id: self
                        .state
                        .coordinator_thread
                        .clone()
                        .context("Main conversation unavailable")?,
                    directory,
                    active: true,
                    thread_id: None,
                    previous_threads: vec![],
                };
                self.state.collaboration.members.insert(id, member.clone());
                Ok(serde_json::to_value(member)?)
            }
            CollaborationTool::ListMembers => Ok(json!(self.members())),
            CollaborationTool::RetireMember { member_id } => {
                self.authorized_member(&member_id)?;
                ensure!(
                    !self.store.tasks()?.iter().any(|t| t
                        .assignment
                        .as_ref()
                        .and_then(|a| a.member_id.as_ref())
                        == Some(&member_id)
                        && (!matches!(
                            t.status,
                            TaskStatus::Done | TaskStatus::Failed | TaskStatus::Paused
                        ) || t.active_run_id.is_some())),
                    "Pause the member's tasks and confirm their stop before retiring it"
                );
                ensure!(
                    !self
                        .deliveries()
                        .iter()
                        .any(|d| d.member_id.as_ref() == Some(&member_id)
                            && matches!(d.status.as_str(), "queued" | "sending" | "unknown")),
                    "Member still has pending delivery"
                );
                self.state
                    .collaboration
                    .members
                    .get_mut(&member_id)
                    .unwrap()
                    .active = false;
                Ok(json!({"retired":true}))
            }
            CollaborationTool::CreateGroup {
                title,
                requires_summary,
            } => {
                text(&title, 512)?;
                let id = format!("group:{}", fingerprint(&journal)?);
                let group = TaskGroup {
                    id: id.clone(),
                    title,
                    origin_operation: op.id.clone(),
                    requires_summary,
                    sealed: false,
                    summary_enqueued: false,
                };
                self.state.collaboration.groups.insert(id, group.clone());
                Ok(json!(group))
            }
            CollaborationTool::SealGroup { group_id } => {
                let group = self
                    .state
                    .collaboration
                    .groups
                    .get_mut(&group_id)
                    .context("Task group not found")?;
                ensure!(
                    group.origin_operation == op.id,
                    "Only its dispatching turn can seal a task group"
                );
                group.sealed = true;
                Ok(json!({"sealed":true}))
            }
            CollaborationTool::AssignTask {
                prompt,
                directory,
                writes,
                member_id,
                group_id,
                dependencies,
                completion_criteria,
            } => {
                text(&prompt, 65536)?;
                text(&completion_criteria, 8192)?;
                ensure!(
                    prompt.len() + completion_criteria.len() + 128 <= 65536,
                    "Task and completion criteria exceed native input limit"
                );
                let directory = self.authorize_task_directory(op, &directory)?;
                if let Some(id) = &member_id {
                    ensure!(
                        self.authorized_member(id)?.directory == directory,
                        "Member task directory must match its responsibility"
                    );
                }
                if let Some(id) = &group_id {
                    let group = self
                        .state
                        .collaboration
                        .groups
                        .get(id)
                        .context("Task group not found")?;
                    ensure!(
                        !group.sealed && group.origin_operation == op.id,
                        "Task group already sealed or owned by another turn"
                    );
                }
                ensure!(
                    dependencies.len() <= 32
                        && dependencies.iter().collect::<BTreeSet<_>>().len() == dependencies.len(),
                    "Invalid task dependencies"
                );
                for id in &dependencies {
                    self.store.task(id)?;
                }
                let assignment = TaskAssignment {
                    member_id,
                    group_id,
                    dependencies,
                    completion_criteria,
                    continue_from_task: None,
                    lineage_root: None,
                };
                let receipt = self.store.apply(
                    journal,
                    Command::CreateAssignedTask {
                        prompt,
                        directory,
                        writes,
                        assignment,
                    },
                )?;
                Ok(serde_json::to_value(receipt)?)
            }
            CollaborationTool::SendToMember { member_id, message } => {
                let member = self.authorized_member(&member_id)?;
                self.authorize_task_directory(op, &member.directory)?;
                self.submit_targeted(journal, &message, Some(&member_id), None, false, now)?;
                if let Some(entry) = self
                    .state
                    .collaboration
                    .conversation
                    .iter_mut()
                    .find(|e| e.id == format!("user:{journal}"))
                {
                    entry.role = "Delegation".into();
                }
                Ok(json!({"queued":true,"deliveryId":journal}))
            }
            CollaborationTool::ReassignTask {
                task_id,
                member_id,
                message,
            } => {
                let member = self.authorized_member(&member_id)?;
                self.authorize_task_directory(op, &member.directory)?;
                let task = self.store.task(&task_id)?;
                ensure!(
                    task.directory == member.directory,
                    "Reassignment must retain the authorized directory"
                );
                self.submit_targeted(
                    journal,
                    &message,
                    Some(&member_id),
                    Some(&task_id),
                    true,
                    now,
                )?;
                if let Some(entry) = self
                    .state
                    .collaboration
                    .conversation
                    .iter_mut()
                    .find(|e| e.id == format!("user:{journal}"))
                {
                    entry.role = "Delegation".into();
                }
                Ok(json!({"queued":true,"waitingForStop":true,"deliveryId":journal}))
            }
            CollaborationTool::ReadMain {
                before,
                limit,
                entry_id,
                offset,
            } => match entry_id {
                Some(id) => self.conversation_text(&id, offset),
                None => self.conversation_page(before, limit.unwrap_or(5).min(20)),
            },
        }
    }

    /// Saves a user message before attempting any native append or follow-up execution.
    pub fn submit_targeted(
        &mut self,
        id: &str,
        message: &str,
        member_id: Option<&str>,
        task_id: Option<&str>,
        reassign: bool,
        now: i64,
    ) -> Result<()> {
        text(id, 512)?;
        text(message, 65536)?;
        clock(now)?;
        let receipt_id = format!("delivery:{id}");
        let hash = fingerprint(&(message, member_id, task_id, reassign))?;
        if self.replay(&receipt_id, &hash)?.is_some() {
            return Ok(());
        }
        ensure!(!self.is_paused(), "Assistant is paused");
        let task = task_id.map(|id| self.store.task(id)).transpose()?;
        if reassign {
            let task = task.as_ref().context("Reassignment requires a task")?;
            ensure!(member_id.is_some(), "Reassignment requires a member");
            ensure!(
                !matches!(task.status, TaskStatus::Done | TaskStatus::Failed),
                "Task is terminal; use a follow-up instead"
            );
            ensure!(
                !self.pending_reassignment(&task.id),
                "Task already has a pending reassignment"
            );
            ensure!(
                !task
                    .assignment
                    .as_ref()
                    .and_then(|a| a.group_id.as_ref())
                    .and_then(|id| self.state.collaboration.groups.get(id))
                    .is_some_and(|g| g.summary_enqueued),
                "Task group is settled; use a follow-up instead"
            );
            task.reassigned_prompt(message)?;
        }
        let resolved_member = member_id.map(str::to_owned).or_else(|| {
            task.as_ref()
                .and_then(|t| t.assignment.as_ref())
                .and_then(|a| a.member_id.clone())
        });
        let member = resolved_member
            .as_deref()
            .map(|id| self.authorized_member(id))
            .transpose()?;
        ensure!(
            task.is_some() || member.is_some(),
            "A task or member target is required"
        );
        if let (Some(task), Some(member)) = (&task, &member) {
            ensure!(
                task.directory == member.directory,
                "Target directory mismatch"
            );
            if !reassign {
                ensure!(
                    task.assignment.as_ref().and_then(|a| a.member_id.as_ref()) == Some(&member.id),
                    "Task belongs to another member"
                );
            }
        }
        let tasks = self.store.tasks()?;
        let latest = task.clone().or_else(|| {
            tasks
                .iter()
                .rev()
                .find(|t| {
                    t.assignment.as_ref().and_then(|a| a.member_id.as_ref())
                        == resolved_member.as_ref()
                })
                .cloned()
        });
        let directory = member
            .as_ref()
            .map(|m| m.directory.clone())
            .or_else(|| latest.as_ref().map(|t| t.directory.clone()))
            .context("Target directory unavailable")?;
        let writes = latest.as_ref().is_some_and(|t| t.writes);
        let operation_id = latest.as_ref().and_then(|t| t.active_run_id.clone());
        let group_id = latest
            .as_ref()
            .and_then(|t| t.assignment.as_ref())
            .and_then(|a| a.group_id.as_ref())
            .and_then(|id| {
                self.state
                    .collaboration
                    .groups
                    .get(id)
                    .filter(|g| !g.summary_enqueued && !self.group_settled(g, &tasks))
                    .map(|_| id.clone())
            });
        let sequence = self
            .state
            .collaboration
            .last_delivery_sequence
            .checked_add(1)
            .context("Delivery sequence exhausted")?;
        let delivery = Delivery {
            id: id.into(),
            sequence,
            group_id: group_id.clone(),
            message: message.into(),
            member_id: resolved_member.clone(),
            task_id: latest.as_ref().map(|t| t.id.clone()),
            directory,
            writes,
            at_ms: now,
            status: "queued".into(),
            delivered_task_id: None,
            operation_id,
            reassign,
            cancelled: false,
            detail: None,
        };
        self.state
            .collaboration
            .deliveries
            .insert(id.into(), delivery);
        self.state.collaboration.last_delivery_sequence = sequence;
        self.remember_entry(ConversationEntry {
            id: format!("user:{id}"),
            role: "User".into(),
            text: message.into(),
            at_ms: now,
            member_id: resolved_member,
            task_id: latest.as_ref().map(|t| t.id.clone()),
            group_id,
            source_thread_id: None,
        });
        self.record(&receipt_id, hash, Value::Null);
        self.save()?;
        // The queued reassignment itself is durable if interruption loses its receipt.
        if reassign {
            if let Some(task) = task.filter(|t| {
                !matches!(
                    t.status,
                    TaskStatus::Done | TaskStatus::Failed | TaskStatus::Paused
                )
            }) {
                self.apply_preserving_reassignment(
                    &format!("reassign-stop:{id}"),
                    Command::PauseTask { task_id: task.id },
                    now,
                    Some(id),
                )?;
            }
        }
        Ok(())
    }

    pub(super) fn advance_collaboration(&mut self, now: i64) -> Result<()> {
        let tasks = self.store.tasks()?;
        for task in &tasks {
            if let Some(a) = &task.assignment {
                if task.status == TaskStatus::Queued
                    && task.active_run_id.is_none()
                    && a.dependencies.iter().any(|id| {
                        tasks.iter().any(|t| {
                            &t.id == id
                                && matches!(t.status, TaskStatus::Failed | TaskStatus::Paused)
                                && !self.pending_reassignment(&t.id)
                                && t.active_run_id.is_none()
                        })
                    })
                {
                    self.store.fail_queued(
                        &task.id,
                        "A prerequisite did not complete; task was not dispatched",
                    )?;
                }
            }
        }
        let deliveries = self.deliveries();
        for delivery in deliveries.into_iter().filter(|d| d.status == "queued") {
            let tasks = self.store.tasks()?;
            if self.earlier_delivery_pending(&delivery, &tasks, false)? {
                continue;
            }
            if delivery.reassign {
                let task_id = delivery
                    .task_id
                    .as_deref()
                    .context("Reassignment task missing")?;
                let command_id = format!("reassign-task:{}", fingerprint(&delivery.id)?);
                let applied = self.store.command_applied(&command_id)?;
                let task = self.store.task(task_id)?;
                if !applied {
                    if matches!(task.status, TaskStatus::Done | TaskStatus::Failed) {
                        let current = self
                            .state
                            .collaboration
                            .deliveries
                            .get_mut(&delivery.id)
                            .unwrap();
                        current.status = "rejected".into();
                        current.detail = Some(
                            "Task completed before reassignment; use a follow-up instead".into(),
                        );
                        self.save()?;
                        continue;
                    }
                    if task.status != TaskStatus::Paused {
                        self.apply_preserving_reassignment(
                            &format!("reassign-stop:{}", delivery.id),
                            Command::PauseTask {
                                task_id: task_id.into(),
                            },
                            now,
                            Some(&delivery.id),
                        )?;
                    }
                    if task.active_run_id.is_some() || task.reconciliation_required {
                        continue;
                    }
                }
                let member = self.authorized_member(
                    delivery
                        .member_id
                        .as_deref()
                        .context("Reassignment member missing")?,
                )?;
                ensure!(
                    task.directory == member.directory,
                    "Reassignment directory changed"
                );
                self.store.apply(
                    &command_id,
                    Command::ReassignTask {
                        task_id: task_id.into(),
                        member_id: member.id,
                        message: delivery.message.clone(),
                    },
                )?;
                let current = self
                    .state
                    .collaboration
                    .deliveries
                    .get_mut(&delivery.id)
                    .unwrap();
                current.status = "sent".into();
                current.delivered_task_id = Some(task_id.into());
                self.save()?;
                continue;
            }
            if !delivery.reassign {
                let operation = delivery
                    .operation_id
                    .as_ref()
                    .and_then(|id| self.state.operations.get(id))
                    .cloned();
                let can_append = !self.earlier_delivery_pending(&delivery, &tasks, true)?
                    && operation
                        .as_ref()
                        .is_some_and(|op| op.phase == OperationPhase::Running && !op.suspended);
                if can_append {
                    if let Some(mapping) = operation.as_ref().and_then(|op| op.mapping.as_ref()) {
                        self.state
                            .collaboration
                            .deliveries
                            .get_mut(&delivery.id)
                            .unwrap()
                            .status = "sending".into();
                        self.save()?;
                        let outcome =
                            self.executor
                                .append(&delivery.id, mapping, &delivery.message);
                        let current = self
                            .state
                            .collaboration
                            .deliveries
                            .get_mut(&delivery.id)
                            .unwrap();
                        match outcome {
                            Ok(AppendOutcome::Delivered) => {
                                current.status = "sent".into();
                                current.delivered_task_id = delivery.task_id.clone();
                            }
                            Ok(AppendOutcome::Unsupported | AppendOutcome::Rejected) => {
                                current.status = "queued".into()
                            }
                            _ => current.status = "unknown".into(),
                        }
                        let queued = current.status == "queued";
                        self.save()?;
                        if !queued {
                            continue;
                        }
                    }
                }
            }
            if let Some(member) = &delivery.member_id {
                if self.authorized_member(member).is_err() {
                    continue;
                }
            }
            if tasks.iter().any(|t| {
                t.active_run_id.is_some()
                    && (delivery.task_id.as_deref() == Some(&t.id)
                        || delivery.member_id.as_ref().is_some_and(|m| {
                            t.assignment.as_ref().and_then(|a| a.member_id.as_ref()) == Some(m)
                        }))
            }) {
                continue;
            }
            let previous = delivery
                .task_id
                .as_ref()
                .and_then(|id| tasks.iter().find(|t| &t.id == id));
            if previous.is_some_and(|t| t.status == TaskStatus::Queued) {
                continue;
            }
            let mut prompt = delivery.message.clone();
            if let Some(t) = previous {
                append_context(
                    &mut prompt,
                    &format!(
                        "\nFollow-up to task {}. Original task: {}\nPrevious outcome: {}",
                        t.id,
                        clip_bytes(&t.prompt, 8192),
                        clip_bytes(t.detail.as_deref().unwrap_or("Not completed"), 8192)
                    ),
                );
            }
            text(&prompt, 65536)?;
            let assignment = TaskAssignment {
                member_id: delivery.member_id.clone(),
                group_id: delivery.group_id.clone(),
                continue_from_task: delivery.task_id.clone(),
                lineage_root: if delivery.member_id.is_none() {
                    previous
                        .map(|task| self.anonymous_lineage_root(task))
                        .transpose()?
                } else {
                    None
                },
                ..Default::default()
            };
            let receipt = self.store.apply(
                &format!("delivery-task:{}", delivery.id),
                Command::CreateAssignedTask {
                    prompt,
                    directory: delivery.directory.clone(),
                    writes: delivery.writes,
                    assignment,
                },
            )?;
            let CommandResult::Task(task) = receipt.result else {
                unreachable!()
            };
            let current = self
                .state
                .collaboration
                .deliveries
                .get_mut(&delivery.id)
                .unwrap();
            current.status = "sent".into();
            current.delivered_task_id = Some(task.id);
            self.save()?;
        }
        if self.uncertain_deliveries() {
            // Each delivery transition was already saved before/after its
            // effect. Do not rewrite the conversation just to wait for receipts.
            return Ok(());
        }
        let mut changed = false;
        let tasks = self.store.tasks()?;
        for group in self.state.collaboration.groups.values_mut() {
            if !group.sealed
                && self
                    .state
                    .operations
                    .get(&group.origin_operation)
                    .is_some_and(|op| !op.phase.live())
            {
                group.sealed = true;
                changed = true;
            }
        }
        let groups: Vec<_> = self.state.collaboration.groups.values().cloned().collect();
        for group in groups
            .into_iter()
            .filter(|g| g.sealed && g.requires_summary && !g.summary_enqueued)
        {
            let members: Vec<_> = tasks
                .iter()
                .filter(|t| {
                    t.assignment.as_ref().and_then(|a| a.group_id.as_ref()) == Some(&group.id)
                })
                .collect();
            if self.group_settled(&group, &tasks) {
                let detail=serde_json::to_string(&members.iter().map(|t|json!({"id":t.id,"status":t.status,"result":t.detail.as_ref().map(|s|clip_bytes(s,4096))})).collect::<Vec<_>>())?;
                self.event(
                    format!("group-ready:{}", group.id),
                    None,
                    "group_ready",
                    detail,
                    false,
                    true,
                    now,
                );
                if let Some(event) = self
                    .state
                    .events
                    .last_mut()
                    .filter(|e| e.id == format!("group-ready:{}", group.id))
                {
                    event.group_id = Some(group.id.clone());
                }
                self.state
                    .collaboration
                    .groups
                    .get_mut(&group.id)
                    .unwrap()
                    .summary_enqueued = true;
                changed = true;
            }
        }
        if changed {
            self.save()?;
        }
        Ok(())
    }
    pub(super) fn eligible_tasks(&self) -> Result<BTreeSet<String>> {
        let tasks = self.store.tasks()?;
        let mut eligible = BTreeSet::new();
        for task in &tasks {
            if task.assignment.as_ref().is_some_and(|a| {
                a.member_id
                    .as_ref()
                    .is_some_and(|id| self.authorized_member(id).is_err())
                    || a.group_id.as_ref().is_some_and(|id| {
                        !self
                            .state
                            .collaboration
                            .groups
                            .get(id)
                            .is_some_and(|g| g.sealed)
                    })
            }) {
                continue;
            }
            if let Some(delivery) = self
                .state
                .collaboration
                .deliveries
                .values()
                .find(|d| d.fallback_task_id() == Some(task.id.as_str()))
            {
                // A sent fallback is still ordered work until its task settles.
                // Recomputed before every claim, including anonymous read-only tasks.
                if self.earlier_delivery_pending(delivery, &tasks, true)? {
                    continue;
                }
            }
            eligible.insert(task.id.clone());
        }
        Ok(eligible)
    }
    pub(super) fn execution_thread(&mut self, task: &Task) -> Result<Option<String>> {
        if task
            .assignment
            .as_ref()
            .is_none_or(|a| a.member_id.is_none())
        {
            let root = self.anonymous_lineage_root(task)?;
            if let Some(assignment) = task
                .active_run_id
                .as_ref()
                .and_then(|id| self.state.collaboration.operation_assignments.get_mut(id))
            {
                assignment.lineage_root = Some(root);
                self.save()?;
            }
        }
        let prior =
            if let Some(member_id) = task.assignment.as_ref().and_then(|a| a.member_id.as_ref()) {
                self.authorized_member(member_id)?.thread_id
            } else {
                let from = task
                    .assignment
                    .as_ref()
                    .and_then(|a| a.continue_from_task.as_deref())
                    .unwrap_or(&task.id);
                self.state
                    .operations
                    .values()
                    .filter(|o| o.task_id.as_deref() == Some(from))
                    .filter_map(|o| o.mapping.as_ref().map(|m| m.thread_id.clone()))
                    .last()
            };
        // Validate service ownership before creating any native effect. The
        // same check is repeated for the returned mapping in observe().
        if let Some(thread) = &prior {
            let operation = self
                .state
                .operations
                .get(task.active_run_id.as_deref().context("Missing run")?)
                .context("Missing operation")?;
            ensure!(
                self.state
                    .operations
                    .values()
                    .filter(|o| o.id != operation.id
                        && o.mapping.as_ref().is_some_and(|m| &m.thread_id == thread))
                    .all(|other| other.role == Role::Execution
                        && self.same_member_operations(operation, other)),
                "Native thread crosses task ownership or continuation lineage"
            );
        }
        match prior {
            Some(thread) if task.assignment.is_some() => {
                if self.executor.can_resume(
                    &thread,
                    Role::Execution,
                    Some(&task.directory),
                    task.writes,
                )? {
                    Ok(Some(thread))
                } else {
                    Ok(None)
                }
            }
            other => Ok(other),
        }
    }
    pub(super) fn task_prompt(&self, task: &Task) -> Result<String> {
        let Some(a) = &task.assignment else {
            return Ok(task.prompt.clone());
        };
        let member = a
            .member_id
            .as_deref()
            .map(|id| self.authorized_member(id))
            .transpose()?;
        let mut prompt = task.prompt.clone();
        if !a.completion_criteria.is_empty() {
            prompt.push_str(&format!("\nCompletion criteria: {}", a.completion_criteria));
        }
        text(&prompt, 65536)?;
        if let Some(member) = member {
            append_context(
                &mut prompt,
                &format!(
                    "\nResponsibility: {}. Main conversation: {}. Use assistant_read_main only if more context is needed.",
                    member.duty, member.main_thread_id
                ),
            );
        }
        for id in &a.dependencies {
            let prior = self.store.task(id)?;
            append_context(
                &mut prompt,
                &format!(
                    "\nPrerequisite {} result: {}",
                    id,
                    clip_bytes(&prior.detail.unwrap_or_default(), 512)
                ),
            );
        }
        // A bounded public context preview; full older messages remain available through read_main.
        let context = self
            .state
            .collaboration
            .conversation
            .iter()
            .rev()
            .take(3)
            .map(|entry| {
                let clipped: String = entry.text.chars().take(1500).collect();
                format!("{}: {}", entry.role, clipped)
            })
            .collect::<Vec<_>>();
        append_context(
            &mut prompt,
            &format!(
                "\nRecent conversation (reference data): {}",
                serde_json::to_string(&context)?
            ),
        );
        text(&prompt, 65536)?;
        Ok(prompt)
    }
    pub(super) fn record_execution_result(
        &mut self,
        op: &Operation,
        kind: &str,
        detail: &str,
        now: i64,
    ) -> Result<()> {
        let assignment = self
            .state
            .collaboration
            .operation_assignments
            .get(&op.id)
            .cloned();
        if let (Some(a), Some(mapping)) = (&assignment, &op.mapping) {
            if let Some(member) = a
                .member_id
                .as_ref()
                .and_then(|id| self.state.collaboration.members.get_mut(id))
            {
                if member.thread_id.as_deref() != Some(&mapping.thread_id) {
                    if let Some(prior) = member.thread_id.replace(mapping.thread_id.clone()) {
                        if !member.previous_threads.contains(&prior) {
                            member.previous_threads.push(prior);
                        }
                    }
                }
            }
        }
        if matches!(kind, "completed" | "failed") {
            self.remember_entry(ConversationEntry {
                id: format!("result:{}", op.id),
                role: "Assistant".into(),
                text: detail.into(),
                at_ms: now,
                member_id: assignment.as_ref().and_then(|a| a.member_id.clone()),
                task_id: op.task_id.clone(),
                group_id: assignment.as_ref().and_then(|a| a.group_id.clone()),
                source_thread_id: op.mapping.as_ref().map(|m| m.thread_id.clone()),
            });
        }
        Ok(())
    }
    pub fn direct_result(&self, op: &Operation) -> bool {
        op.role == Role::Execution
            && self
                .state
                .collaboration
                .operation_assignments
                .get(&op.id)
                .is_some_and(|a| {
                    a.group_id.as_ref().is_none_or(|id| {
                        self.state
                            .collaboration
                            .groups
                            .get(id)
                            .is_none_or(|g| !g.requires_summary)
                    })
                })
    }
}

pub(super) fn clip_bytes(value: &str, limit: usize) -> &str {
    let mut end = value.len().min(limit);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    &value[..end]
}
fn append_context(prompt: &mut String, context: &str) {
    let remaining = 65536usize.saturating_sub(prompt.len());
    prompt.push_str(clip_bytes(context, remaining));
}
