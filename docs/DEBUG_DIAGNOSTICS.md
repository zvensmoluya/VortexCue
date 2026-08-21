# Debug diagnostics

The debug build keeps a seven-day, local-only diagnostic trail for sync-text work. It records task and scheduler lifecycle events, ASR/translation work-unit outcomes, publication milestones, and redacted request metadata. It never records API keys, authorization headers, request prompts, media paths, or subtitle text in the structured event table.

Request attempts include their ASR window or translation batch ID, semantic operation ordinal, network retry ordinal, wall-clock boundaries, monotonic duration, provider response ID when available, and sanitized failure phase/root-cause class. Translation validation events record issue-code counts or deterministic ID normalization counts without storing cue IDs or subtitle text. Raw provider responses remain in `noBackupFilesDir/sync_text/raw_responses` for seven days, as before, and may contain transcript or translation content.

Collect the latest task from the only attached device:

```powershell
.\scripts\collect-debug-diagnostics.ps1
```

Select a device or task explicitly:

```powershell
.\scripts\collect-debug-diagnostics.ps1 -Serial 10AF8Z0B9G006D3 -TaskId <task-id>
```

Raw responses are excluded by default. Include them only when content inspection is required:

```powershell
.\scripts\collect-debug-diagnostics.ps1 -TaskId <task-id> -IncludeRawResponses
```

The script requires a debuggable APK, `adb`, and `sqlite3` on the connected device. It writes a timestamped ZIP under the system temporary directory unless `-OutputRoot` is supplied. The default bundle contains only allowlisted Room columns, a credential-scrubbed process log, the package's JobScheduler history, memory state, and a raw-response file index.
