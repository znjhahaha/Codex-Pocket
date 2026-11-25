//! Conversation identity is independent from native turns. The original coordinator
//! fields stay flattened in State so existing journals keep their original meaning.
use super::*;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Conversation {
    pub id: String,
    pub title: String,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub(super) struct CoordinatorState {
    pub coordinator_thread: Option<String>,
    #[serde(default)]
    pub coordinator_attempt: Option<String>,
    pub coordinator_watermark: u64,
    pub inbox: Vec<Inbox>,
}

impl State {
    pub(super) fn origin<'a>(&'a self, kind: &str, id: &str, main: &'a str) -> &'a str {
        self.conversation_origins
            .get(&format!("{kind}:{id}"))
            .map(String::as_str)
            .unwrap_or(main)
    }
    pub(super) fn migrate_conversations(&mut self, owner: &str) {
        let id = format!("main:{owner}");
        let created = self
            .collaboration
            .conversation
            .iter()
            .map(|m| m.at_ms)
            .chain(self.events.iter().map(|e| e.at_ms))
            .min()
            .unwrap_or(0);
        let updated = self
            .collaboration
            .conversation
            .iter()
            .map(|m| m.at_ms)
            .chain(self.events.iter().map(|e| e.at_ms))
            .max()
            .unwrap_or(created);
        self.conversations
            .entry(id.clone())
            .or_insert(Conversation {
                id,
                title: "口袋助理".into(),
                created_at_ms: created,
                updated_at_ms: updated,
            });
    }
}

/// Metadata-only creation before the native worker has started. It takes the same
/// profile lock, and preserves every operation phase and pending receipt verbatim.
pub fn create_retained_conversation(
    path: &Path,
    computer: &str,
    profile: &str,
    command: &str,
    title: Option<&str>,
    now: i64,
) -> Result<Conversation> {
    text(computer, 256)?;
    text(profile, 256)?;
    text(command, 512)?;
    clock(now)?;
    let identity = fingerprint(&(computer, profile))?;
    let parent = dunce::canonicalize(path.parent().context("Missing database parent")?)?;
    let path = parent.join(path.file_name().context("Missing database filename")?);
    let owner = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(path.with_extension(format!("{identity}.assistant-lock")))?;
    fs2::FileExt::try_lock_exclusive(&owner)
        .context("Assistant profile already has a service owner")?;
    let store = AssistantStore::open(&path, &identity)?;
    domain::initialize(&store.db)?;
    store.db.execute_batch("CREATE TABLE IF NOT EXISTS assistant_service_state (assistant_id TEXT PRIMARY KEY,data TEXT NOT NULL)")?;
    let saved: Option<String> = store
        .db
        .query_row(
            "SELECT data FROM assistant_service_state WHERE assistant_id=?1",
            [&store.assistant_id],
            |r| r.get(0),
        )
        .optional()?;
    let mut state: State = saved
        .map(|s| serde_json::from_str(&s))
        .transpose()?
        .unwrap_or_default();
    state.migrate_conversations(&store.assistant_id);
    let title = title.unwrap_or("新对话").trim();
    text(title, 512)?;
    let hash = fingerprint(&("conversation/create", title))?;
    if let Some(entry) = state.journal.get(command) {
        ensure!(
            entry.fingerprint == hash,
            "Command ID reused with different content"
        );
        return Ok(serde_json::from_value(entry.result.clone())?);
    }
    let conversation = Conversation {
        id: format!("chat:{}", uuid::Uuid::new_v4()),
        title: title.into(),
        created_at_ms: now,
        updated_at_ms: now,
    };
    state
        .conversations
        .insert(conversation.id.clone(), conversation.clone());
    state
        .conversation_coordinators
        .insert(conversation.id.clone(), CoordinatorState::default());
    state.journal.insert(
        command.into(),
        JournalEntry {
            fingerprint: hash,
            result: serde_json::to_value(&conversation)?,
        },
    );
    save_state(&store.db, &store.assistant_id, &state)?;
    Ok(conversation)
}

impl<E: Executor> AssistantService<E> {
    pub fn default_conversation_id(&self) -> String {
        format!("main:{}", self.assistant_id())
    }
    pub fn selected_conversation_id(&self) -> &str {
        &self.current_conversation
    }
    pub fn conversations(&self) -> Vec<Conversation> {
        let mut values = self
            .state
            .conversations
            .values()
            .cloned()
            .collect::<Vec<_>>();
        values.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms).then(a.id.cmp(&b.id)));
        values
    }
    pub fn resolve_conversation(&self, id: Option<&str>) -> Result<String> {
        let id = id
            .map(str::to_owned)
            .unwrap_or_else(|| self.default_conversation_id());
        ensure!(
            self.state.conversations.contains_key(&id),
            "Assistant conversation not found"
        );
        Ok(id)
    }
    /// Serialized service calls enter their originating conversation and always restore
    /// the caller's context, including error paths. Selection itself never persists.
    pub(crate) fn in_conversation<T>(
        &mut self,
        id: &str,
        action: impl FnOnce(&mut Self) -> Result<T>,
    ) -> Result<T> {
        self.resolve_conversation(Some(id))?;
        let previous = std::mem::replace(&mut self.current_conversation, id.into());
        let result = action(self);
        self.current_conversation = previous;
        result
    }
    pub(super) fn coordinator(&self) -> &CoordinatorState {
        if self.current_conversation == self.default_conversation_id() {
            &self.state.coordinator
        } else {
            &self.state.conversation_coordinators[&self.current_conversation]
        }
    }
    pub(super) fn coordinator_mut(&mut self) -> &mut CoordinatorState {
        if self.current_conversation == self.default_conversation_id() {
            &mut self.state.coordinator
        } else {
            self.state
                .conversation_coordinators
                .get_mut(&self.current_conversation)
                .expect("validated conversation")
        }
    }
    pub fn conversation_thread_id(&self, conversation: &str) -> Option<&str> {
        if conversation == self.default_conversation_id() {
            self.state.coordinator.coordinator_thread.as_deref()
        } else {
            self.state
                .conversation_coordinators
                .get(conversation)?
                .coordinator_thread
                .as_deref()
        }
    }
    pub fn conversation_operation_id(&self, conversation: &str) -> Option<&str> {
        let context = if conversation == self.default_conversation_id() {
            &self.state.coordinator
        } else {
            self.state.conversation_coordinators.get(conversation)?
        };
        if let Some(id) = context.coordinator_attempt.as_deref() {
            return Some(id).filter(|id| {
                self.state
                    .operations
                    .get(*id)
                    .is_some_and(|op| op.phase.live())
            });
        }
        let mut live = self.state.operations.values().filter(|op| {
            op.role == Role::Coordinator
                && op.phase.live()
                && self.origin_conversation("operation", &op.id) == conversation
        });
        let only = live.next()?;
        if live.next().is_none() {
            Some(only.id.as_str())
        } else {
            None
        }
    }
    pub fn origin_conversation(&self, kind: &str, id: &str) -> String {
        if kind == "message"
            && !self
                .state
                .conversation_origins
                .contains_key(&format!("message:{id}"))
        {
            if let Some(operation) = id
                .strip_prefix("assistant-operation:")
                .or_else(|| id.strip_prefix("result:"))
            {
                return self.origin_conversation("operation", operation);
            }
            if let Some(event) = id.strip_prefix("assistant:") {
                return self.origin_conversation("event", event);
            }
        }
        self.state
            .origin(kind, id, &self.default_conversation_id())
            .to_owned()
    }
    pub(super) fn in_current_conversation(&self, kind: &str, id: &str) -> bool {
        self.origin_conversation(kind, id) == self.current_conversation
    }
    pub(super) fn conversation_inbox_empty(&self, id: &str) -> bool {
        if id == self.default_conversation_id() {
            self.state.coordinator.inbox.is_empty()
        } else {
            self.state
                .conversation_coordinators
                .get(id)
                .is_some_and(|c| c.inbox.is_empty())
        }
    }
    pub(super) fn bind_conversation(&mut self, kind: &str, id: &str) {
        self.state
            .conversation_origins
            .entry(format!("{kind}:{id}"))
            .or_insert_with(|| self.current_conversation.clone());
    }
    pub fn import_message_origin(&mut self, id: &str, operation: Option<&str>) {
        let origin = operation
            .map(|op| self.origin_conversation("operation", op))
            .unwrap_or_else(|| self.origin_conversation("message", id));
        self.state
            .conversation_origins
            .entry(format!("message:{id}"))
            .or_insert(origin);
    }
    pub(super) fn touch_conversation(&mut self, now: i64) {
        if let Some(conversation) = self.state.conversations.get_mut(&self.current_conversation) {
            conversation.updated_at_ms = conversation.updated_at_ms.max(now);
        }
    }
    pub fn create_conversation(
        &mut self,
        command: &str,
        title: Option<&str>,
        now: i64,
    ) -> Result<Conversation> {
        text(command, 512)?;
        clock(now)?;
        let title = title.unwrap_or("新对话").trim();
        text(title, 512)?;
        let hash = fingerprint(&("conversation/create", title))?;
        if let Some(result) = self.replay(command, &hash)? {
            return Ok(serde_json::from_value(result)?);
        }
        let previous = self.state.clone();
        let conversation = Conversation {
            id: format!("chat:{}", uuid::Uuid::new_v4()),
            title: title.into(),
            created_at_ms: now,
            updated_at_ms: now,
        };
        self.state
            .conversations
            .insert(conversation.id.clone(), conversation.clone());
        self.state
            .conversation_coordinators
            .insert(conversation.id.clone(), CoordinatorState::default());
        self.record(command, hash, serde_json::to_value(&conversation)?);
        if let Err(error) = self.save() {
            self.state = previous;
            return Err(error);
        }
        Ok(conversation)
    }
    pub fn interrupt_conversation(
        &mut self,
        command: &str,
        conversation: &str,
        operation: &str,
        now: i64,
    ) -> Result<()> {
        self.resolve_conversation(Some(conversation))?;
        clock(now)?;
        let hash = fingerprint(&("conversation/interrupt", conversation, operation))?;
        if self.replay(command, &hash)?.is_some() {
            return Ok(());
        }
        ensure!(
            self.origin_conversation("operation", operation) == conversation
                && self.conversation_operation_id(conversation) == Some(operation),
            "Coordinator operation changed"
        );
        let op = self
            .state
            .operations
            .get(operation)
            .context("Coordinator operation not found")?;
        ensure!(
            op.role == Role::Coordinator && op.phase.live(),
            "Coordinator is not running"
        );
        let previous = self.state.clone();
        self.state.operations.get_mut(operation).unwrap().suspended = true;
        self.record(command, hash, serde_json::Value::Null);
        if let Err(error) = self.save() {
            self.state = previous;
            return Err(error);
        }
        // Saving the exact stop intent first lets restart reconciliation retry only
        // this native turn. Unknown receipt or missing mapping never drops work.
        if let Some(mapping) = self.state.operations[operation].mapping.clone() {
            let _ = self.executor.interrupt(mapping);
        }
        Ok(())
    }
    /// Task and provenance commit together, closing the crash gap before dispatch.
    pub(super) fn apply_bound_command(
        &mut self,
        id: &str,
        command: Command,
    ) -> Result<CommandReceipt> {
        let mut next = self.state.clone();
        let owner = self.assistant_id().to_owned();
        let conversation = self.current_conversation.clone();
        let creates = matches!(
            command,
            Command::CreateTask { .. } | Command::CreateAssignedTask { .. }
        );
        let receipt = self.store.apply_with_effect(id, command, |db, receipt| {
            if creates && let CommandResult::Task(task) = &receipt.result {
                let origin = task
                    .assignment
                    .as_ref()
                    .and_then(|a| a.continue_from_task.as_ref().or(a.parent_task_id.as_ref()))
                    .map(|id| next.origin("task", id, &format!("main:{owner}")).to_owned())
                    .unwrap_or(conversation);
                next.conversation_origins
                    .entry(format!("task:{}", task.id))
                    .or_insert(origin);
                save_state(db, &owner, &next)?;
            }
            Ok(())
        })?;
        if creates {
            if let CommandResult::Task(task) = &receipt.result {
                if let Some(origin) = next.conversation_origins.get(&format!("task:{}", task.id)) {
                    self.state
                        .conversation_origins
                        .insert(format!("task:{}", task.id), origin.clone());
                }
            }
        }
        Ok(receipt)
    }
}
