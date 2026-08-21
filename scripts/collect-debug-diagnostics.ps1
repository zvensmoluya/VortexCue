[CmdletBinding()]
param(
    [string]$Serial,
    [string]$TaskId,
    [string]$OutputRoot = [System.IO.Path]::GetTempPath(),
    [switch]$IncludeRawResponses,
    [string]$Package = "io.github.zvensmoluya.vortexcue.prototype"
)

$ErrorActionPreference = "Stop"
$Adb = (Get-Command adb -ErrorAction Stop).Source
$NewLine = [Environment]::NewLine
$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

function Invoke-AdbText {
    param(
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & $Adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ($AllowFailure) {
            return ""
        }
        throw "adb failed: $($output -join $NewLine)"
    }
    return ($output -join $NewLine).TrimEnd()
}

function Protect-DiagnosticText {
    param([string]$Value)

    $redacted = $Value -replace '(?i)(authorization\s*[:=]\s*)([^\s,;]+)', '$1[redacted]'
    $redacted = $redacted -replace '(?i)(bearer\s+)[A-Za-z0-9._~+/-]+', '$1[redacted]'
    $redacted = $redacted -replace '(?i)((api[_ -]?key|token|secret)\s*[:=]\s*)([^\s,;]+)', '$1[redacted]'
    $redacted = $redacted -replace '(?i)content://[^\s,)\]}]+', '[content-uri-redacted]'
    $redacted = $redacted -replace '(?i)file://[^\s,)\]}]+', '[file-uri-redacted]'
    $redacted = $redacted -replace '(?i)/(storage|sdcard|data/user)/[^\s,)\]}]+', '/[path-redacted]'
    return $redacted
}

