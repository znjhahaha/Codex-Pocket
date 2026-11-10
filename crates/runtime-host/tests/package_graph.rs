//! Offline package-level guard: disabling a feature is not dependency isolation.
use serde_json::Value;
use std::{collections::BTreeSet, path::Path, process::Command};

fn metadata(manifest: &Path, features: &str) -> Value {
    let output = Command::new(env!("CARGO"))
        .args([
            "metadata",
            "--offline",
            "--format-version",
            "1",
            "--manifest-path",
        ])
        .arg(manifest)
        .arg(features)
        .output()
        .expect("run Cargo metadata");
    assert!(
        output.status.success(),
        "offline metadata failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).expect("valid Cargo metadata")
}

fn package<'a>(graph: &'a Value, name: &str) -> &'a Value {
    graph["packages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["name"] == name)
        .unwrap_or_else(|| panic!("missing package {name}"))
}

fn resolved_closure(graph: &Value, name: &str) -> BTreeSet<String> {
    let mut visited = BTreeSet::new();
    let mut pending = vec![package(graph, name)["id"].as_str().unwrap().to_owned()];
    while let Some(id) = pending.pop() {
        if !visited.insert(id.clone()) {
            continue;
        }
        let node = graph["resolve"]["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|node| node["id"] == id)
            .expect("resolved package node");
        pending.extend(
            node["deps"]
                .as_array()
                .unwrap()
                .iter()
                .map(|dep| dep["pkg"].as_str().unwrap().to_owned()),
        );
    }
    graph["packages"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|p| visited.contains(p["id"].as_str().unwrap()))
        .map(|p| p["name"].as_str().unwrap().to_owned())
        .collect()
}

// Check declarations as well as activated edges. Optional/dev/platform path
// dependencies must not conceal a cycle under later feature unification.
fn declared_path_closure(graph: &Value, name: &str) -> BTreeSet<String> {
    let mut visited = BTreeSet::new();
    let mut pending = vec![package(graph, name)];
    while let Some(p) = pending.pop() {
        if !visited.insert(p["name"].as_str().unwrap().to_owned()) {
            continue;
        }
        for dep in p["dependencies"].as_array().unwrap() {
            if let Some(path) = dep["path"].as_str() {
                let manifest = Path::new(path).join("Cargo.toml");
                let target = graph["packages"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|p| Path::new(p["manifest_path"].as_str().unwrap()) == manifest)
                    .expect("every declared path dependency must be present for proof");
                pending.push(target);
            }
        }
    }
    visited
}

fn assert_isolated(names: &BTreeSet<String>) {
    for forbidden in ["pocket-agent", "pocket-runtime-host"] {
        assert!(
            !names.contains(forbidden),
            "IPC reaches forbidden package {forbidden}"
        );
    }
}

#[test]
fn ipc_package_is_independent_even_with_host_server_features() {
    let host_manifest = Path::new(env!("CARGO_MANIFEST_DIR")).join("Cargo.toml");
    let graph = metadata(&host_manifest, "--all-features");
    let host = package(&graph, "pocket-runtime-host");
    assert!(
        host["dependencies"]
            .as_array()
            .unwrap()
            .iter()
            .any(|d| d["name"] == "pocket-runtime-ipc" && d["optional"] == false),
        "host must depend on the independent pocket-runtime-ipc package"
    );
    let declared = declared_path_closure(&graph, "pocket-runtime-ipc");
    assert_isolated(&declared);
    assert_isolated(&resolved_closure(&graph, "pocket-runtime-ipc"));
    assert!(
        resolved_closure(&graph, "pocket-runtime-host").contains("pocket-agent"),
        "all-feature proof must actually activate the server storage dependency"
    );

    let client_only = metadata(&host_manifest, "--no-default-features");
    let client_names = resolved_closure(&client_only, "pocket-runtime-host");
    assert!(client_names.contains("pocket-runtime-ipc"));
    assert!(!client_names.contains("pocket-agent"));

    let ipc_manifest = host_manifest
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .join("runtime-ipc/Cargo.toml");
    for features in ["--all-features", "--no-default-features"] {
        let standalone = metadata(&ipc_manifest, features);
        assert_isolated(&resolved_closure(&standalone, "pocket-runtime-ipc"));
        assert_isolated(&declared_path_closure(&standalone, "pocket-runtime-ipc"));
        let all_packages = standalone["packages"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| p["name"].as_str().unwrap().to_owned())
            .collect();
        assert_isolated(&all_packages);
    }
    println!(
        "IPC declared path graph: {declared:?}; all-feature host storage edge exercised; standalone and client-only graphs isolated"
    );
}
