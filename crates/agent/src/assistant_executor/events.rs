use super::*;

impl<R: RuntimeIo> HostExecutor<R> {
    pub fn ingest(&mut self, generation: &str, event: RuntimeEvent) -> Result<EventProjection> {
        self.healthy(generation, false)?;
        ensure!(
            generation == self.state.generation,
            "Event generation mismatch"
        );
        bounded_json(&event.message)?;
        if event.sequence > 0 && event.sequence <= self.state.cursor {
            return Ok(EventProjection::default());
        }
        let v = event.message;
        let method = v["method"]
            .as_str()
            .context("Native event missing method")?;
        let p = &v["params"];
        let thread = p["threadId"].as_str();
        let turn = p["turnId"].as_str().or_else(|| p["turn"]["id"].as_str());
        let mut projection = EventProjection::default();
        if method == "serverRequest/resolved" {
            let key = native_key(&p["requestId"])?;
            if let Some(pending) = self.pending.get(&key) {
                ensure!(
                    thread == Some(&pending.mapping.thread_id),
                    "Resolved request thread mismatch"
                );
                let id = pending.operation_id.clone();
                let mapping = pending.mapping.clone();
                if let Some(call) = &pending.call_id {
                    projection
                        .resolved_tools
                        .push((mapping.clone(), call.clone()));
                }
                self.pending.remove(&key);
                if !self.state.streaming_stopped.contains(&id) {
                let status = self
                    .pending_status(&mapping)?
                    .unwrap_or(ExecutionStatus::Running(mapping));
                self.statuses.insert(id.clone(), status.clone());
                projection.observations.push(StatusObservation {
                    operation_id: id,
                    status,
                    event_id: format!("host:{generation}:{}", event.sequence),
                });
                }
            }
        } else {
            let op = self
                .state
                .operations
                .values()
                .find(|o| {
                    o.generation==generation && o.mapping.as_ref().is_some_and(|m| {
                        Some(m.thread_id.as_str()) == thread && Some(m.turn_id.as_str()) == turn
                    })
                })
                .cloned();
            if let Some(op) = op {
                let mapping = op.mapping.clone().unwrap();
                if method == "item/agentMessage/delta"
                    || (method == "item/completed" && p["item"]["type"] == "agentMessage")
                {
                    if self.state.threads[&mapping.thread_id]
                        .current_operation
                        .as_deref()
                        == Some(&op.operation_id)
                        && !self.state.streaming_gap
                        && !self.state.streaming_stopped.contains(&op.operation_id)
                        && !self.statuses.get(&op.operation_id).is_some_and(|s| {
                            matches!(
                                s,
                                ExecutionStatus::Completed(..)
                                    | ExecutionStatus::Failed(..)
                                    | ExecutionStatus::Interrupted(..)
                            )
                        })
                    {
                        let item = p["itemId"]
                            .as_str()
                            .or_else(|| p["item"]["id"].as_str())
                            .context("Streaming item identity missing")?;
                        small(item)?;
                        let text = if method == "item/completed" {
                            p["item"]["text"].as_str()
                        } else {
                            p["delta"].as_str()
                        }
                        .context("Streaming text missing")?;
                        self.store_stream(&op.operation_id, item, text, method != "item/completed");
                        if let Some(text) = self.stream_text(&op.operation_id) {
                            projection.streams.push(StreamObservation {
                                operation_id: op.operation_id.clone(),
                                text,
                            });
                        }
                    }
                } else if method == "item/tool/call" {
                    let current = self.state.threads[&mapping.thread_id]
                            .current_operation
                            .as_deref()
                            == Some(&op.operation_id);
                    let stopped = self.state.streaming_stopped.contains(&op.operation_id)
                        || self
                            .statuses
                            .get(&op.operation_id)
                            .is_some_and(|s| matches!(
                                s,
                                ExecutionStatus::Completed(..)
                                    | ExecutionStatus::Failed(..)
                                    | ExecutionStatus::Interrupted(..)
                            ));
                    ensure!((current && !stopped) || event.sequence == 0,
                        "Dynamic call targets an old or stopped turn");
                    let native_id = v
                        .get("id")
                        .context("Dynamic call native request id missing")?
                        .clone();
                    let handle = native_key(&native_id)?;
                    let call_id = p["callId"]
                        .as_str()
                        .context("Dynamic callId missing")?
                        .to_owned();
                    small(&call_id)?;
                    let pending = PendingRequest {
                        operation_id: op.operation_id.clone(),
                        mapping: mapping.clone(),
                        native_id: native_id.clone(),
                        handle: handle.clone(),
                        method: method.into(),
                        params: p.clone(),
                        call_id: Some(call_id.clone()),
                    };
                    self.insert_pending(pending)?;
                    let decoded =
                        if !current || stopped {
                            Err(anyhow::anyhow!("Retained tool belongs to a stopped turn; use Assistant to reject it"))
                        } else if op.role != Role::Coordinator && p["tool"] != "assistant_read_main" {
                            Err(anyhow::anyhow!(
                                "Only current coordinator may use assistant tools"
                            ))
                        } else if !p["namespace"].is_null() {
                            Err(anyhow::anyhow!("Unknown dynamic tool namespace"))
                        } else {
                            protocol::decode_tool(p["tool"].as_str().unwrap_or(""), &p["arguments"])
                        };
                    let (call, error) = match decoded {
                        Ok(c) => (Some(c), None),
                        Err(e) => (None, Some(e.to_string())),
                    };
                    projection.tools.push(PendingToolCall {
                        operation_id: op.operation_id,
                        scope: mapping,
                        native_id,
                        call_id,
                        call,
                        error,
                    });
                } else if matches!(
                    method,
                    "item/tool/requestUserInput"
                        | "item/commandExecution/requestApproval"
                        | "item/fileChange/requestApproval"
                        | "item/permissions/requestApproval"
                ) {
                    let native_id = v.get("id").context("Native request id missing")?.clone();
                    let handle = native_key(&native_id)?;
                    let pending = PendingRequest {
                        operation_id: op.operation_id.clone(),
                        mapping: mapping.clone(),
                        native_id,
                        handle: handle.clone(),
                        method: method.into(),
                        params: p.clone(),
                        call_id: None,
                    };
                    self.insert_pending(pending)?;
                    if self.state.streaming_stopped.contains(&op.operation_id) {
                        ensure!(event.sequence == 0, "Native request targets a stopped turn");
                        return Ok(projection);
                    }
                    let text = if method == "item/tool/requestUserInput" {
                        serde_json::to_string(&p["questions"])?
                    } else {
                        serde_json::to_string(p)?
                    };
                    bounded_text(&text, 65536)?;
                    let question = UserQuestion {
                        request_id: handle,
                        kind: if method == "item/tool/requestUserInput" {
                            QuestionKind::Input
                        } else {
                            QuestionKind::Approval
                        },
                        text,
                    };
                    let status = ExecutionStatus::Waiting(mapping, question);
                    self.statuses
                        .insert(op.operation_id.clone(), status.clone());
                    let event_id = if event.sequence == 0 {
                        format!("host:{generation}:pending:{}", hash(&v["id"])?)
                    } else {
                        format!("host:{generation}:{}", event.sequence)
                    };
                    projection.observations.push(StatusObservation {
                        operation_id: op.operation_id,
                        status,
                        event_id,
                    });
                } else if method == "turn/completed" || method == "turn/started" {
                    let status = if method == "turn/completed" {
                        self.project_turn(&mapping, &p["turn"])?
                    } else {
                        ExecutionStatus::Running(mapping)
                    };
                    self.statuses
                        .insert(op.operation_id.clone(), status.clone());
                    projection.observations.push(StatusObservation {
                        operation_id: op.operation_id,
                        status,
                        event_id: format!("host:{generation}:{}", event.sequence),
                    });
                }
            }
        }
        if event.sequence > self.state.cursor {
            self.state.cursor = event.sequence;
            self.save()?;
        }
        Ok(projection)
    }
    fn insert_pending(&mut self, p: PendingRequest) -> Result<()> {
        if let Some(old) = self.pending.get(&p.handle) {
            ensure!(
                old.mapping == p.mapping && old.method == p.method && old.params == p.params,
                "Native request id reused with different content"
            );
        }
        ensure!(
            self.pending.len() < 128 || self.pending.contains_key(&p.handle),
            "Too many pending requests"
        );
        self.pending.insert(p.handle.clone(), p);
        Ok(())
    }
    pub fn poll_events(&mut self, limit: usize) -> Result<EventProjection> {
        ensure!((1..=256).contains(&limit), "Invalid event page limit");
        let generation = self.state.generation.clone();
        if self.healthy(&generation, false).is_err() {
            return Ok(self.fence_unknown("disconnect"));
        }
        let batch = match self.runtime.events(self.state.cursor, limit) {
            Ok(batch) => batch,
            Err(_) => return Ok(self.fence_unknown("events-unavailable")),
        };
        ensure!(
            batch.generation == generation
                && batch.events.len() <= limit
                && batch.pending_requests.len() <= 128,
            "Invalid event batch"
        );
        if batch.snapshot_required || self.state.streaming_gap {
            self.state.streaming_gap = true;
            self.event_gap = true;
            self.save()?;
            return self.recover_stream_gap(batch, limit);
        }
        let mut current_pending = std::collections::BTreeSet::new();
        for request in &batch.pending_requests {
            bounded_json(request)?;
            ensure!(
                request["method"].is_string(),
                "Invalid pending request method"
            );
            ensure!(
                current_pending.insert(native_key(&request["id"])?),
                "Duplicate pending request identity"
            );
        }
        let mut result = if batch.snapshot_required {
            self.fence_unknown("gap")
        } else {
            EventProjection::default()
        };
        for event in batch.events {
            let p = self.ingest(&generation, event)?;
            result.observations.extend(p.observations);
            result.streams.extend(p.streams);
            result.tools.extend(p.tools);
            result.resolved_tools.extend(p.resolved_tools);
        }
        // Current live pending snapshot restores questions after adapter restart.
        for message in batch.pending_requests {
            let p = self.ingest(
                &generation,
                RuntimeEvent {
                    sequence: 0,
                    message,
                },
            )?;
            result.observations.extend(p.observations);
            result.streams.extend(p.streams);
            result.tools.extend(p.tools);
        }
        // Replay may omit the resolution event. Only a successful same-generation
        // snapshot can retire a request; disconnect/gap alone cannot do so.
        self.pending.retain(|handle, pending| {
            if current_pending.contains(handle) {
                return true;
            }
            if let Some(call) = &pending.call_id {
                result
                    .resolved_tools
                    .push((pending.mapping.clone(), call.clone()));
            }
            false
        });
        result.tools.retain(|tool| {
            self.pending
                .values()
                .any(|p| p.native_id == tool.native_id && p.mapping == tool.scope)
        });
        result.pending_snapshot = true;
        ensure!(
            batch.next_after >= self.state.cursor,
            "Event cursor moved backwards"
        );
        self.state.cursor = batch.next_after;
        self.event_gap = false;
        self.save()?;
        Ok(result)
    }
    fn recover_stream_gap(
        &mut self,
        batch: RuntimeEvents,
        limit: usize,
    ) -> Result<EventProjection> {
        let generation = self.state.generation.clone();
        let targets = self
            .state
            .operations
            .values()
            .filter(|op| {
                op.generation==generation && op.mapping.as_ref().is_some_and(|mapping| {
                    self.state
                        .threads
                        .get(&mapping.thread_id)
                        .is_some_and(|owner| {
                            owner.current_operation.as_deref() == Some(&op.operation_id)
                        })
                }) && !self.state.streaming_stopped.contains(&op.operation_id)
                    && !self.statuses.get(&op.operation_id).is_some_and(|status| {
                        matches!(
                            status,
                            ExecutionStatus::Completed(..)
                                | ExecutionStatus::Failed(..)
                                | ExecutionStatus::Interrupted(..)
                        )
                    })
            })
            .cloned()
            .collect::<Vec<_>>();
        let mut cursor = batch.next_after;
        let mut latest = batch;
        // Native history and Host replay have separate reads. Take full exact
        // snapshots, then drain replay to a quiet watermark. Any body/turn
        // event in that interval requires another snapshot: it may already be
        // included in the first one. Read-RPC replies alone do not change text.
        for _ in 0..3 {
            let mut snapshots = vec![];
            for op in &targets {
                let mapping = op.mapping.as_ref().unwrap();
                let turn = self
                    .read_exact_turn(mapping)?
                    .context("Native stream snapshot unavailable")?;
                snapshots.push((op.clone(), turn));
            }
            let mut changed = false;
            let mut quiet = false;
            for _ in 0..4 {
                let follow = self.runtime.events(cursor, limit)?;
                ensure!(
                    follow.generation == generation
                        && follow.events.len() <= limit
                        && follow.pending_requests.len() <= 128
                        && follow.next_after >= cursor,
                    "Invalid stream snapshot fence"
                );
                for event in &follow.events {
                    bounded_json(&event.message)?;
                    ensure!(
                        event.sequence > cursor && event.sequence <= follow.next_after,
                        "Invalid stream snapshot event boundary"
                    );
                    let p = &event.message["params"];
                    let turn = p["turnId"].as_str().or_else(|| p["turn"]["id"].as_str());
                    changed |= matches!(
                        event.message["method"].as_str(),
                        Some(
                            "item/agentMessage/delta"
                                | "item/completed"
                                | "turn/started"
                                | "turn/completed"
                        )
                    ) && targets.iter().any(|op| {
                        op.mapping.as_ref().is_some_and(|m| {
                            p["threadId"].as_str() == Some(&m.thread_id) && turn == Some(&m.turn_id)
                        })
                    });
                }
                changed |= follow.snapshot_required
                    || (follow.events.is_empty() && follow.next_after != cursor);
                quiet = !follow.snapshot_required
                    && follow.events.is_empty()
                    && follow.next_after == cursor;
                cursor = follow.next_after;
                latest = follow;
                if quiet || latest.snapshot_required {
                    break;
                }
            }
            if changed || !quiet {
                continue;
            }
            let previous = self.state.clone();
            let previous_pending = self.pending.clone();
            let previous_statuses = self.statuses.clone();
            let applied = (|| -> Result<EventProjection> {
                let mut result = EventProjection::default();
                for (op, turn) in snapshots {
                    let mapping = op.mapping.as_ref().unwrap();
                    let status = self.project_turn(mapping, &turn)?;
                    ensure!(
                        !matches!(status, ExecutionStatus::Unknown),
                        "Unverified native stream snapshot status"
                    );
                    if matches!(status, ExecutionStatus::Running(..)) {
                        self.clear_stream(&op.operation_id);
                        let items = turn["items"]
                            .as_array()
                            .context("Full native stream items missing")?;
                        for item in items.iter().filter(|item| item["type"] == "agentMessage") {
                            let id = item["id"]
                                .as_str()
                                .context("Native stream snapshot item ID missing")?;
                            small(id)?;
                            let text = item["text"]
                                .as_str()
                                .context("Native stream snapshot text missing")?;
                            self.store_stream(&op.operation_id, id, text, false);
                        }
                        result.streams.push(StreamObservation {
                            operation_id: op.operation_id.clone(),
                            text: self.stream_text(&op.operation_id).unwrap_or_default(),
                        });
                    }
                    self.statuses
                        .insert(op.operation_id.clone(), status.clone());
                    result.observations.push(StatusObservation {
                        operation_id: op.operation_id,
                        event_id: format!("host:stream-snapshot:{cursor}:{}", hash(&status)?),
                        status,
                    });
                }
                let pending_ids = latest
                    .pending_requests
                    .iter()
                    .map(|p| native_key(&p["id"]))
                    .collect::<Result<std::collections::BTreeSet<_>>>()?;
                for (handle, pending) in &self.pending {
                    if !pending_ids.contains(handle)
                        && let Some(call) = &pending.call_id
                    {
                        result
                            .resolved_tools
                            .push((pending.mapping.clone(), call.clone()));
                    }
                }
                self.pending.clear();
                for message in latest.pending_requests {
                    let p = self.ingest(
                        &generation,
                        RuntimeEvent {
                            sequence: 0,
                            message,
                        },
                    )?;
                    result.observations.extend(p.observations);
                    result.tools.extend(p.tools);
                }
                result.pending_snapshot = true;
                self.state.cursor = cursor;
                self.state.streaming_gap = false;
                self.save()?;
                self.event_gap = false;
                Ok(result)
            })();
            if applied.is_err() {
                self.state = previous;
                self.pending = previous_pending;
                self.statuses = previous_statuses;
                self.event_gap = true;
            }
            return applied;
        }
        anyhow::bail!(
            "Native stream snapshot is changing; retain replay fence until reconciliation"
        )
    }

