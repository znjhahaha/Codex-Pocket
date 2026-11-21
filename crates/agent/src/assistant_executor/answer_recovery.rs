use super::*;

impl<R: RuntimeIo> HostExecutor<R> {
    fn confirmed_answer(&mut self, scope: &NativeMapping, id: Value) -> Result<RuntimeReceipt> {
        self.healthy(&scope.host_generation, false)?;
        let receipt = self.runtime.answer_receipt(id.clone())?;
        self.healthy(&scope.host_generation, false)?;
        ensure!(
            receipt.generation == scope.host_generation,
            "Answer receipt generation mismatch"
        );
        match receipt.state.as_str() {
            "completed" => {
                let v = receipt
                    .response
                    .as_ref()
                    .context("Answer confirmation unavailable")?;
                ensure!(
                    v["id"] == id && v.get("error").is_none() && v["result"]["written"] == true,
                    "Answer write not confirmed"
                );
            }
            "missing" => ensure!(receipt.response.is_none(), "Invalid missing answer receipt"),
            _ => anyhow::bail!("Answer remains unknown; never resend"),
        }
        Ok(receipt)
    }

    pub(super) fn mark_answer_accepted(&mut self, key: &str) -> Result<()> {
        let old = self
            .state
            .answers
            .get(key)
            .context("Missing answer reservation")?
            .accepted;
        self.state.answers.get_mut(key).unwrap().accepted = true;
        if let Err(e) = self.save() {
            self.state.answers.get_mut(key).unwrap().accepted = old;
            return Err(e);
        }
        Ok(())
    }

    /// Read-only reconciliation of exact answer IDs. A missing/unknown receipt
    /// cannot authorize a retry; only written=true confirms the original answer.
    pub fn reconcile_answer_receipts(&mut self) -> Result<()> {
        for p in self.pending_requests() {
            let key = hash(&(&p.mapping, &p.native_id))?;
            if self.state.answers.contains_key(&key)
                && self
                    .confirmed_answer(&p.mapping, p.native_id.clone())
                    .is_ok_and(|r| r.state == "completed")
            {
                self.mark_answer_accepted(&key)?;
                self.runtime
                    .confirm_answer_receipt(&p.mapping.host_generation, &p.native_id)?;
            }
        }
        Ok(())
    }

    fn rejection_body(&self, scope: &NativeMapping, id: &Value) -> Result<Value> {
        let key = hash(&(scope, id))?;
        // Legacy reservations held only a digest. Match the exact known refusal;
        // never replace an unknown successful result with a validation error.
        for reason in [
            "Task directory not authorized by this handoff",
            "Original tool cancelled by user",
        ] {
            let result = protocol::tool_response(&json!({"error":reason}), false)?;
            if self
                .state
                .answers
                .get(&key)
                .is_some_and(|a| a.fingerprint == hash(&result).unwrap_or_default())
            {
                return Ok(result);
            }
        }
        ensure!(
            !self.state.answers.contains_key(&key),
            "Original answer content cannot be reconstructed safely"
        );
        protocol::tool_response(&json!({"error":"Original tool cancelled by user"}), false)
    }

    pub fn rejection_recovery_available(&self, p: &PendingRequest) -> bool {
        p.method == "item/tool/call"
            && self
                .state
                .operations
                .get(&p.operation_id)
                .is_some_and(|o| o.role == Role::Coordinator)
            && (self.state.streaming_stopped.contains(&p.operation_id)
                || hash(&(&p.mapping, &p.native_id))
                    .is_ok_and(|key| self.state.answers.contains_key(&key)))
            && self.rejection_body(&p.mapping, &p.native_id).is_ok()
    }

    /// Explicit human refusal through assistant/respond. This sends only the
    /// original rejection, never executes a tool or grants a project directory.
    pub fn reject_retained_tool(&mut self, scope: NativeMapping, handle: &str) -> Result<()> {
        self.healthy(&scope.host_generation, true)?;
        let op = self
            .state
            .operations
            .values()
            .find(|o| o.mapping.as_ref() == Some(&scope))
            .context("Unowned tool scope")?;
        ensure!(
            op.role == Role::Coordinator
                && self.state.threads[&scope.thread_id]
                    .current_operation
                    .as_deref()
                    == Some(&op.operation_id),
            "Retained tool does not belong to the current coordinator"
        );
        let id = if let Some(s) = handle.strip_prefix("s:") {
            json!(s)
        } else if let Some(s) = handle.strip_prefix("n:") {
            serde_json::from_str(s)?
        } else {
            anyhow::bail!("Invalid native request handle")
        };
        ensure!(native_key(&id)? == handle, "Native request handle mismatch");
        let key = hash(&(&scope, &id))?;
        let result = self.rejection_body(&scope, &id)?;
        let receipt = self.confirmed_answer(&scope, id.clone())?;
        if receipt.state == "completed" {
            self.mark_answer_accepted(&key)?;
            return self
                .runtime
                .confirm_answer_receipt(&scope.host_generation, &id);
        }
        ensure!(
            !self.state.answers.get(&key).is_some_and(|a| a.accepted),
            "Accepted answer cannot be resent even if a later receipt is missing"
        );
        let p = self
            .pending
            .get(handle)
            .cloned()
            .context("Original request is no longer pending")?;
        ensure!(
            p.mapping == scope && p.method == "item/tool/call",
            "Original dynamic tool scope mismatch"
        );
        // Completion alone does not retire this request. Verify its entire live
        // snapshot and a second missing receipt in the same healthy generation.
        let batch = self.runtime.events(self.state.cursor, 1)?;
        ensure!(
            batch.generation == scope.host_generation
                && !batch.snapshot_required
                && batch.events.len() <= 1
                && batch.pending_requests.len() <= 128
                && batch.next_after >= self.state.cursor
                && batch
                    .pending_requests
                    .iter()
                    .any(|v| v["id"] == id && v["method"] == p.method && v["params"] == p.params),
            "Original pending tool identity changed; retain the answer fence"
        );
        ensure!(
            self.confirmed_answer(&scope, id.clone())?.state == "missing",
            "Answer receipt changed; reconcile without retry"
        );
        self.healthy(&scope.host_generation, true)?;
        if !self.state.answers.contains_key(&key) {
            self.state.answers.insert(
                key.clone(),
                Effect {
                    fingerprint: hash(&result)?,
                    accepted: false,
                    rejected: false,
                },
            );
            self.save()?;
        }
        // Keep the same reservation and exact body. Host also deduplicates this
        // original ID if an earlier transport frame arrives after the read.
        self.runtime.answer(id.clone(), result)?;
        self.mark_answer_accepted(&key)?;
        self.runtime
            .confirm_answer_receipt(&scope.host_generation, &id)
    }
}
