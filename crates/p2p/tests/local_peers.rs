use pocket_p2p::{DataChannelStream, Signal};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_peers_full_duplex_and_shutdown() {
    let (tx, rx) = mpsc::sync_channel(256);
    let caller = std::thread::current().id();
    let off_thread = Arc::new(AtomicBool::new(true));
    let make = |index| {
        let tx = tx.clone();
        let off_thread = off_thread.clone();
        DataChannelStream::new(42, Some("127.0.0.1"), move |s| {
            if std::thread::current().id() == caller {
                off_thread.store(false, Ordering::Relaxed);
            }
            tx.try_send((index, s)).unwrap();
        })
        .unwrap()
    };
    let (a, ca) = make(0);
    let (b, cb) = make(1);
    assert!(ca.remote_candidate(41, "invalid", "0").is_err());
    cb.start(false).unwrap();
    ca.start(true).unwrap();
    let controls = [cb.clone(), ca.clone()];
    let stop = Arc::new(AtomicBool::new(false));
    let finish = stop.clone();
    let signaling = std::thread::spawn(move || {
        while !finish.load(Ordering::Relaxed) {
            if let Ok((index, signal)) = rx.recv_timeout(Duration::from_millis(20)) {
                match signal {
                    Signal::Description {
                        generation,
                        sdp,
                        kind,
                    } => controls[index]
                        .remote_description(generation, &sdp, &kind)
                        .unwrap(),
                    Signal::Candidate {
                        generation,
                        candidate,
                        mid,
                    } => controls[index]
                        .remote_candidate(generation, &candidate, &mid)
                        .unwrap(),
                }
            }
        }
    });
    tokio::time::timeout(Duration::from_secs(15), async {
        ca.opened().await.unwrap();
        cb.opened().await.unwrap();
        let (mut ar, mut aw) = tokio::io::split(a);
        let (mut br, mut bw) = tokio::io::split(b);
        let bytes: Vec<u8> = (0..2 * 1024 * 1024 + 513)
            .map(|i| (i % 251) as u8)
            .collect();
        let write = async {
            aw.write_all(&bytes).await.unwrap();
            aw.flush().await.unwrap();
        };
        let echo = async {
            let mut got = 0;
            let mut block = [0u8; 1997];
            while got < bytes.len() {
                let n = br.read(&mut block).await.unwrap();
                assert!(n > 0);
                assert_eq!(&block[..n], &bytes[got..got + n]);
                bw.write_all(&block[..n]).await.unwrap();
                got += n;
            }
            bw.flush().await.unwrap();
        };
        let read = async {
            let mut returned = vec![0u8; bytes.len()];
            ar.read_exact(&mut returned).await.unwrap();
            assert_eq!(returned, bytes);
        };
        tokio::join!(write, echo, read);
        aw.shutdown().await.unwrap();
        assert_eq!(br.read(&mut [0u8; 1]).await.unwrap(), 0);
    })
    .await
    .unwrap();
    assert!(off_thread.load(Ordering::Relaxed));
    stop.store(true, Ordering::Relaxed);
    signaling.join().unwrap();
}

#[tokio::test]
async fn pending_reader_wakes_on_close() {
    let (mut stream, control) = DataChannelStream::new(7, Some("127.0.0.1"), |_| {}).unwrap();
    let read = tokio::spawn(async move { stream.read(&mut [0u8; 1]).await });
    tokio::time::sleep(Duration::from_millis(10)).await;
    control.close(false);
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), read)
            .await
            .unwrap()
            .unwrap()
            .unwrap(),
        0
    );
}

#[tokio::test]
async fn bounded_preopen_writer_wakes_on_close() {
    let (mut stream, control) = DataChannelStream::new(8, Some("127.0.0.1"), |_| {}).unwrap();
    let block = vec![1; 65536];
    for _ in 0..16 {
        stream.write_all(&block).await.unwrap();
    }
    let write = tokio::spawn(async move { stream.write_all(&[2]).await });
    tokio::time::sleep(Duration::from_millis(10)).await;
    assert!(!write.is_finished());
    control.close(false);
    assert!(
        tokio::time::timeout(Duration::from_secs(2), write)
            .await
            .unwrap()
            .unwrap()
            .is_err()
    );
}

#[tokio::test]
async fn native_error_wakes_pending_read() {
    let (mut stream, control) = DataChannelStream::new(9, Some("127.0.0.1"), |_| {}).unwrap();
    control.start(false).unwrap();
    let read = tokio::spawn(async move { stream.read(&mut [0u8; 1]).await });
    control
        .remote_description(9, "invalid-sdp", "offer")
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(2), read)
            .await
            .unwrap()
            .unwrap()
            .is_err()
    );
}

#[tokio::test]
async fn dropping_stream_cancels_open_waiter_even_with_control_owner() {
    let (stream, control) = DataChannelStream::new(10, Some("127.0.0.1"), |_| {}).unwrap();
    drop(stream);
    assert!(
        tokio::time::timeout(Duration::from_secs(2), control.opened())
            .await
            .unwrap()
            .is_err()
    );
}

#[tokio::test]
async fn graceful_shutdown_has_bounded_drain_deadline() {
    let (mut stream, _control) = DataChannelStream::new(11, Some("127.0.0.1"), |_| {}).unwrap();
    stream.write_all(&vec![0; 1024 * 1024]).await.unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(7), stream.shutdown())
            .await
            .unwrap()
            .is_err()
    );
}

#[tokio::test]
async fn all_open_waiters_wake_on_close() {
    let (_stream, control) = DataChannelStream::new(12, Some("127.0.0.1"), |_| {}).unwrap();
    let first_control = control.clone();
    let first = tokio::spawn(async move { first_control.opened().await });
    tokio::time::sleep(Duration::from_millis(10)).await;
    let second_control = control.clone();
    let second = tokio::spawn(async move { second_control.opened().await });
    tokio::time::sleep(Duration::from_millis(10)).await;
    control.close(false);
    tokio::time::timeout(Duration::from_secs(2), async {
        assert!(first.await.unwrap().is_err());
        assert!(second.await.unwrap().is_err());
    })
    .await
    .unwrap();
}
