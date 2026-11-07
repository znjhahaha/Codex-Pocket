#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f-]{36}$')][string]$ThreadId,
    [Parameter(Mandatory)][string]$AgentPath,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$Serial = '127.0.0.1:16416',
    [ValidateRange(30,86400)][int]$DurationSeconds = 86400,
    [Parameter(Mandatory)][uri]$HealthUrl
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$AgentPath = [IO.Path]::GetFullPath($AgentPath)
Assert-PocketPlainPath $AgentPath
if (-not [IO.File]::Exists($AgentPath)) { throw 'Verified installed helper is required.' }
if ($HealthUrl.Scheme -ne 'https' -or $HealthUrl.UserInfo -or $HealthUrl.Query) { throw 'Use a credential-free HTTPS health endpoint.' }
$OutputDirectory = Get-PocketDirectoryPath $OutputDirectory
Assert-PocketPlainPath $OutputDirectory
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory; prior soak evidence is preserved.' }
[void][IO.Directory]::CreateDirectory($OutputDirectory)
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$stdout = Join-Path $OutputDirectory 'android.log'
$stderr = Join-Path $OutputDirectory 'adb-error.log'
$arguments = @('-s',$Serial,'shell','am','instrument','-w','-e','class','dev.codexpocket.app.LiveSoakTest','-e','liveSoak','true','-e','threadId',$ThreadId,'-e','durationSeconds',[string]$DurationSeconds,'dev.codexpocket.app.test/androidx.test.runner.AndroidJUnitRunner')
$started = [DateTimeOffset]::UtcNow
$probe = Start-Process -FilePath $adb -ArgumentList (($arguments | ForEach-Object { ConvertTo-PocketArgument $_ }) -join ' ') -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
Write-PocketJson (Join-Path $OutputDirectory 'run.json') ([ordered]@{
    startedAt=$started.ToString('o');targetSeconds=$DurationSeconds;threadId=$ThreadId
    adbPid=$probe.Id;adbStartedAt=$probe.StartTime.ToUniversalTime().ToString('o')
    helperPath=$AgentPath;helperSha256=(Get-PocketFileHash $AgentPath)
    mode='Read-only Android instrumentation; history equality, periodic reconnect, Android PSS, Windows helper memory, HTTPS health'
})
$healthFailures = 0
$samples = 0
try {
    while (-not $probe.HasExited) {
        $helpers = @(Get-Process pocket-agent -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $AgentPath })
        $healthy = $false
        try { $healthy = (Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 10).status -eq 'ok' } catch { $healthy = $false }
        if (-not $healthy) { $healthFailures++ }
        $samples++
        [ordered]@{at=[DateTimeOffset]::UtcNow.ToString('o');helperCount=$helpers.Count;helperWorkingSetBytes=($helpers | Measure-Object WorkingSet64 -Sum).Sum;httpsHealthy=$healthy} |
            ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $OutputDirectory 'host-samples.jsonl') -Encoding utf8
        if (([DateTimeOffset]::UtcNow - $started).TotalSeconds -gt $DurationSeconds + 180) {
            # Only the adb process created above is eligible for timeout cleanup.
            $probe.Kill(); $probe.WaitForExit(); throw 'Instrumentation exceeded the configured duration; inspect retained evidence.'
        }
        Start-Sleep -Seconds 30
        $probe.Refresh()
    }
    $probe.WaitForExit()
    $text = [IO.File]::ReadAllText($stdout)
    $completed = @($text -split "`n" | Where-Object { $_ -match '^INSTRUMENTATION_STATUS: pocketSoak=' } | ForEach-Object {
        $_.Substring($_.IndexOf('=') + 1) | ConvertFrom-Json
    } | Where-Object { $_.status -eq 'completed' })
    $passed = $probe.ExitCode -eq 0 -and $text -match 'OK \(1 test\)' -and $completed.Count -eq 1 -and $completed[0].elapsedSeconds -ge $DurationSeconds
    Write-PocketJson (Join-Path $OutputDirectory 'result.json') ([ordered]@{
        completedAt=[DateTimeOffset]::UtcNow.ToString('o');instrumentationPassed=$passed;hostSamples=$samples
        httpsHealthFailures=$healthFailures;android=if($completed.Count){$completed[-1]}else{$null}
    })
    if (-not $passed) { throw 'Soak did not complete successfully; evidence retained.' }
} finally { $probe.Dispose() }
