//! Git layer for isolated assistant write jobs: one `git worktree` per job, merged into or
//! discarded from the user's repository when the job ends. Pure functions over paths that shell
//! out to `git`; nothing here knows about the assistant's store or service.

use anyhow::{Context, Result, anyhow, bail, ensure};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::ffi::OsStr;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Output, Stdio};

const BRANCH_PREFIX: &str = "pocket/job-";
/// Commits are attributed per command; the user's Git configuration is never written.
const IDENTITY: [&str; 6] = [
    "-c",
    "user.name=Codex Pocket",
    "-c",
    "user.email=pocket@localhost",
    "-c",
    "commit.gpgsign=false",
];
const MAX_MESSAGE_BYTES: usize = 2000;
const MAX_UNTRACKED_TEXT_BYTES: u64 = 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkspaceMode {
    Direct,
    Worktree,
}

/// Why a job runs where it runs; shown to the user on the job card.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ModeReason {
    ReadOnly,
    NotGit,
    Dirty,
    Requested,
    Clean,
    GitUnavailable,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModeDecision {
    pub mode: WorkspaceMode,
    pub reason: ModeReason,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JobWorktree {
    /// Canonical top-level of the user's repository (the main working tree).
    pub repository: PathBuf,
    /// `<state_dir>/worktrees/<task_id>` (canonical).
    pub path: PathBuf,
    /// `pocket/job-<short_id>`.
    pub branch: String,
    /// Commit the worktree was created from (full hex).
    pub base: String,
    /// The job's directory inside the worktree: `path` joined with the directory's path relative
    /// to the repository top-level (equals `path` when the job directory is the top-level).
    pub directory: PathBuf,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiffStats {
    pub files: u32,
    pub insertions: u32,
    pub deletions: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "outcome", rename_all = "snake_case")]
pub enum MergeOutcome {
    Merged {
        commit: String,
    },
    NothingToMerge,
    Conflict {
        files: Vec<String>,
    },
    MainDirty,
    /// The main working tree is on no branch; a merge commit there would be left dangling.
    MainDetached,
    /// Git older than 2.38 has no `merge-tree --write-tree`; we refuse rather than touch the
    /// working tree.
    UnsupportedGit {
        version: String,
    },
}

/// `explicit` is the model/user's `use_worktree` argument when given. Never fails and never
/// mutates anything.
pub fn decide_mode(directory: &Path, writes: bool, explicit: Option<bool>) -> ModeDecision {
    let direct = |reason| ModeDecision {
        mode: WorkspaceMode::Direct,
        reason,
    };
    let worktree = |reason| ModeDecision {
        mode: WorkspaceMode::Worktree,
        reason,
    };
    if !writes {
        return direct(ModeReason::ReadOnly);
    }
    let Ok(inside) = git(directory, &["rev-parse", "--is-inside-work-tree"]) else {
        return direct(ModeReason::GitUnavailable);
    };
    let inside =
        inside.status.success() && String::from_utf8_lossy(&inside.stdout).trim() == "true";
    // A worktree is created from HEAD, so a repository without commits cannot be isolated.
    if !inside || !resolves(directory, "HEAD^{commit}") {
        return direct(ModeReason::NotGit);
    }
    match explicit {
        Some(false) => direct(ModeReason::Requested),
        Some(true) => worktree(ModeReason::Requested),
        // The worktree would not see the user's uncommitted changes, so a dirty repository is
        // not isolated by default.
        None => match is_clean(directory) {
            Ok(true) => worktree(ModeReason::Clean),
            Ok(false) => direct(ModeReason::Dirty),
            Err(_) => direct(ModeReason::GitUnavailable),
        },
    }
}

/// `pocket/job-` plus the first 8 ASCII alphanumerics of `task_id`, lowercased.
pub fn branch_name(task_id: &str) -> String {
    let short: String = task_id
        .chars()
        .filter(char::is_ascii_alphanumeric)
        .take(8)
        .collect();
    format!("{BRANCH_PREFIX}{}", short.to_ascii_lowercase())
}

/// The task id becomes a directory name, so nothing that could leave `worktrees` is accepted.
fn checked_id(task_id: &str) -> Result<()> {
    let safe = |byte: u8| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_';
    ensure!(
        !task_id.is_empty() && task_id.len() <= 128 && task_id.bytes().all(safe),
        "invalid task id"
    );
    Ok(())
}

/// `create`, or the worktree that an interrupted earlier call for the same id already made.
/// Only for a job that has not started: the adopted branch tip is taken as its base.
pub fn ensure_created(state_dir: &Path, task_id: &str, directory: &Path) -> Result<JobWorktree> {
    checked_id(task_id)?;
    let path = state_dir.join("worktrees").join(task_id);
    let in_use = path
        .read_dir()
        .is_ok_and(|mut entries| entries.next().is_some());
    if !in_use {
        return create(state_dir, task_id, directory);
    }
    let directory = dunce::canonicalize(directory).context("job directory is not accessible")?;
    let toplevel = run(&directory, &["rev-parse", "--show-toplevel"])?;
    let repository =
        dunce::canonicalize(toplevel.trim()).context("repository top-level is not accessible")?;
    let relative = directory
        .strip_prefix(&repository)
        .context("job directory is outside its repository")?
        .to_owned();
    let path = dunce::canonicalize(&path).context("job worktree is not accessible")?;
    // Taken over only if it is a checkout of this same repository on one of our branches.
    let common = |dir: &Path| -> Result<PathBuf> {
        let shared = run(dir, &["rev-parse", "--path-format=absolute", "--git-common-dir"])?;
        dunce::canonicalize(shared.trim()).context("git directory is not accessible")
    };
    ensure!(
        common(&path)? == common(&repository)?,
        "job worktree path belongs to another repository"
    );
    let branch = run(&path, &["rev-parse", "--abbrev-ref", "HEAD"])?.trim().to_owned();
    ensure!(
        branch.starts_with(&branch_name(task_id)),
        "job worktree path is on another branch"
    );
    let worktree = JobWorktree {
        base: head(&path)?,
        directory: path.join(relative),
        repository,
        path,
        branch,
    };
    validate(&worktree)?;
    std::fs::create_dir_all(&worktree.directory).context("job directory could not be created")?;
    Ok(worktree)
}

pub fn create(state_dir: &Path, task_id: &str, directory: &Path) -> Result<JobWorktree> {
    checked_id(task_id)?;
    let stem = branch_name(task_id);
    ensure!(
        stem.len() >= BRANCH_PREFIX.len() + 4,
        "task id is too short to name a job branch"
    );

    let directory = dunce::canonicalize(directory).context("job directory is not accessible")?;
    let toplevel = run(&directory, &["rev-parse", "--show-toplevel"])?;
    let repository =
        dunce::canonicalize(toplevel.trim()).context("repository top-level is not accessible")?;
    let relative = directory
        .strip_prefix(&repository)
        .context("job directory is outside its repository")?
        .to_owned();
    let base = head(&repository)?;

    let root = state_dir.join("worktrees");
    std::fs::create_dir_all(&root).context("worktrees directory could not be created")?;
    let root = dunce::canonicalize(&root).context("worktrees directory is not accessible")?;
    let path = root.join(task_id);
    if path.symlink_metadata().is_ok() {
        let empty = path
            .read_dir()
            .is_ok_and(|mut entries| entries.next().is_none());
        ensure!(empty, "job worktree path is already in use");
    }
    let branch = (1..=9)
        .map(|attempt| {
            if attempt == 1 {
                stem.clone()
            } else {
                format!("{stem}-{attempt}")
            }
        })
        .find(|branch| !resolves(&repository, &format!("refs/heads/{branch}")))
        .context("job branch name is already taken")?;

    let add = || -> Result<JobWorktree> {
        let arguments = ["worktree", "add", "-b", &branch].map(OsStr::new);
        run(
            &repository,
            &[&arguments[..], &[path.as_os_str(), OsStr::new(&base)]].concat(),
        )?;
        let path = dunce::canonicalize(&path).context("job worktree is not accessible")?;
        // Git only checks out tracked files; the job still needs its directory to start in.
        let directory = path.join(&relative);
        std::fs::create_dir_all(&directory).context("job directory could not be created")?;
        Ok(JobWorktree {
            repository: repository.clone(),
            path,
            branch: branch.clone(),
            base: base.clone(),
            directory,
        })
    };
    add().inspect_err(|_| {
        let _ = remove(&repository, &path, &branch);
    })
}

/// Read-only: everything the job changed relative to `base` (committed on the job branch,
/// staged, unstaged and untracked).
pub fn stats(worktree: &JobWorktree) -> Result<DiffStats> {
    validate(worktree)?;
    let mut stats = DiffStats::default();
    let mut files = BTreeSet::new();
    let base = worktree.base.as_str();
    let diff = run(
        &worktree.path,
        &[
            "diff",
            "--numstat",
            "-z",
            "-M",
            "--no-ext-diff",
            "--no-textconv",
            base,
            "--",
        ],
    )?;
    let mut records = diff.split('\0');
    while let Some(record) = records.next() {
        let mut fields = record.splitn(3, '\t');
        let (Some(added), Some(deleted), Some(path)) =
            (fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        // A rename has an empty path here and is followed by its old and new names.
        let path = if path.is_empty() {
            records.nth(1).unwrap_or_default()
        } else {
            path
        };
        files.insert(path.to_owned());
        // Binary files report `-` for both counts.
        stats.insertions = stats.insertions.saturating_add(added.parse().unwrap_or(0));
        stats.deletions = stats.deletions.saturating_add(deleted.parse().unwrap_or(0));
    }
    let untracked = run(
        &worktree.path,
        &["ls-files", "--others", "--exclude-standard", "-z"],
    )?;
    for path in untracked.split('\0').filter(|path| !path.is_empty()) {
        if files.insert(path.to_owned()) {
            let lines = text_lines(&worktree.path.join(path));
            stats.insertions = stats.insertions.saturating_add(lines);
        }
    }
    stats.files = u32::try_from(files.len()).unwrap_or(u32::MAX);
    Ok(stats)
}

/// Commit every change in the worktree onto the job branch. Returns the new commit, or None when
/// there was nothing to commit.
pub fn snapshot(worktree: &JobWorktree, message: &str) -> Result<Option<String>> {
    validate(worktree)?;
    ensure!(
        !message.trim().is_empty() && message.len() <= MAX_MESSAGE_BYTES,
        "commit message must be non-empty and at most {MAX_MESSAGE_BYTES} bytes"
    );
    // A job that switched branches would otherwise have its work committed where `merge` and
    // `discard` never look.
    let current = run(
        &worktree.path,
        &["symbolic-ref", "--quiet", "--short", "HEAD"],
    );
    ensure!(
        current.is_ok_and(|current| current.trim() == worktree.branch),
        "job worktree is no longer on its job branch"
    );
    run(&worktree.path, &["add", "-A"])?;
    let staged = git(&worktree.path, &["diff", "--cached", "--quiet"])?;
    match staged.status.code() {
        Some(0) => return Ok(None),
        Some(1) => {}
        _ => return Err(failure("diff", &staged)),
    }
    let arguments = ["commit", "--no-verify", "--quiet", "-m", message];
    run(&worktree.path, &[&IDENTITY[..], &arguments].concat())?;
    head(&worktree.path).map(Some)
}

/// Snapshot, pre-check with `git merge-tree --write-tree`, and only then merge into the
/// repository's current branch. The main working tree is untouched unless the merge is certain
/// to succeed. The worktree itself is kept; call `discard` afterwards.
pub fn merge(worktree: &JobWorktree, message: &str) -> Result<MergeOutcome> {
    validate(worktree)?;
    let (repository, branch) = (worktree.repository.as_path(), worktree.branch.as_str());
    let banner = run(repository, &["--version"])?;
    let version = banner
        .trim()
        .strip_prefix("git version ")
        .unwrap_or(banner.trim());
    let version = version
        .split_whitespace()
        .next()
        .unwrap_or_default()
        .to_owned();
    if !supports_merge_tree(&version) {
        return Ok(MergeOutcome::UnsupportedGit { version });
    }
    // The job's sandbox cannot write the main repository's `.git`, so its work is normally
    // still uncommitted.
    snapshot(worktree, message)?;
    if run(
        repository,
        &["rev-list", "--count", &format!("HEAD..{branch}")],
    )?
    .trim()
        == "0"
    {
        return Ok(MergeOutcome::NothingToMerge);
    }
    // An unfinished merge of the user's can look clean, and aborting ours would abort theirs.
    if !is_clean(repository)? || resolves(repository, "MERGE_HEAD") {
        return Ok(MergeOutcome::MainDirty);
    }
    if !git(repository, &["symbolic-ref", "--quiet", "HEAD"])?.status.success() {
        return Ok(MergeOutcome::MainDetached);
    }

    let arguments = ["merge-tree", "--write-tree", "--name-only", "HEAD", branch];
    let check = git(
        repository,
        &[&["-c", "core.quotePath=false"][..], &arguments].concat(),
    )?;
    let listing = String::from_utf8_lossy(&check.stdout);
    let mut lines = listing.lines();
    let tree = lines.next().unwrap_or_default();
    match check.status.code() {
        Some(0) => {}
        // Conflicted names follow the tree id; informational messages follow an empty line.
        Some(1) => {
            let files: BTreeSet<String> = lines
                .take_while(|line| !line.is_empty())
                .map(str::to_owned)
                .collect();
            return Ok(MergeOutcome::Conflict {
                files: files.into_iter().collect(),
            });
        }
        _ => return Err(failure("merge-tree", &check)),
    }
    // Git silently overwrites ignored files when it merges. A path the merge adds that already
    // exists in the clean main working tree is such a file, so it is reported as a conflict.
    let added = run(
        repository,
        &[
            "diff",
            "--name-only",
            "-z",
            "--no-renames",
            "--diff-filter=A",
            "HEAD",
            tree,
            "--",
        ],
    )?;
    let files: Vec<String> = added
        .split('\0')
        .filter(|path| !path.is_empty() && repository.join(path).symlink_metadata().is_ok())
        .map(str::to_owned)
        .collect();
    if !files.is_empty() {
        return Ok(MergeOutcome::Conflict { files });
    }

    let arguments = [
        "merge",
        "--no-ff",
        "--no-verify",
        "--quiet",
        "-m",
        message,
        branch,
    ];
    if let Err(error) = run(repository, &[&IDENTITY[..], &arguments].concat()) {
        let _ = git(repository, &["merge", "--abort"]);
        return Err(error);
    }
    Ok(MergeOutcome::Merged {
        commit: head(repository)?,
    })
}

/// Remove the worktree directory and delete the job branch. Idempotent.
pub fn discard(worktree: &JobWorktree) -> Result<()> {
    validate(worktree)?;
    remove(&worktree.repository, &worktree.path, &worktree.branch)
}

/// A `JobWorktree` is read back from storage, so before Git commits in it or deletes it, it must
/// still look like one that `create` made: never the user's repository, never their branches.
fn validate(worktree: &JobWorktree) -> Result<()> {
    let JobWorktree {
        repository,
        path,
        branch,
        base,
        ..
    } = worktree;
    ensure!(
        path.is_absolute()
            && !path
                .components()
                .any(|part| matches!(part, Component::ParentDir))
            && path.parent().and_then(Path::file_name) == Some(OsStr::new("worktrees"))
            && !repository.starts_with(path),
        "job worktree path is not a managed worktree"
    );
    let suffix = branch.strip_prefix(BRANCH_PREFIX).unwrap_or_default();
    let safe = |byte: u8| byte.is_ascii_alphanumeric() || byte == b'-';
    ensure!(
        !suffix.is_empty() && suffix.bytes().all(safe),
        "job branch is not a managed branch"
    );
    ensure!(
        base.len() >= 40 && base.bytes().all(|byte| byte.is_ascii_hexdigit()),
        "job base is not a commit id"
    );
    Ok(())
}

fn remove(repository: &Path, path: &Path, branch: &str) -> Result<()> {
    let arguments = ["worktree", "remove", "--force"].map(OsStr::new);
    let _ = git(repository, &[&arguments[..], &[path.as_os_str()]].concat());
    if path.symlink_metadata().is_ok() {
        std::fs::remove_dir_all(path).context("job worktree directory could not be removed")?;
    }
    let _ = git(repository, &["worktree", "prune"]);
    let _ = git(repository, &["branch", "-D", branch]);
    // Already absent is fine; only a branch that survived deletion is an error.
    let survived = resolves(repository, &format!("refs/heads/{branch}"));
    ensure!(!survived, "job branch could not be deleted");
    Ok(())
}

/// `git merge-tree --write-tree` exists since Git 2.38.
fn supports_merge_tree(version: &str) -> bool {
    let mut parts = version.split('.').map(|part| part.parse::<u32>().ok());
    matches!(
        (parts.next().flatten(), parts.next().flatten()),
        (Some(major), Some(minor)) if (major, minor) >= (2, 38)
    )
}

/// Lines of an untracked file, or 0 when it is not a small regular text file.
fn text_lines(path: &Path) -> u32 {
    let small = |metadata: std::fs::Metadata| {
        metadata.is_file() && metadata.len() < MAX_UNTRACKED_TEXT_BYTES
    };
    if !path.symlink_metadata().is_ok_and(small) {
        return 0;
    }
    match std::fs::read(path).map(String::from_utf8) {
        Ok(Ok(text)) if !text.contains('\0') => {
            u32::try_from(text.lines().count()).unwrap_or(u32::MAX)
        }
        _ => 0,
    }
}

fn head(dir: &Path) -> Result<String> {
    Ok(run(dir, &["rev-parse", "--verify", "HEAD^{commit}"])?
        .trim()
        .to_owned())
}

fn resolves(dir: &Path, revision: &str) -> bool {
    git(dir, &["rev-parse", "--verify", "--quiet", revision])
        .is_ok_and(|output| output.status.success())
}

/// Untracked files count as dirty whatever `status.showUntrackedFiles` says.
fn is_clean(dir: &Path) -> Result<bool> {
    Ok(
        run(dir, &["status", "--porcelain", "--untracked-files=normal"])?
            .trim()
            .is_empty(),
    )
}

/// Runs git and returns its stdout, failing on a non-zero exit.
fn run<S: AsRef<OsStr>>(dir: &Path, args: &[S]) -> Result<String> {
    let output = git(dir, args)?;
    if !output.status.success() {
        let subcommand = args
            .iter()
            .map(|arg| arg.as_ref().to_string_lossy())
            .find(|arg| !arg.starts_with('-') && !arg.contains('='))
            .unwrap_or_default();
        bail!(failure(&subcommand, &output));
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Carries Git's own (truncated) complaint, which names paths but never file contents.
fn failure(subcommand: &str, output: &Output) -> anyhow::Error {
    let stderr = String::from_utf8_lossy(&output.stderr);
    let detail: String = stderr.trim().chars().take(300).collect();
    anyhow!("git {subcommand} failed ({}): {detail}", output.status)
}

fn git<S: AsRef<OsStr>>(dir: &Path, args: &[S]) -> Result<Output> {
    let mut command = Command::new("git");
    command.arg("-C").arg(dir);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // The state directory adds a long prefix to every checked-out path.
        command
            .args(["-c", "core.longpaths=true"])
            .creation_flags(0x08000000);
    }
    // Inherited repository overrides (set while running inside a Git hook) would defeat `-C`.
    for name in [
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_INDEX_FILE",
        "GIT_COMMON_DIR",
        "GIT_PREFIX",
    ] {
        command.env_remove(name);
    }
    // Without optional locks, read-only commands such as `status` do not rewrite the index.
    command
        .args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_OPTIONAL_LOCKS", "0")
        .env("LC_ALL", "C")
        .stdin(Stdio::null());
    command.output().context("git could not be started")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    const JOB_A: &str = "0a1b2c3d-1111-4111-8111-111111111111";
    const JOB_B: &str = "4e5f6a7b-2222-4222-8222-222222222222";

    struct Fixture {
        _temp: tempfile::TempDir,
        repo: PathBuf,
        state: PathBuf,
    }

    /// A committed repository and a separate state directory inside one temporary directory.
    fn fixture() -> Fixture {
        let temp = tempfile::tempdir().unwrap();
        let root = dunce::canonicalize(temp.path()).unwrap();
        let (repo, state) = (root.join("repo"), root.join("state"));
        fs::create_dir_all(&repo).unwrap();
        sh(&repo, &["init", "--quiet", "--initial-branch=main"]);
        // Local to the temporary repository: keeps `status` clean on Windows.
        sh(&repo, &["config", "core.autocrlf", "false"]);
        fs::write(repo.join("a.txt"), "one\ntwo\n").unwrap();
        commit_all(&repo, "initial");
        Fixture {
            _temp: temp,
            repo,
            state,
        }
    }

    fn sh(dir: &Path, args: &[&str]) -> String {
        let output = git(dir, &[&IDENTITY[..], args].concat()).unwrap();
        let stderr = String::from_utf8_lossy(&output.stderr);
        assert!(output.status.success(), "git {args:?}: {stderr}");
        String::from_utf8_lossy(&output.stdout).trim().to_owned()
    }

    fn commit_all(dir: &Path, message: &str) -> String {
        sh(dir, &["add", "-A"]);
        sh(dir, &["commit", "--quiet", "-m", message]);
        head_of(dir)
    }

    fn head_of(dir: &Path) -> String {
        sh(dir, &["rev-parse", "HEAD"])
    }

    fn status(dir: &Path) -> String {
        sh(dir, &["status", "--porcelain"])
    }

    fn read(path: PathBuf) -> String {
        fs::read_to_string(path).unwrap()
    }

    fn decision(mode: WorkspaceMode, reason: ModeReason) -> ModeDecision {
        ModeDecision { mode, reason }
    }

    #[test]
    fn parallel_git_write_jobs_use_isolated_worktrees_simultaneously() {
        let fx = fixture();
        let clean = decision(WorkspaceMode::Worktree, ModeReason::Clean);

        assert_eq!(decide_mode(&fx.repo, true, None), clean);
        let first = create(&fx.state, JOB_A, &fx.repo).unwrap();
        // A running job's worktree does not make the repository dirty for the next job.
        assert_eq!(decide_mode(&fx.repo, true, None), clean);
        let second = create(&fx.state, JOB_B, &fx.repo).unwrap();

        fs::write(first.directory.join("first.txt"), "1\n").unwrap();
        fs::write(second.directory.join("second.txt"), "2\n").unwrap();
        assert!(first.path.join("first.txt").is_file());
        assert!(!first.path.join("second.txt").exists());
        assert!(second.path.join("second.txt").is_file());
        assert!(!second.path.join("first.txt").exists());
        assert!(!fx.repo.join("first.txt").exists());
        assert!(!fx.repo.join("second.txt").exists());
        assert_eq!(status(&fx.repo), "");

        assert_eq!(first.branch, "pocket/job-0a1b2c3d");
        assert!(second.branch.starts_with("pocket/job-"));
        assert_ne!(first.branch, second.branch);
        assert_ne!(first.path, second.path);
        assert_eq!(
            (first.directory.as_path(), first.repository.as_path()),
            (&*first.path, &*fx.repo)
        );
        assert_eq!(first.base, head_of(&fx.repo));
        assert_eq!(second.base, first.base);
    }

    #[test]
    fn non_git_directory_falls_back_to_serial_writer_slot() {
        let temp = tempfile::tempdir().unwrap();
        let plain = dunce::canonicalize(temp.path()).unwrap();
        let not_git = decision(WorkspaceMode::Direct, ModeReason::NotGit);
        assert_eq!(decide_mode(&plain, true, None), not_git);
        assert_eq!(decide_mode(&plain, true, Some(true)), not_git);
        assert_eq!(decide_mode(&plain.join("missing"), true, None), not_git);
        let read_only = decision(WorkspaceMode::Direct, ModeReason::ReadOnly);
        assert_eq!(decide_mode(&plain, false, None), read_only);
        assert_eq!(decide_mode(&plain, false, Some(true)), read_only);
        assert!(create(&plain.join("state"), JOB_A, &plain).is_err());

        // A repository without a commit has nothing to create a worktree from.
        sh(&plain, &["init", "--quiet"]);
        assert_eq!(decide_mode(&plain, true, Some(true)), not_git);
        assert!(create(&plain.join("state"), JOB_A, &plain).is_err());
    }

    #[test]
    fn dirty_repository_defaults_to_direct_mode() {
        let fx = fixture();
        let dirty = decision(WorkspaceMode::Direct, ModeReason::Dirty);
        let requested = decision(WorkspaceMode::Worktree, ModeReason::Requested);

        fs::write(fx.repo.join("a.txt"), "one\nedited\n").unwrap();
        assert_eq!(decide_mode(&fx.repo, true, None), dirty);
        assert_eq!(decide_mode(&fx.repo, true, Some(true)), requested);
        assert_eq!(
            decide_mode(&fx.repo, false, Some(true)).reason,
            ModeReason::ReadOnly
        );

        fs::write(fx.repo.join("a.txt"), "one\ntwo\n").unwrap();
        fs::write(fx.repo.join("untracked.txt"), "new\n").unwrap();
        assert_eq!(decide_mode(&fx.repo, true, None), dirty);
        assert_eq!(decide_mode(&fx.repo, true, Some(true)), requested);

        fs::remove_file(fx.repo.join("untracked.txt")).unwrap();
        assert_eq!(decide_mode(&fx.repo, true, None).reason, ModeReason::Clean);
        let direct = decision(WorkspaceMode::Direct, ModeReason::Requested);
        assert_eq!(decide_mode(&fx.repo, true, Some(false)), direct);
    }

    #[test]
    fn merge_snapshots_uncommitted_job_changes_before_merging() {
        let fx = fixture();
        let job = create(&fx.state, JOB_A, &fx.repo).unwrap();
        assert_eq!(stats(&job).unwrap(), DiffStats::default());
        fs::write(job.directory.join("a.txt"), "one\nTWO\n").unwrap();
        fs::write(job.directory.join("new.txt"), "x\ny\n").unwrap();

        let expected = DiffStats {
            files: 2,
            insertions: 3,
            deletions: 1,
        };
        let before = status(&job.path);
        assert_eq!(stats(&job).unwrap(), expected);
        assert_eq!(status(&job.path), before);
        assert!(
            before.contains("?? new.txt"),
            "stats must not stage anything: {before}"
        );

        let MergeOutcome::Merged { commit } = merge(&job, "Merge job").unwrap() else {
            panic!("expected the job to merge");
        };
        assert_eq!(commit, head_of(&fx.repo));
        assert_eq!(read(fx.repo.join("a.txt")), "one\nTWO\n");
        assert_eq!(read(fx.repo.join("new.txt")), "x\ny\n");
        assert_eq!(status(&fx.repo), "");
        assert_eq!(
            sh(&fx.repo, &["log", "-1", "--format=%an <%ae>"]),
            "Codex Pocket <pocket@localhost>"
        );
        // The identity is passed per command and never written to the repository.
        assert!(!read(fx.repo.join(".git").join("config")).contains("[user]"));

        // Committed job work still counts against the base, and there is nothing left to merge.
        assert_eq!(stats(&job).unwrap(), expected);
        assert_eq!(snapshot(&job, "Again").unwrap(), None);
        assert_eq!(
            merge(&job, "Merge job").unwrap(),
            MergeOutcome::NothingToMerge
        );
        assert_eq!(commit, head_of(&fx.repo));
    }

    #[test]
    fn stats_counts_renames_binaries_and_committed_work_once() {
        let fx = fixture();
        fs::write(fx.repo.join("image.bin"), [0u8, 1, 2, 3]).unwrap();
        commit_all(&fx.repo, "add binary");
        let job = create(&fx.state, JOB_A, &fx.repo).unwrap();
        sh(&job.path, &["mv", "a.txt", "renamed.txt"]);
        fs::write(job.directory.join("image.bin"), [0u8, 9, 9, 9]).unwrap();
        fs::write(job.directory.join("blob.bin"), [0u8, 159, 146, 150]).unwrap();

        let expected = DiffStats {
            files: 3,
            insertions: 0,
            deletions: 0,
        };
        assert_eq!(stats(&job).unwrap(), expected);
        assert!(snapshot(&job, "Snapshot").unwrap().is_some());
        assert_eq!(stats(&job).unwrap(), expected);
        assert_eq!(status(&job.path), "");
    }

    #[test]
    fn merge_detects_conflict_without_mutating_main_worktree() {
        let fx = fixture();
        let job = create(&fx.state, JOB_A, &fx.repo).unwrap();
        fs::write(fx.repo.join("a.txt"), "one\nmain\n").unwrap();
        let head = commit_all(&fx.repo, "main change");
        fs::write(job.directory.join("a.txt"), "one\njob\n").unwrap();

        let outcome = merge(&job, "Merge job").unwrap();
        assert_eq!(
            outcome,
            MergeOutcome::Conflict {
                files: vec!["a.txt".to_owned()]
            }
        );
        assert_eq!(head_of(&fx.repo), head);
        assert_eq!(read(fx.repo.join("a.txt")), "one\nmain\n");
        assert_eq!(status(&fx.repo), "");
        assert!(!fx.repo.join(".git").join("MERGE_HEAD").exists());
        // The job's work stays on its branch for the user to resolve or discard.
        assert_eq!(read(job.directory.join("a.txt")), "one\njob\n");
        assert_eq!(merge(&job, "Merge job").unwrap(), outcome);
    }

    #[test]
    fn merge_refuses_when_main_worktree_is_dirty() {
        let fx = fixture();
        let job = create(&fx.state, JOB_A, &fx.repo).unwrap();
        fs::write(job.directory.join("new.txt"), "x\n").unwrap();
        let head = head_of(&fx.repo);
        fs::write(fx.repo.join("a.txt"), "one\nedited\n").unwrap();

        assert_eq!(merge(&job, "Merge job").unwrap(), MergeOutcome::MainDirty);
        assert_eq!(read(fx.repo.join("a.txt")), "one\nedited\n");
        assert_eq!(head_of(&fx.repo), head);
        assert!(!fx.repo.join("new.txt").exists());
        assert_eq!(status(&fx.repo), "M a.txt");
    }

    #[test]
    fn merge_reports_ignored_main_file_as_conflict_instead_of_overwriting() {
        let fx = fixture();
        fs::write(fx.repo.join(".gitignore"), "local.env\n").unwrap();
        let head = commit_all(&fx.repo, "ignore local file");
        fs::write(fx.repo.join("local.env"), "secret\n").unwrap();
        let job = create(&fx.state, JOB_A, &fx.repo).unwrap();
        fs::write(job.directory.join(".gitignore"), "").unwrap();
        fs::write(job.directory.join("local.env"), "job\n").unwrap();

        let outcome = merge(&job, "Merge job").unwrap();
        assert_eq!(
            outcome,
            MergeOutcome::Conflict {
                files: vec!["local.env".to_owned()]
            }
        );
        assert_eq!(read(fx.repo.join("local.env")), "secret\n");
        assert_eq!(head_of(&fx.repo), head);
    }

    #[test]
    fn discard_removes_worktree_and_branch_and_is_idempotent() {
        let fx = fixture();
        let job = create(&fx.state, JOB_A, &fx.repo).unwrap();
        fs::write(job.directory.join("new.txt"), "x\n").unwrap();

        discard(&job).unwrap();
        assert!(!job.path.exists());
        assert_eq!(sh(&fx.repo, &["branch", "--list", "pocket/job-*"]), "");
        assert_eq!(
            sh(&fx.repo, &["worktree", "list", "--porcelain"])
                .matches("worktree ")
                .count(),
            1
        );
        discard(&job).unwrap();

        // Neither the user's repository nor one of their branches can be named for deletion.
        let repository = JobWorktree {
            path: fx.repo.clone(),
            ..job.clone()
        };
        assert!(discard(&repository).is_err());
        assert!(snapshot(&repository, "Snapshot").is_err());
        let user_branch = JobWorktree {
            branch: "main".to_owned(),
            ..job.clone()
        };
        assert!(discard(&user_branch).is_err());
        assert!(fx.repo.join("a.txt").is_file());
        assert_eq!(sh(&fx.repo, &["branch", "--list", "main"]), "* main");
        assert_eq!(status(&fx.repo), "");
    }

    #[test]
    fn create_rejects_unsafe_task_ids_and_reports_subdirectory() {
        let fx = fixture();
        for task_id in [
            "../x",
            "a/b",
            "",
            "a.b",
            "a\\b",
            "..",
            "abc",
            &"a".repeat(129),
        ] {
            assert!(create(&fx.state, task_id, &fx.repo).is_err(), "{task_id:?}");
        }
        assert_eq!(sh(&fx.repo, &["branch", "--list", "pocket/job-*"]), "");

        fs::create_dir_all(fx.repo.join("sub")).unwrap();
        fs::write(fx.repo.join("sub").join("file.txt"), "s\n").unwrap();
        commit_all(&fx.repo, "add sub");
        let job = create(&fx.state, JOB_A, &fx.repo.join("sub")).unwrap();
        let state = dunce::canonicalize(&fx.state).unwrap();
        assert_eq!(job.repository, fx.repo);
        assert_eq!(job.path, state.join("worktrees").join(JOB_A));
        assert_eq!(job.directory, job.path.join("sub"));
        assert!(job.directory.join("file.txt").is_file());

        // A second create for the same task fails without disturbing the first.
        assert!(create(&fx.state, JOB_A, &fx.repo).is_err());
        assert!(job.directory.join("file.txt").is_file());
        assert_eq!(
            sh(&fx.repo, &["branch", "--list", "pocket/job-*"]),
            "+ pocket/job-0a1b2c3d"
        );
        // A task whose short id collides gets the next free branch name.
        let twin = create(&fx.state, "0A1B2C3D_twin", &fx.repo).unwrap();
        assert_eq!(twin.branch, "pocket/job-0a1b2c3d-2");
    }

    #[test]
    fn merge_requires_git_with_merge_tree_write_tree() {
        for version in ["2.38.0", "2.49.0.windows.1", "3.0"] {
            assert!(supports_merge_tree(version), "{version}");
        }
        for version in ["2.37.9", "1.9.5", "2", "", "unknown"] {
            assert!(!supports_merge_tree(version), "{version}");
        }
    }

    #[test]
    fn wire_names_are_stable() {
        let conflict = MergeOutcome::Conflict {
            files: vec!["a.txt".to_owned()],
        };
        assert_eq!(
            serde_json::to_value(conflict).unwrap(),
            serde_json::json!({ "outcome": "conflict", "files": ["a.txt"] })
        );
        assert_eq!(
            serde_json::to_value(MergeOutcome::NothingToMerge).unwrap(),
            serde_json::json!({ "outcome": "nothing_to_merge" })
        );
        assert_eq!(
            serde_json::to_value(ModeReason::GitUnavailable).unwrap(),
            "git_unavailable"
        );
        assert_eq!(
            serde_json::to_value(WorkspaceMode::Worktree).unwrap(),
            "worktree"
        );
        assert_eq!(
            serde_json::to_value(DiffStats::default()).unwrap(),
            serde_json::json!({ "files": 0, "insertions": 0, "deletions": 0 })
        );
    }
}
