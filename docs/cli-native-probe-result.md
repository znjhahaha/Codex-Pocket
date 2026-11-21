# Native CLI integration probe

```json
{
  "cliVersion": "codex-cli 0.169.0",
  "nativeConfigUnchanged": true,
  "transport": "WebSocket over AF_UNIX",
  "workspace": "D:\\codex2\\.local\\probe-workspace",
  "checks": {
    "nativeThreadLoadedWhileTuiAlive": true,
    "liveJoinPreservedNativeId": true,
    "liveFollowupStreamed": true,
    "liveFollowupCompleted": true,
    "liveFollowupVisibleInTui": true,
    "interruptWhileOriginalTuiAlive": true,
    "approvalRoundtripWhileOriginalTuiAlive": true,
    "ordinaryResumeFollowupStreamed": true,
    "ordinaryResumeFollowupCompleted": true,
    "ordinaryResumeFollowupVisibleInTui": true,
    "ordinaryResumePreservedNativeId": true
  },
  "ownedProcesses": [
    {
      "kind": "tui-launcher",
      "pid": 130300
    },
    {
      "kind": "rust-transport",
      "pid": 151152
    },
    {
      "kind": "tui-launcher",
      "pid": 105936
    }
  ],
  "daemonBefore": {
    "status": "running",
    "backend": "pid",
    "cliVersion": "0.169.0",
    "managedCodexVersion": "0.170.0",
    "appServerVersion": "0.170.0",
    "socketPath": "C:\\Users\\82206\\.codex\\app-server-control\\app-server-control.sock"
  },
  "discovery": {
    "codexHome": "C:\\Users\\82206\\.codex",
    "initializeResult": {
      "codexHome": "C:\\Users\\82206\\.codex",
      "platformFamily": "windows",
      "platformOs": "windows",
      "userAgent": "Codex Desktop/0.170.0 (Windows 10.0.26300; x86_64) dumb (cli-adapter-probe; 0.1.0)"
    },
    "serverVersion": "0.170.0",
    "socketPath": "C:\\Users\\82206\\.codex\\app-server-control\\app-server-control.sock",
    "sourceVersion": "0.164.0"
  },
  "server": {
    "userAgent": "Codex Desktop/0.170.0 (Windows 10.0.26300; x86_64) dumb (cli-adapter-probe; 0.1.0)",
    "codexHome": "C:\\Users\\82206\\.codex",
    "platformFamily": "windows",
    "platformOs": "windows"
  },
  "threadId": "01a1f703-50d8-7441-8eb8-2c88c5fc1451",
  "nativeModel": "gpt-6.2-sol",
  "nativeProviderMatchesCliThread": true,
  "approval": {
    "decision": "decline",
    "requestIdType": "int",
    "originalTuiAlive": true,
    "probeMarkerInCommand": true,
    "cwdMatchesProbe": true
  },
  "updatePromptsSkipped": [
    {
      "pid": 105936,
      "skipped": true
    }
  ],
  "passed": true
}
```
