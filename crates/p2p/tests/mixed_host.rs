use pocket_p2p::{DataChannelStream, Signal};
use std::{
    io::{BufRead, BufReader, Write},
    process::{Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
fn hex(value: &str) -> String {
    value
        .as_bytes()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}
fn unhex(value: &str) -> String {
    String::from_utf8(
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
            .collect(),
    )
    .unwrap()
}

/// Explicitly opt in through native/p2p/test-kotlin.ps1. Never invokes a model or public endpoint.
#[tokio::test]
#[ignore = "requires separately compiled host JVM/JNI fixture"]
async fn windows_rust_to_kotlin_jni_same_protocol() {
    let classpath = std::env::var("P2P_KOTLIN_CLASSPATH").unwrap();
    let native = std::env::var("P2P_NATIVE_DIR").unwrap();
    let mut child = Command::new("java")
        .arg(format!("-Djava.library.path={native}"))
        .args([
            "-cp",
            &classpath,
            "dev.codexpocket.wire.DataChannelFixtureKt",
            "stdio",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .unwrap();
    let mut input = child.stdin.take().unwrap();
    let mut output = BufReader::new(child.stdout.take().unwrap());
    let mut ready = String::new();
    output.read_line(&mut ready).unwrap();
    assert_eq!(ready.trim(), "READY");
    let (tx, rx) = mpsc::sync_channel(256);
    let (mut stream, control) = DataChannelStream::new(88, Some("127.0.0.1"), {
        let tx = tx.clone();
        move |signal| {
            tx.try_send((0, signal)).unwrap();
        }
    })
    .unwrap();
    let output_reader = std::thread::spawn(move || {
        for line in output.lines() {
            let line = line.unwrap();
            let parts: Vec<_> = line.split(' ').collect();
            assert_eq!(parts.len(), 3);
            let (a, b) = (unhex(parts[1]), unhex(parts[2]));
            let signal = if parts[0] == "D" {
                Signal::Description {
                    generation: 88,
                    sdp: a,
                    kind: b,
                }
            } else {
                assert_eq!(parts[0], "C");
                Signal::Candidate {
                    generation: 88,
                    candidate: a,
                    mid: b,
                }
            };
            tx.try_send((1, signal)).unwrap();
        }
    });
    let stop = Arc::new(AtomicBool::new(false));
    let finish = stop.clone();
    let other = control.clone();
    let controller = std::thread::spawn(move || {
        while !finish.load(Ordering::Relaxed) {
            if let Ok((direction, s)) = rx.recv_timeout(Duration::from_millis(20)) {
                match (direction, s) {
                    (0, Signal::Description { sdp, kind, .. }) => {
                        writeln!(input, "D {} {}", hex(&sdp), hex(&kind)).unwrap()
                    }
                    (0, Signal::Candidate { candidate, mid, .. }) => {
                        writeln!(input, "C {} {}", hex(&candidate), hex(&mid)).unwrap()
                    }
                    (
                        _,
                        Signal::Description {
                            generation,
                            sdp,
                            kind,
                        },
                    ) => other.remote_description(generation, &sdp, &kind).unwrap(),
                    (
                        _,
                        Signal::Candidate {
                            generation,
                            candidate,
                            mid,
                        },
                    ) => other
                        .remote_candidate(generation, &candidate, &mid)
                        .unwrap(),
                }
                input.flush().unwrap();
            }
        }
        writeln!(input, "QUIT").unwrap();
        input.flush().unwrap();
    });
    control.start(true).unwrap();
    let result = tokio::time::timeout(Duration::from_secs(15), async {
        control.opened().await.unwrap();
        let mut offset = 0usize;
        while offset < 2 * 1024 * 1024 + 509 {
            let n = (2 * 1024 * 1024 + 509 - offset).min(65536);
            let bytes: Vec<_> = (0..n).map(|i| ((offset + i) % 251) as u8).collect();
            stream.write_all(&bytes).await.unwrap();
            stream.flush().await.unwrap();
            let mut returned = vec![0; n];
            stream.read_exact(&mut returned).await.unwrap();
            assert_eq!(returned, bytes);
            offset += n;
        }
    })
    .await;
    stop.store(true, Ordering::Relaxed);
    controller.join().unwrap();
    assert!(child.wait().unwrap().success());
    output_reader.join().unwrap();
    result.unwrap();
}
