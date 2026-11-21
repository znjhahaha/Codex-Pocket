use super::*;

impl<R: RuntimeIo> HostExecutor<R> {
    /// A new authenticated Host owns only new attempts. Retained operations and
    /// answers stay bound to their original generations and cannot be resumed.
    pub fn adopt_scoped_generation(&mut self)->Result<bool> {
        let h=self.runtime.health()?;valid_health(&h)?;
        if h.generation==self.state.generation || !h.scoped_admission {return Ok(false);}
        ensure!(h.runtime_id==self.state.runtime_id && h.online && h.initialized && h.protocol_verified,
            "Scoped Host identity unverified");
        ensure!(self.pending.is_empty() && self.state.scoped_generations.len()<64,"Scoped transition capacity or pending input");
        let recovery=self.runtime.recovery()?;
        let digest=recovery["evidenceDigest"].as_str().context("Scoped Host recovery evidence missing")?;
        ensure!(recovery["generation"]==h.generation && recovery["sequence"].as_u64().is_some()
            && recovery["pendingRequests"].is_array() && recovery["runs"].is_array(),"Scoped recovery identity missing");
        let commands=recovery["commandCount"].as_u64().context("Command inventory missing")?;
        let answers=recovery["answerCount"].as_u64().context("Answer inventory missing")?;
        ensure!(commands.checked_add(answers).is_some_and(|n|n<=pocket_runtime_ipc::MAX_RECOVERY_RECORDS as u64),"Scoped recovery capacity invalid");
        let mut effects=std::collections::BTreeSet::new();
        for op in self.state.operations.values() {
            ensure!(op.generation!=h.generation,"New Host generation already has conflicting local history");
            for id in [&op.thread_rpc_id,&op.turn_rpc_id] {
                let key=native_key(id)?;
                if let Some(effect)=self.state.effects.get(&key) {
                    ensure!(effects.insert(key),"Effect has multiple owners");
                    if effect.rejected {continue;}
                    let receipt=self.runtime.historical_receipt(id.clone(),&op.generation)?;
                    ensure!(receipt.generation==op.generation && matches!(receipt.state.as_str(),"completed"|"unknown"),
                        "Original effect receipt not retained");
                }
            }
        }
        ensure!(effects.len()==self.state.effects.len(),"Unowned local effect");
        let mut found=std::collections::BTreeSet::new();let mut offset=0;
        loop {
            let page=self.runtime.answer_evidence(offset,digest)?;
            page.validate(&h.runtime_id,&h.generation,digest,offset)?;
            ensure!(page.total==answers as usize,"Answer inventory changed");
            for answer in page.records {
                for mapping in self.state.operations.values().filter_map(|op|op.mapping.as_ref()).filter(|m|m.host_generation==answer.generation) {
                    let key=hash(&(mapping,&answer.request_id))?;
                    if self.state.answers.contains_key(&key) {ensure!(found.insert(key),"Answer identity repeated");}
                }
            }
            if let Some(next)=page.next_offset {offset=next;} else {break;}
        }
        ensure!(found.len()==self.state.answers.len(),"Original answer evidence missing");
        let after=self.runtime.health()?;
        ensure!(after.runtime_id==h.runtime_id && after.generation==h.generation && after.scoped_admission
            && after.online && after.initialized && after.protocol_verified && self.runtime.recovery()?==recovery,
            "Scoped recovery changed during verification");
        let previous=self.state.clone();
        self.state.scoped_generations.push(json!({"previousGeneration":previous.generation,"generation":h.generation,
            "previousCursor":previous.cursor,"previousEventGap":self.event_gap,"previousStreamingGap":previous.streaming_gap,
            "evidenceDigest":digest,"retainedOperations":previous.operations.len(),"retainedAnswers":previous.answers.len()}));
        self.state.generation=h.generation;self.state.cursor=0;self.state.streaming_gap=false;
        if let Err(e)=self.save() {self.state=previous;return Err(e);}
        self.statuses.clear();self.event_gap=false;
        Ok(true)
    }

    pub fn current_generation_ready(&self, generation: &str) -> bool {
        self.state.generation == generation
    }
    pub fn released_generations(&mut self)->Result<std::collections::BTreeSet<String>> {
        let before=self.healthy(&self.state.generation.clone(),false)?;
        if !before.scoped_admission {return Ok(Default::default());}
        let recovery=self.runtime.recovery()?;
        ensure!(recovery["generation"]==before.generation,"Released generation snapshot mismatch");
        let Some(values)=recovery.get("releasedGenerations") else {return Ok(Default::default());};
        let values=values.as_array().context("Released generations malformed")?;
        ensure!(values.len()<=pocket_runtime_ipc::MAX_RECOVERY_RECORDS,"Released generation inventory oversized");
        let mut released=std::collections::BTreeSet::new();
        for value in values {
            let generation=value.as_str().context("Invalid released generation")?;
            ensure!(generation!=before.generation && uuid::Uuid::parse_str(generation).is_ok()
                && released.insert(generation.to_owned()),"Invalid released generation identity");
        }
        self.healthy(&before.generation,false)?;Ok(released)
    }

