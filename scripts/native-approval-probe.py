"""Start one dedicated native approval probe; the Android test submits decline."""
import argparse
import json
import pathlib
import queue
import re
import subprocess
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument("--thread-id", required=True)
parser.add_argument("--marker", required=True)
args = parser.parse_args()
assert re.fullmatch(r"[0-9a-f-]{36}", args.thread_id)
assert re.fullmatch(r"MUMU_APPROVAL_[0-9a-f]{32}", args.marker)
root = pathlib.Path(__file__).resolve().parent.parent
workspace = root / ".local" / "probe-workspace"
proof = workspace / (args.marker + ".txt")
assert not proof.exists()
process = subprocess.Popen(
    [str(root / "target/debug/examples/cli_probe_transport.exe")],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    text=True, encoding="utf-8", bufsize=1,
)
messages = queue.Queue()
notes = []

def reader():
    for line in process.stdout:
        try:
            messages.put(json.loads(line))
        except ValueError:
            messages.put({"probeError": True})
    messages.put({"probeError": True})

threading.Thread(target=reader, daemon=True).start()

def receive(timeout=20):
    value = messages.get(timeout=timeout)
    if "probeError" in value:
        raise RuntimeError("Native transport unavailable")
    return value

def send(value):
    process.stdin.write(json.dumps(value) + "\n")
    process.stdin.flush()

sequence = 0

def call(method, params):
    global sequence
    sequence += 1
    rid = "approval-probe-" + str(sequence)
    send({"id": rid, "method": method, "params": params})
    end = time.monotonic() + 20
    while time.monotonic() < end:
        value = receive(max(.1, end - time.monotonic()))
        if value.get("id") == rid:
            if "error" in value:
                raise RuntimeError("Native RPC rejected")
            return value.get("result", {})
        notes.append(value)
    raise TimeoutError("Native RPC timeout")

report = {"passed": False, "threadId": args.thread_id, "phoneDecision": None, "cleanupDecline": False}
pending = None
completed = False
try:
    assert "probeMetadata" in receive()
    loaded = call("thread/loaded/list", {}).get("data", [])
    assert args.thread_id in loaded, "Dedicated native chat must be loaded before probing"
    thread = call("thread/read", {"threadId": args.thread_id, "includeTurns": False})["thread"]
    assert thread["id"] == args.thread_id
    assert pathlib.Path(thread["cwd"]).resolve() == workspace.resolve()
    # Attach this observer to the already loaded exact-ID native chat so its
    # approval/resolution events can be checked and cleaned up on test failure.
    # This keeps the existing daemon and does not change any permission policy.
    resumed = call("thread/resume", {"threadId": args.thread_id, "excludeTurns": True})["thread"]
    assert resumed["id"] == args.thread_id
    prompt = (
        "Approval-routing test. Use the shell tool once to run exactly: "
        f"Set-Content -LiteralPath '{proof}' -Value '{args.marker}'. "
        "If approval is denied, do not retry or use another tool; reply exactly MUMU_APPROVAL_DENIED. "
        "Do not read files or use network."
    )
    turn = call("turn/start", {
        "threadId": args.thread_id, "input": [{"type": "text", "text": prompt}],
        "approvalPolicy": "untrusted", "sandboxPolicy": {"type": "dangerFullAccess"},
    })["turn"]["id"]
    deadline = time.monotonic() + 160
    seen = False
    while time.monotonic() < deadline:
        event = notes.pop(0) if notes else receive(max(.1, deadline - time.monotonic()))
        params = event.get("params") or {}
        if params.get("threadId") != args.thread_id:
            continue
        if event.get("method") == "item/commandExecution/requestApproval" and args.marker in params.get("command", ""):
            pending = event["id"]
            seen = True
            advertised = params.get("availableDecisions")
            decisions = advertised if isinstance(advertised, list) else ["decline", "cancel"]
            report["phoneDecision"] = "decline" if "decline" in decisions else "cancel" if "cancel" in decisions else None
            assert report["phoneDecision"] is not None
        if event.get("method") == "turn/completed" and params.get("turn", {}).get("id") == turn:
            completed = True
            report["nativeTurnStatus"] = params["turn"]["status"]
            break
    report.update(approvalObserved=seen, nativeTurnCompleted=completed, proofFileAbsent=not proof.exists())
    expected = "interrupted" if report["phoneDecision"] == "cancel" else "completed"
    report["passed"] = seen and completed and report["nativeTurnStatus"] == expected and not proof.exists()
except Exception as error:
    report["errorType"] = type(error).__name__
finally:
    if pending is not None and not completed:
        try:
            send({"id": pending, "result": {"decision": report["phoneDecision"] or "cancel"}})
            report["cleanupDecline"] = True
        except (OSError, ValueError):
            pass
    try:
        process.stdin.close()
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=3)
    (root / ".local/approval-native-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))
raise SystemExit(0 if report["passed"] and not report["cleanupDecline"] else 1)
