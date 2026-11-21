//! Opt-in read-only probe of the same snapshot consumed by the installed UI.
use super::*;
use pocket_desktop_runtime::{installation, supervisor::maintenance::ProcessIdentity};

#[test]
#[ignore = "Requires an installed Pocket UI PID and explicit output path; never changes live state"]
fn installed_dashboard_snapshot() {
    let pid: u32 = std::env::var("POCKET_DASHBOARD_PROBE_PID").unwrap().parse().unwrap();
    let output = PathBuf::from(std::env::var_os("POCKET_DASHBOARD_PROBE_OUTPUT").unwrap());
    let root = storage::local_app_data().unwrap().join("CodexPocket");
    let (bin, state) = launcher::resolve_release(&root).unwrap();
    let context = Context::new(state, bin.clone());
    let mut report = snapshot(&context);
    // The probe is not the UI. Independently authenticate the actual UI process
    // instead of promoting this test executable's own stamp into UI evidence.
    let ui = ProcessIdentity::capture(pid).unwrap();
    let ui_bin = ui.executable.parent().unwrap();
    let manifest: Value = storage::read(&ui_bin.join("windows-manifest.json")).unwrap();
    let stamp = installation::RuntimeStamp {
        version: manifest["version"].as_str().unwrap().into(),
        build_id: manifest["buildId"].as_str().unwrap().into(),
        generation: Some(installation::generation(ui_bin).unwrap()),
        pid,
        process_created_at: Some(ui.created_at),
        executable_sha256: Some(manifest["files"].as_array().unwrap().iter()
            .find(|e| e["path"] == "pocket-desktop.exe").unwrap()["sha256"].as_str().unwrap().into()),
        ..Default::default()
    };
    let verified = installation::report(report["installation"]["installedGeneration"].as_str().unwrap(),
        report["installation"]["installedVersion"].as_str().unwrap(), &stamp, &Value::Null, &Value::Null, true);
    assert_eq!(verified["ui"]["runningVerified"], true);
    report["installation"]["ui"] = verified["ui"].clone();
    // Keep only the state needed by the view, without addresses or paired IDs.
    report["computerName"] = json!("本机实际连接");
    report["relay"]["host"] = json!("");
    report["devices"] = json!([]);
    for key in ["routes", "cloudflareReceipt", "connectivity", "sessionPreferences", "discoveryErrors"] {
        report.as_object_mut().unwrap().remove(key);
    }
    report["observedAt"] = json!(storage::now());
    storage::atomic(&output, &report).unwrap();
    assert_eq!(report["connection"]["available"], true, "Snapshot must agree with independently connected helper");
    println!("{}", json!({"observedAt":report["observedAt"],"connection":report["connection"],
        "uiVerified":verified["ui"]["runningVerified"],"upgrade":report["installation"]["upgrade"],
        "issues":report["issues"]}));
}