    pub fn reconcile_terminal_generation(&mut self, operations: &[crate::assistant::Operation]) -> Result<bool> {
        let before = self.runtime.health()?;
        valid_health(&before)?;
        if before.generation == self.state.generation { return Ok(false); }
        ensure!(before.runtime_id == self.state.runtime_id && before.online && before.initialized
            && before.protocol_verified && !before.reconciliation_required, "Cold runtime identity/readiness unverified");
        ensure!(self.pending.is_empty() && self.state.effects.values().all(|e| e.accepted != e.rejected),
            "Old native effects remain unknown or contradictory");
        ensure!(self.state.terminal_generations.len() < 64, "Terminal generation history capacity reached");
        self.verify_terminal_inventory(operations)?;

        let recovery = self.runtime.recovery()?;
        ensure!(recovery["generation"] == before.generation && recovery["reconciliationRequired"] == false
            && recovery["unconfirmedWrites"].as_u64() == Some(0) && recovery["unknownOperations"].as_u64() == Some(0)
            && recovery["pendingRequests"].as_array().is_some_and(|v| v.is_empty()), "Cold Host recovery is not proven idle");
        let runs = recovery["runs"].as_array().context("Cold Host runs missing")?;
        ensure!(runs.len() <= 256 && runs.iter().all(|run| run["state"] == "completed"),
            "Cold Host contains an unconfirmed run");
        let commands = recovery["commandCount"].as_u64().context("Cold Host command count missing")?;
        let answers = recovery["answerCount"].as_u64().context("Cold Host answer count missing")?;
        ensure!(commands.checked_add(answers).is_some_and(|n| n <= pocket_runtime_ipc::MAX_RECOVERY_RECORDS as u64),
            "Cold Host receipt budget exceeded");
        let digest = recovery["evidenceDigest"].as_str().context("Cold Host evidence digest unavailable")?;
        let mut identified = BTreeMap::new();
        let mut receipts = BTreeMap::new();
        for record in self.state.operations.values() {
            if let Some(mapping) = &record.mapping {
                ensure!(runs.iter().filter(|run| run["generation"] == mapping.host_generation
                    && run["threadId"] == mapping.thread_id && run["turnId"] == mapping.turn_id
                    && run["state"] == "completed").count() == 1, "Original mapped run completion missing or ambiguous");
            }
            for id in [&record.thread_rpc_id, &record.turn_rpc_id] {
                let key = native_key(id)?;
                if let Some(effect) = self.state.effects.get(&key) {
                    ensure!(identified.insert(key.clone(), record.operation_id.clone()).is_none(),
                        "Native effect belongs to multiple operations");
                    if effect.rejected { continue; }
                    let receipt = self.runtime.historical_receipt(id.clone(), &record.generation)?;
                    ensure!(receipt.generation == record.generation && receipt.state == "completed",
                        "Original command receipt not completed");
                    if let Some(response) = receipt.response {
                        ensure!(response["id"] == *id && (response.get("result").is_some() ^ response.get("error").is_some()),
                            "Original command receipt identity invalid");
                    }
                    receipts.insert(key, record.generation.clone());
                } else {
                    ensure!(record.mapping.is_none() && record.rejected, "Mapped operation effect inventory missing");
                }
            }
        }
        ensure!(identified.len() == self.state.effects.len(), "Unidentified historical effect cannot migrate");
        let answer_receipts = self.verify_terminal_answers(&before, digest, answers as usize)?;

        // Completion need not advance the event sequence: all pages bind the
        // complete persisted allowlist digest, then the recovery is reread.
        let after = self.runtime.health()?;
        let final_recovery = self.runtime.recovery()?;
        ensure!(after.runtime_id == before.runtime_id && after.generation == before.generation
            && after.online && after.initialized && after.protocol_verified && !after.reconciliation_required
            && final_recovery == recovery, "Cold recovery changed during receipt verification");
        let original = self.state.clone();
        self.state.terminal_generations.push(json!({"previousGeneration":original.generation,"generation":before.generation,
            "previousCursor":original.cursor,"recoveryDigest":hash(&recovery)?,"evidenceDigest":digest,
            "commandReceipts":receipts,"answerReceipts":answer_receipts}));
        self.state.generation = before.generation;
        self.state.cursor = 0;
        self.state.streaming_gap = false;
        if let Err(error) = self.save() { self.state = original; return Err(error); }
        self.statuses.clear();
        self.event_gap = false;
        Ok(true)
    }