function Save-DiagnosticText {
    param(
        [string]$Path,
        [string]$Value
    )

    $safe = Protect-DiagnosticText $Value
    [System.IO.File]::WriteAllText($Path, $safe + $NewLine, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-DeviceSqlite {
    param(
        [string]$Sql,
        [switch]$List
    )

    if ($Sql.Contains('"')) {
        throw "Diagnostic SQL must not contain double quotes"
    }
    $format = if ($List) { "-list" } else { "-header -csv" }
    $remote = "run-as $Package sqlite3 -readonly $format databases/vortexcue.db `"$Sql`""
    return Invoke-AdbText @("-s", $Serial, "shell", $remote)
}

function Save-AdbBinary {
    param(
        [string]$RemotePath,
        [string]$LocalPath
    )

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Adb
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @("-s", $Serial, "exec-out", "run-as", $Package, "cat", $RemotePath)) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $file = [System.IO.File]::Open($LocalPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    try {
        $process.StandardOutput.BaseStream.CopyTo($file)
    } finally {
        $file.Dispose()
    }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "adb raw-response copy failed: $errorText"
    }
}

$devices = Invoke-AdbText @("devices", "-l")
$connected = @($devices -split "`r?`n" | Where-Object { $_ -match '^\S+\s+device\b' })
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($connected.Count -ne 1) {
        throw "Expected exactly one connected device; pass -Serial when multiple devices are attached"
    }
    $Serial = ($connected[0] -split '\s+')[0]
} elseif (-not ($connected | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device\b" })) {
    throw "Device is not connected: $Serial"
}

if ($Package -notmatch '^[A-Za-z0-9._]+$') {
    throw "Unsafe package name"
}
Invoke-AdbText @("-s", $Serial, "shell", "run-as", $Package, "true") | Out-Null
$remoteSqlite = Invoke-AdbText @("-s", $Serial, "shell", "which", "sqlite3")
if ([string]::IsNullOrWhiteSpace($remoteSqlite)) {
    throw "The connected debug device does not provide sqlite3"
}
$schemaVersion = Invoke-DeviceSqlite -List "PRAGMA user_version;"
if ([int]$schemaVersion -lt 8) {
    throw "The installed debug APK uses database schema v$schemaVersion; install the latest v8 build first"
}

if ([string]::IsNullOrWhiteSpace($TaskId)) {
    $TaskId = Invoke-DeviceSqlite -List "SELECT task_id FROM generation_task ORDER BY created_at_ms DESC LIMIT 1;"
}
if (-not [string]::IsNullOrWhiteSpace($TaskId) -and $TaskId -notmatch '^[A-Za-z0-9._:-]+$') {
    throw "Unsafe task id"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportDirectory = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) "VortexCue-diagnostics-$stamp"
[void](New-Item -ItemType Directory -Path $reportDirectory)

$packageDump = Invoke-AdbText @("-s", $Serial, "shell", "dumpsys", "package", $Package)
$packageSummary = $packageDump -split "`r?`n" | Select-String -Pattern 'versionCode=|versionName=|minSdk=|targetSdk=|DEBUGGABLE|lastUpdateTime='
$gitCommit = "unavailable"
$gitDirty = "unavailable"
if (Get-Command git -ErrorAction SilentlyContinue) {
    $gitCommit = ((& git -C $RepositoryRoot rev-parse HEAD 2>$null) | Select-Object -First 1)
    $gitDirty = if ((& git -C $RepositoryRoot status --porcelain 2>$null | Select-Object -First 1)) { "true" } else { "false" }
}
$debugApk = Join-Path $RepositoryRoot "app\build\outputs\apk\debug\app-debug.apk"
$debugApkSha256 = if (Test-Path -LiteralPath $debugApk) { (Get-FileHash -LiteralPath $debugApk -Algorithm SHA256).Hash } else { "unavailable" }
$deviceSummary = @(
    "serial=$Serial"
    "manufacturer=$(Invoke-AdbText @('-s', $Serial, 'shell', 'getprop', 'ro.product.manufacturer'))"
    "model=$(Invoke-AdbText @('-s', $Serial, 'shell', 'getprop', 'ro.product.model'))"
    "sdk=$(Invoke-AdbText @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.sdk'))"
    "release=$(Invoke-AdbText @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.release'))"
    "package=$Package"
    "taskId=$TaskId"
    "gitCommit=$gitCommit"
    "gitDirty=$gitDirty"
    "localDebugApkSha256=$debugApkSha256"
    "includeRawResponses=$($IncludeRawResponses.IsPresent)"
    "collectedAt=$([DateTimeOffset]::Now.ToString('o'))"
    ""
    ($packageSummary | Out-String).TrimEnd()
) -join $NewLine
Save-DiagnosticText (Join-Path $reportDirectory "environment.txt") $deviceSummary

$taskPredicate = if ([string]::IsNullOrWhiteSpace($TaskId)) { "1 = 0" } else { "task_id = '$TaskId'" }
$attemptPredicate = if ([string]::IsNullOrWhiteSpace($TaskId)) { "1 = 0" } else { "task_id = '$TaskId'" }
$attemptSubquery = "SELECT attempt_id FROM generation_attempt WHERE $attemptPredicate"

$queries = [ordered]@{
    "task.csv" = "SELECT task_id,content_id,kind,state,stage,queue_position,progress,waiting_for_network,metered_network_allowed,network_wait_reason,paused,needs_attention,scheme_blocked,current_attempt_id,source_result_id,error_category,created_at_ms,updated_at_ms FROM generation_task WHERE $taskPredicate;"
    "task_snapshot.csv" = "SELECT task_id,input_kind,source_track_id,embedded_track_id,audio_track_id,input_source_result_id,source_language,target_language,presentation_mode,allow_metered_network,allow_asr_fallback,submitted_at_ms FROM generation_task_snapshot WHERE $taskPredicate;"
    "attempts.csv" = "SELECT attempt_id,task_id,ordinal,scheme_id,asr_profile,translation_protocol,translation_model,policy_id,policy_version,request_schema_version,asr_window_ms,asr_overlap_ms,asr_max_encoded_bytes,source_result_id,state,created_at_ms,finished_at_ms FROM generation_attempt WHERE $attemptPredicate ORDER BY ordinal;"
    "request_attempts.csv" = "SELECT request_attempt_id,attempt_id,sequence,stage,provider,provider_request_id,work_unit_kind,work_unit_id,operation_ordinal,retry_ordinal,request_bytes,response_bytes,http_status,input_tokens,output_tokens,total_tokens,cost_usd,raw_response_file_name,error_category,started_at_ms,finished_at_ms,duration_ms,retain_until_ms FROM sync_text_request_attempt WHERE attempt_id IN ($attemptSubquery) ORDER BY started_at_ms,sequence;"
    "asr_windows.csv" = "SELECT window_id,attempt_id,ordinal,owned_start_ms,owned_end_ms,upload_start_ms,upload_end_ms,cut_reason,planner_version,parent_window_id,state,encoded_bytes,transcript_artifact_id,cue_count,detected_language,input_tokens,output_tokens,total_tokens,cost_usd FROM asr_window WHERE attempt_id IN ($attemptSubquery) ORDER BY ordinal;"
    "translation_batches.csv" = "SELECT batch_id,attempt_id,ordinal,first_cue_index,last_cue_index,parent_batch_id,state,format_recovery_count,repair_count,result_artifact_id,cue_count,input_tokens,output_tokens,total_tokens,cost_usd FROM translation_batch WHERE attempt_id IN ($attemptSubquery) ORDER BY ordinal;"
    "results.csv" = "SELECT result_id,slot_id,content_id,task_id,attempt_id,source_language,target_language,presentation_mode,source_revision_id,target_revision_id,cue_count,completeness,covered_end_ms,completed_window_count,total_window_count,scheme_id,model,policy_id,policy_version,input_tokens,output_tokens,total_tokens,cost_usd,created_at_ms FROM generated_text_result WHERE task_id = '$TaskId' ORDER BY created_at_ms;"
    "events.csv" = "SELECT event_id,process_session_id,event_type,task_id,attempt_id,work_unit_kind,work_unit_id,stage,outcome,reason,value_ms,value_bytes,created_at_ms,elapsed_realtime_ms FROM sync_text_diagnostic_event WHERE task_id = '$TaskId' OR task_id IS NULL ORDER BY created_at_ms,event_id;"
    "artifact_summary.csv" = "SELECT kind,count(*) AS artifact_count,sum(byte_size) AS total_bytes,sum(cue_count) AS cue_count FROM text_artifact GROUP BY kind ORDER BY kind;"
    "lease.csv" = "SELECT lease_key,expires_at_ms,generation FROM sync_text_runner_lease;"
}

foreach ($entry in $queries.GetEnumerator()) {
    Save-DiagnosticText (Join-Path $reportDirectory $entry.Key) (Invoke-DeviceSqlite $entry.Value)
}

$appProcessId = Invoke-AdbText -AllowFailure -Arguments @("-s", $Serial, "shell", "pidof", $Package)
if (-not [string]::IsNullOrWhiteSpace($appProcessId)) {
    Save-DiagnosticText (Join-Path $reportDirectory "logcat.txt") (Invoke-AdbText @("-s", $Serial, "logcat", "-d", "--pid=$appProcessId", "-v", "threadtime", "-t", "12000"))
    Save-DiagnosticText (Join-Path $reportDirectory "meminfo.txt") (Invoke-AdbText @("-s", $Serial, "shell", "dumpsys", "meminfo", $Package))
}

$jobDump = Invoke-AdbText @("-s", $Serial, "shell", "dumpsys", "jobscheduler", $Package)
$jobSummary = $jobDump -split "`r?`n" | Select-String -Pattern "$([regex]::Escape($Package))|Pending queue:|Job history:|Currently running" -Context 3,12
Save-DiagnosticText (Join-Path $reportDirectory "jobscheduler.txt") ($jobSummary | Out-String)

$rawFiles = Invoke-DeviceSqlite -List "SELECT raw_response_file_name FROM sync_text_request_attempt WHERE attempt_id IN ($attemptSubquery) AND raw_response_file_name IS NOT NULL ORDER BY sequence;"
Save-DiagnosticText (Join-Path $reportDirectory "raw-response-index.txt") $rawFiles
if ($IncludeRawResponses -and -not [string]::IsNullOrWhiteSpace($rawFiles)) {
    $rawDirectory = Join-Path $reportDirectory "raw-responses"
    [void](New-Item -ItemType Directory -Path $rawDirectory)
    foreach ($fileName in ($rawFiles -split "`r?`n")) {
        if ($fileName -notmatch '^[A-Za-z0-9_.-]{1,180}$') {
            throw "Unsafe raw-response file name"
        }
        Save-AdbBinary "no_backup/sync_text/raw_responses/$fileName" (Join-Path $rawDirectory $fileName)
    }
}

$readme = @"
VortexCue debug diagnostics

This bundle is local and was collected from a debuggable APK through adb run-as.
Credential tables, Android Keystore files, Authorization headers, request bodies, media paths,
and subtitle text are excluded from the default report. Raw provider responses are included only
when IncludeRawResponses is true and may contain transcribed or translated content.
"@
Save-DiagnosticText (Join-Path $reportDirectory "README.txt") $readme

$zipPath = "$reportDirectory.zip"
Compress-Archive -Path (Join-Path $reportDirectory "*") -DestinationPath $zipPath
Write-Output $zipPath
