use super::*;
use std::sync::{Arc, Mutex, OnceLock, Weak};

/// Local authority returned by a fresh read of the selected source runtime.
/// Deliberately not deserializable from a phone request or persisted grant.
#[derive(Clone, Debug)]
pub struct ProjectPermissionProof {
    pub runtime_id: String,
    pub generation: String,
    pub thread_id: String,
    pub cwd: PathBuf,
    pub revision: String,
    pub settings: Value,
}

/// The broker supplies native effective settings on its authenticated connection.
/// Implementations must recheck connection/process identity after their read and
/// reject stale/unknown projections. Called from the serialized assistant worker.
pub trait ProjectPermissionReader: Send + Sync {
    fn read(&self, runtime: &str, thread: &str, cwd: &Path) -> Result<ProjectPermissionProof>;
}
impl<F> ProjectPermissionReader for F
where
    F: Fn(&str, &str, &Path) -> Result<ProjectPermissionProof> + Send + Sync,
{
    fn read(&self, runtime: &str, thread: &str, cwd: &Path) -> Result<ProjectPermissionProof> {
        self(runtime, thread, cwd)
    }
}
type Readers = BTreeMap<PathBuf, Weak<dyn ProjectPermissionReader>>;
fn readers() -> &'static Mutex<Readers> {
    static READERS: OnceLock<Mutex<Readers>> = OnceLock::new();
    READERS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
/// Register before restore_assistant/enable. The callback should hold only a
/// Weak<Broker> and must not acquire the broker mutation serializer. The broker
/// retains the reader; this registry cannot keep a broker or worker alive.
pub fn register_project_permission_reader(
    state: &Path,
    reader: Arc<dyn ProjectPermissionReader>,
) -> Result<()> {
    let state = canonical(state)?;
    let mut registered = readers()
        .lock()
        .map_err(|_| anyhow::anyhow!("Project permission reader unavailable"))?;
    registered.retain(|_, reader| reader.strong_count() > 0);
    ensure!(
        registered.contains_key(&state) || registered.len() < 64,
        "Too many project permission readers"
    );
    registered.insert(state, Arc::downgrade(&reader));
    Ok(())
}
pub(crate) fn project_permission_reader(
    state: &Path,
) -> Result<Option<Arc<dyn ProjectPermissionReader>>> {
    let state = canonical(state)?;
    let mut registered = readers()
        .lock()
        .map_err(|_| anyhow::anyhow!("Project permission reader unavailable"))?;
    registered.retain(|_, reader| reader.strong_count() > 0);
    Ok(registered.get(&state).and_then(Weak::upgrade))
}
