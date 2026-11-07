//! QuickJS has no host bindings. All HTTP happens outside this VM.
use super::Failure;
use serde_json::Value;
use std::time::{Duration, Instant};
pub const MAX_SCRIPT: usize = 64 * 1024;
pub const MAX_BODY: usize = 1024 * 1024;
pub fn evaluate(code: &str, response: Option<&str>) -> Result<Value, Failure> {
    if code.len() > MAX_SCRIPT || response.is_some_and(|s| s.len() > MAX_BODY) {
        return Err(Failure::Limit);
    }
    let rt = rquickjs::Runtime::new().map_err(|_| Failure::Script)?;
    rt.set_memory_limit(16 * 1024 * 1024);
    rt.set_max_stack_size(256 * 1024);
    let deadline = Instant::now() + Duration::from_millis(250);
    rt.set_interrupt_handler(Some(Box::new(move || Instant::now() >= deadline)));
    let ctx = rquickjs::Context::full(&rt).map_err(|_| Failure::Script)?;
    let result = ctx.with(|ctx| -> rquickjs::Result<String> {
        let config: rquickjs::Object = ctx.eval(code)?;
        let value: rquickjs::Value = if let Some(response) = response {
            let extractor: rquickjs::Function = config.get("extractor")?;
            extractor.call((ctx.json_parse(response)?,))?
        } else {
            // Validate both phases before issuing even a read-only HTTP query.
            let _: rquickjs::Function = config.get("extractor")?;
            config.get("request")?
        };
        ctx.json_stringify(value)?
            .ok_or(rquickjs::Error::Unknown)?
            .to_string()
    });
    let text = result.map_err(|_| {
        if Instant::now() >= deadline {
            Failure::Timeout
        } else {
            Failure::Script
        }
    })?;
    if text.len() > MAX_SCRIPT {
        return Err(Failure::Limit);
    }
    serde_json::from_str(&text).map_err(|_| Failure::Script)
}
