use super::*;

impl<E: Executor> AssistantService<E> {
    pub(super) fn operation_inputs(&self, op: &Operation) -> Vec<AssistantInput> {
        if let Some(task) = &op.task_id {
            return self
                .state
                .task_inputs
                .get(task)
                .cloned()
                .unwrap_or_default();
        }
        let mut inputs = Vec::new();
        for source in &op.user_message_ids {
            for input in self.state.message_inputs.get(source).into_iter().flatten() {
                if !inputs
                    .iter()
                    .any(|old: &AssistantInput| old.id() == input.id())
                {
                    inputs.push(input.clone());
                }
            }
        }
        inputs
    }
    pub(super) fn inherit_task_inputs(&mut self, task: &str, op: &Operation) {
        let inputs = self.operation_inputs(op);
        if !inputs.is_empty() {
            self.state.task_inputs.insert(task.into(), inputs);
        }
    }
    pub(super) fn delivery_inputs(&self, id: &str) -> Vec<AssistantInput> {
        self.state
            .message_inputs
            .get(&format!("user:{id}"))
            .cloned()
            .unwrap_or_default()
    }
}
