pub fn restart_delay(failures: u32) -> u64 { (1u64 << failures.min(5)).min(30) }
pub fn should_run(desired: bool, has_child: bool) -> bool { desired && !has_child }
pub fn redact(input: &str) -> String {
    let patterns = [
        (r"(?i)Bearer\s+[^\s,;]+", "Bearer [redacted]"),
        (r#"(?i)((?:[a-z]*token|password|secret|authorization|api[_-]?key)\s*[=:]\s*)[^\s,;\"}]+"#, "$1[redacted]"),
        (r"(?i)[A-Z]:\\Users\\[^\s\\]+", "[user]"),
        (r"(?i)(?:wss?|https?)://[^\s]+", "[endpoint]"),
    ];
    patterns.iter().fold(input.to_owned(), |s,(p,r)| regex::Regex::new(p).unwrap().replace_all(&s,*r).into_owned())
}
