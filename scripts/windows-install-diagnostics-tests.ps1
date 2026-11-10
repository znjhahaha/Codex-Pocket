#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
. (Join-Path $PSScriptRoot 'windows-install.ps1')
$fixture=Join-Path $PSScriptRoot ('..\.local\install-diagnostics-'+[guid]::NewGuid().ToString('N'))
$path=Start-PocketInstallDiagnostics ([IO.Path]::GetFullPath($fixture))
try {
    Invoke-PocketInstallStep 'integration_read' 'snapshot' {
        Invoke-PocketInstallStep 'startup_register' 'ITaskFolder.RegisterTask' {
            throw [Runtime.InteropServices.COMException]::new('fixture-secret-not-for-report',-2147467259)
        }
    }
    throw 'Expected fixture failure'
} catch {
    if ($_.Exception.Data['PocketCall'] -ne 'ITaskFolder.RegisterTask' -or $_.Exception.Data['PocketHResult'] -ne '0x80004005') { throw 'Lost the exact inner COM call or HRESULT' }
}
$record=Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
if($record.events.Count -ne 3 -or $record.events[1].call -ne 'ITaskFolder.RegisterTask' -or $record.events[1].hresult -ne '0x80004005'){throw 'Persistent diagnostic events incomplete'}
if(([IO.File]::ReadAllText($path)).Contains('fixture-secret')){throw 'Raw error data leaked'}
Write-PocketInstallEvent @{stage='rollback';call='fixture restore';state='completed'}
$record=Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
if($record.events[-1].stage -ne 'rollback'){throw 'Recovery result was not retained'}
Write-Output ('PASS nested COM failure attribution, persistent journal, redaction, and recovery record: '+$path)
foreach($case in @(
    @{hr=-2147024894;missing=$true},
    @{hr=-2147024893;missing=$true},
    @{hr=-2147024891;missing=$false},
    @{hr=-2147467259;missing=$false},
    @{hr=-2147418110;missing=$false}
)) {
    try { Invoke-PocketInstallStep 'startup_read' 'ITaskFolder.GetTask' {throw [Runtime.InteropServices.COMException]::new('fixture-secret-absence',$case.hr)} }
    catch {
        if ((Test-PocketTaskMissing $_) -ne $case.missing) { throw 'Unknown task error was classified as absence' }
        $pending=Get-PocketOptionalFailure $_ 'integration_read' 'startup_snapshot_unknown' 'snapshot'
        if($pending.stage -ne 'startup_read' -or $pending.call -ne 'ITaskFolder.GetTask' -or $pending.code -ne 'startup_snapshot_unknown') { throw 'Partial status lost the inner stage, call or code' }
        Write-PocketInstallEvent ($pending+@{recovery='retry_startup'})
    }
}
try {
    Invoke-PocketInstallStep 'shortcut' 'IPersistFile.Save' {throw [Runtime.InteropServices.COMException]::new('fixture-secret-link',-2147467259)}
} catch {
    $pending=Get-PocketOptionalFailure $_ 'integration' 'shortcut_integration_failed' 'outer'
    if($pending.stage -ne 'shortcut' -or $pending.hresult -ne '0x80004005') {throw 'Shortcut failure was misattributed'}
    Write-PocketInstallEvent ($pending+@{recovery='retry_shortcut';slot=0})
}
$record=Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
if($record.events[-1].code -ne 'shortcut_integration_failed' -or $record.events[-1].state -ne 'pending'){throw 'Partial success diagnostics were not persisted'}
if(([IO.File]::ReadAllText($path)).Contains('fixture-secret')){throw 'Partial failure leaked raw error data'}
Write-Output 'PASS exact task absence HRESULTs, optional pending codes, shortcut attribution and redaction'
