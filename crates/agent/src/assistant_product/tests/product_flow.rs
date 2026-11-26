use super::*;

#[tokio::test]
async fn assistant_attachment_image_artifact_continuation_consumes_selected_immutable_version() {
    let mut f=Fixture::new(false).await;
    let s=f.send("image-artifact").await;
    let lead=Fixture::mapping(&Fixture::op(&s,"Coordinator"));
    let s=f.create(&lead,9302,"Create image").await;
    let execution=Fixture::mapping(&Fixture::op(&s,"Execution"));
    let image=f.project.path().join("diagram.png");let original=b"\x89PNG\r\n\x1a\noriginal-version";
    std::fs::write(&image,original).unwrap();
    f.io.complete(&execution,&format!("[diagram]({})",image.display()));
    let snapshot=f.read().await;
    let file=snapshot["artifacts"].as_array().unwrap().iter().find(|a|a["name"]=="diagram.png").unwrap().clone();
    assert_eq!(file["availability"],"available");
    std::fs::write(&image,b"\x89PNG\r\n\x1a\nnewer-version").unwrap();
    f.call("assistant/send",json!({"message":"Revise the selected image","taskId":file["taskId"],
        "artifactId":file["id"],"artifactVersion":file["version"]}),"continue-image",false).await.unwrap();
    let state=f.io.0.lock().unwrap();
    let input=state.calls.iter().rev().filter(|(_,method,_)|method=="turn/start").flat_map(|(_,_,p)|p["input"].as_array().unwrap())
        .find(|i|i["type"]=="localImage").expect("selected image must reach native input");
    let path=PathBuf::from(input["path"].as_str().unwrap());
    assert_ne!(path,image);assert_eq!(std::fs::read(path).unwrap(),original);
    drop(state);f.stop().await;
}

#[tokio::test]
async fn product_general_schedule_creates_owned_workspace_task_and_keeps_history() {
    let mut f=Fixture::new(false).await;
    let created=f.call("assistant/schedule/create",json!({"spec":{"prompt":"Make a daily brief","kind":{"Daily":{"hour":9,"minute":0}},"writes":false,"zone":{"name":"Asia/Shanghai"}}}),"general-schedule",false).await.unwrap();
    let schedule=created["snapshot"]["schedules"][0].clone();
    assert!(created["snapshot"]["projectAuthorization"].as_array().unwrap().is_empty());
    assert_eq!(schedule["missed_policy"],"skip");
    let id=schedule["id"].as_str().unwrap();
    f.call("assistant/schedule/run",json!({"scheduleId":id}),"manual",false).await.unwrap();
    f.call("assistant/schedule/run",json!({"scheduleId":id}),"manual",false).await.unwrap();
    let state=f.read().await;
    assert_eq!(state["tasks"].as_array().unwrap().len(),1);
    assert_eq!(state["scheduleRuns"].as_array().unwrap().len(),1);
    assert_eq!(state["scheduleRuns"][0]["task_id"],state["tasks"][0]["id"]);
    assert_eq!(PathBuf::from(state["tasks"][0]["directory"].as_str().unwrap()),dunce::canonicalize(f.root.path().join("assistant/workspace")).unwrap());
    f.stop().await;
}

#[tokio::test]
async fn product_artifact_snapshot_is_immutable_and_rejects_outside_task_scope() {
    let mut f=Fixture::new(false).await;
    let s=f.send("artifact").await;
    let coordinator=Fixture::mapping(&Fixture::op(&s,"Coordinator"));
    let s=f.create(&coordinator,9301,"Write report").await;
    let execution=Fixture::mapping(&Fixture::op(&s,"Execution"));
    let report=f.project.path().join("report.txt");
    let outside=f.root.path().join("outside.txt");
    std::fs::write(&report,b"original version").unwrap();
    std::fs::write(&outside,b"not authorized").unwrap();
    f.io.complete(&execution,&format!("[report]({}) [outside]({})",report.display(),outside.display()));
    let snapshot=f.read().await;
    let artifacts=snapshot["artifacts"].as_array().unwrap();
    let file=artifacts.iter().find(|a|a["name"]=="report.txt").unwrap().clone();
    assert_eq!(file["availability"],"available");
    assert_eq!(artifacts.iter().find(|a|a["name"]=="outside.txt").unwrap()["availability"],"unavailable");
    std::fs::write(&report,b"new overwritten version").unwrap();
    let content=f.call("assistant/artifact/read",json!({"artifactId":file["id"],"offset":0}),"download",false).await.unwrap();
    use base64::Engine;
    assert_eq!(base64::engine::general_purpose::STANDARD.decode(content["data"].as_str().unwrap()).unwrap(),b"original version");
    assert!(f.call("assistant/artifact/read",json!({"artifactId":file["id"],"path":outside}),"phone-path",false).await.is_err());
    assert!(f.call("assistant/send",json!({"message":"continue", "taskId":file["taskId"],"artifactId":file["id"],"artifactVersion":"wrong-version"}),"wrong-version",false).await.is_err());
    let continued=f.call("assistant/send",json!({"message":"continue", "taskId":file["taskId"],"artifactId":file["id"],"artifactVersion":file["version"]}),"continue-original",false).await.unwrap();
    assert!(continued["snapshot"]["deliveries"].as_array().unwrap().iter().any(|d|d["message"].as_str().is_some_and(|m|m.contains("Read-only source snapshot:") && m.contains(file["sha256"].as_str().unwrap()))));
    f.stop().await;
}