    fn verify_terminal_inventory(&self, operations: &[crate::assistant::Operation]) -> Result<()> {
        use crate::assistant::{HeldInputResolution, OperationPhase};
        let mut service = BTreeMap::new();
        let mut mapped = std::collections::BTreeSet::new();
        for op in operations {
            ensure!(service.insert(op.id.clone(), op).is_none()
                && matches!(op.phase,OperationPhase::Completed|OperationPhase::Failed|OperationPhase::Paused),
                "Service inventory contains duplicate or live/unknown operation");
            ensure!(op.question.is_none() && op.held_inputs.iter().all(|q| q.resolution == HeldInputResolution::Resolved)
                && (op.input_tool_call.is_none() || op.held_inputs.iter().any(|q|
                    Some(&q.call_id) == op.input_tool_call.as_ref() && q.resolution == HeldInputResolution::Resolved)),
                "Old service question is not terminal");
            if let Some(mapping) = &op.mapping {
                small(&mapping.host_generation)?;small(&mapping.thread_id)?;small(&mapping.turn_id)?;
                ensure!(mapping.host_generation == op.expected_generation && mapped.insert((mapping.host_generation.clone(),
                    mapping.thread_id.clone(), mapping.turn_id.clone())) && self.state.operations.contains_key(&op.id),
                    "Mapped service operation inventory missing or duplicated");
            } else if !self.state.operations.contains_key(&op.id) {
                let proof = self.state.not_dispatched.get(&op.id).context("Unmapped service operation evidence missing")?;
                ensure!(op.phase == OperationPhase::Paused
                    && proof.expected_generation.as_deref().unwrap_or(&proof.generation) == op.expected_generation
                    && proof.previous_generation != proof.generation && proof.thread_rpc_id == rpc_id(&op.id,"thread")
                    && proof.turn_rpc_id == rpc_id(&op.id,"turn"), "Service non-dispatch tombstone identity mismatch");
            }
        }
        for record in self.state.operations.values() {
            let op = service.get(&record.operation_id).context("Service operation evidence missing")?;
            ensure!(op.expected_generation == record.generation && op.mapping == record.mapping && op.role == record.role
                && record.thread_rpc_id == rpc_id(&record.operation_id,"thread")
                && record.turn_rpc_id == rpc_id(&record.operation_id,"turn"), "Cold service/native operation scope mismatch");
            if let Some(mapping) = &record.mapping {
                ensure!(record.thread_submitted && record.turn_submitted && !record.rejected
                    && record.thread_id.as_ref() == Some(&mapping.thread_id)
                    && self.state.threads.contains_key(&mapping.thread_id), "Mapped native operation ownership invalid");
            } else {
                ensure!(record.rejected && !record.thread_submitted && !record.turn_submitted
                    && op.phase == OperationPhase::Paused, "Unmapped operation is not proven rejected before dispatch");
            }
        }
        for (thread, owner) in &self.state.threads {
            let id = owner.current_operation.as_ref().context("Thread owner operation missing")?;
            let record = self.state.operations.get(id).context("Thread owner operation inventory missing")?;
            let mapping = record.mapping.as_ref().context("Thread owner mapping unavailable")?;
            ensure!(&mapping.thread_id == thread && mapping.host_generation == owner.generation
                && record.role == owner.role && record.directory == owner.directory && record.writes == owner.writes,
                "Thread owner scope mismatch");
        }
        Ok(())
    }

    fn verify_terminal_answers(&mut self, health: &RuntimeHealth, digest: &str, total: usize) -> Result<BTreeMap<String, Value>> {
        let mappings = self.state.operations.values().filter_map(|r| r.mapping.clone()).collect::<Vec<_>>();
        let mut verified = BTreeMap::new();
        let mut identities = std::collections::BTreeSet::new();
        let mut offset = 0;
        loop {
            let page = self.runtime.answer_evidence(offset, digest)?;
            page.validate(&health.runtime_id, &health.generation, digest, offset)?;
            ensure!(page.total == total, "Answer evidence inventory count mismatch");
            for answer in page.records {
                let native_id = native_key(&answer.request_id)?;
                ensure!(identities.insert((answer.generation.clone(), native_id)) && answer.completed,
                    "Historical answer is repeated or not completed");
                let mut matches = Vec::new();
                for mapping in mappings.iter().filter(|m| m.host_generation == answer.generation) {
                    let key = hash(&(mapping, &answer.request_id))?;
                    if self.state.answers.contains_key(&key) { matches.push(key); }
                }
                ensure!(matches.len() <= 1, "Historical answer mapping is ambiguous");
                if let Some(key) = matches.pop() {
                    ensure!(verified.insert(key, json!({"generation":answer.generation,"requestId":answer.request_id})).is_none(),
                        "Local answer has multiple historical identities");
                }
            }
            match page.next_offset { Some(next) => offset = next, None => break }
        }
        ensure!(verified.len() == self.state.answers.len(), "Local answer lacks original generation terminal evidence");
        // Confirm only the original native write ended; preserve local accepted
        // flags, absent answer bodies, and semantic answer Unknown unchanged.
        Ok(verified)
    }
}