    fn fence_unknown(&mut self, reason: &str) -> EventProjection {
        self.pending.clear();
        self.event_gap = true;
        let mut result = EventProjection::default();
        for op in self.state.operations.values() {
            self.statuses
                .insert(op.operation_id.clone(), ExecutionStatus::Unknown);
            result.observations.push(StatusObservation {
                operation_id: op.operation_id.clone(),
                status: ExecutionStatus::Unknown,
                event_id: format!("host:{reason}:{}", self.state.cursor),
            });
        }
        result
    }
    pub fn reject_tool(&mut self, scope: NativeMapping, call_id: &str, reason: &str) -> Result<()> {
        bounded_text(reason, 4096)?;
        let p = self
            .pending
            .values()
            .find(|p| p.mapping == scope && p.call_id.as_deref() == Some(call_id))
            .cloned()
            .context("Unowned dynamic call")?;
        self.answer_pending(p, protocol::tool_response(&json!({"error":reason}), false)?)
    }
    fn project_turn(&mut self, mapping: &NativeMapping, turn: &Value) -> Result<ExecutionStatus> {
        ensure!(
            turn["id"].as_str() == Some(mapping.turn_id.as_str()),
            "Turn read identity mismatch"
        );
        let status = match turn["status"].as_str() {
            Some("completed") => {
                // A bounded text projection must never poison completion/control
                // delivery just because the native answer exceeds the UI budget.
                let parts = turn["items"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .take(100)
                    .filter(|item| item["type"] == "agentMessage")
                    .filter_map(|item| item["text"].as_str())
                    .collect::<Vec<_>>();
                let text = clip_stream(&parts.join("\n"), 65536);
                ExecutionStatus::Completed(
                    mapping.clone(),
                    if text.is_empty() {
                        "Native turn completed; no textual result available".into()
                    } else {
                        text
                    },
                )
            }
            Some("failed") => ExecutionStatus::Failed(
                mapping.clone(),
                turn["error"]["message"]
                    .as_str()
                    .unwrap_or("Native turn failed")
                    .to_owned(),
            ),
            Some("interrupted") => ExecutionStatus::Interrupted(mapping.clone()),
            Some("inProgress") => ExecutionStatus::Running(mapping.clone()),
            _ => ExecutionStatus::Unknown,
        };
        if matches!(
            status,
            ExecutionStatus::Completed(..)
                | ExecutionStatus::Failed(..)
                | ExecutionStatus::Interrupted(..)
        ) {
            // Only a current Host pending snapshot/resolution event retires a
            // request. A completed turn can still retain an unanswered tool.
            if let Some(id) = self
                .state
                .operations
                .values()
                .find(|op| op.mapping.as_ref() == Some(mapping))
                .map(|op| op.operation_id.clone())
            {
                self.clear_stream(&id);
                self.state.streaming_stopped.insert(id);
            }
        }
        Ok(status)
    }
    fn pending_status(&self, mapping: &NativeMapping) -> Result<Option<ExecutionStatus>> {
        for p in self
            .pending
            .values()
            .filter(|p| p.mapping == *mapping && p.call_id.is_none())
        {
            let key = hash(&(&p.mapping, &p.native_id))?;
            if self.state.answers.get(&key).is_some_and(|a| a.accepted) {
                continue;
            }
            let text = serde_json::to_string(if p.method == "item/tool/requestUserInput" {
                &p.params["questions"]
            } else {
                &p.params
            })?;
            bounded_text(&text, 65536)?;
            return Ok(Some(ExecutionStatus::Waiting(
                mapping.clone(),
                UserQuestion {
                    request_id: p.handle.clone(),
                    kind: if p.method == "item/tool/requestUserInput" {
                        QuestionKind::Input
                    } else {
                        QuestionKind::Approval
                    },
                    text,
                },
            )));
        }
        Ok(None)
    }
    pub(super) fn require_idle_thread(&mut self, thread: &str) -> Result<()> {
        let meta = self.query(
            "thread/read",
            json!({"threadId":thread,"includeTurns":false}),
        )?;
        ensure!(
            meta["thread"]["id"] == thread,
            "Resume thread identity mismatch"
        );
        ensure!(
            matches!(
                meta["thread"]["status"]["type"].as_str(),
                Some("idle" | "notLoaded")
            ),
            "Current native thread is active or unverified; keep the entrusted task paused"
        );
        let page = self.query("thread/turns/list", json!({"threadId":thread,"limit":100,"sortDirection":"desc","itemsView":"full","cursor":null}))?;
        let turns = page["data"]
            .as_array()
            .context("Resume turn inventory unavailable")?;
        ensure!(
            turns.len() <= 100
                && turns.iter().all(|t| matches!(
                    t["status"].as_str(),
                    Some("completed" | "failed" | "interrupted")
                )),
            "Current native thread has an active or unverified turn; keep the entrusted task paused"
        );
        Ok(())
    }
    pub(super) fn read_exact_status(
        &mut self,
        id: &str,
        mapping: &NativeMapping,
    ) -> Result<ExecutionStatus> {
        let Some(turn) = self.read_exact_turn(mapping)? else {
            return Ok(ExecutionStatus::Unknown);
        };
        let mut status = self.project_turn(mapping, &turn)?;
        if matches!(status, ExecutionStatus::Running(..)) {
            status = self.pending_status(mapping)?.unwrap_or(status);
        }
        self.statuses.insert(id.into(), status.clone());
        if self.state.streaming_stopped.contains(id) {
            self.save()?;
        }
        Ok(status)
    }
    fn read_exact_turn(&mut self, mapping: &NativeMapping) -> Result<Option<Value>> {
        let meta = match self.query(
            "thread/read",
            json!({"threadId":mapping.thread_id,"includeTurns":false}),
        ) {
            Ok(v) => v,
            Err(_) => return Ok(None),
        };
        ensure!(
            meta["thread"]["id"].as_str() == Some(&mapping.thread_id),
            "Native reconciliation thread mismatch"
        );
        let mut cursor: Option<String> = None;
        for _ in 0..4 {
            let page=match self.query("thread/turns/list",json!({"threadId":mapping.thread_id,"limit":100,"sortDirection":"desc","itemsView":"full","cursor":cursor})){Ok(v)=>v,Err(_)=>return Ok(None)};
            let turns = page["data"]
                .as_array()
                .context("Invalid native reconciliation page")?;
            ensure!(turns.len() <= 100, "Native turn page oversized");
            if let Some(turn) = turns
                .iter()
                .find(|t| t["id"].as_str() == Some(&mapping.turn_id))
            {
                return Ok(Some(turn.clone()));
            }
            cursor = page["nextCursor"].as_str().map(str::to_owned);
            if cursor.is_none() {
                break;
            }
        }
        Ok(None)
    }
}
