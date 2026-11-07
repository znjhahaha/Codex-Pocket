#requires -Version 7.0
<#
.SYNOPSIS
Dry-run by default. Install or restore reversible wrappers around known npm
Codex entrypoints. Native configuration, credentials and package code are untouched.
.EXAMPLE
.\cli-install.ps1
.\cli-install.ps1 -Apply
.\cli-install.ps1 -Uninstall -Apply
#>
[CmdletBinding()]
param([string]$LauncherDirectory, [switch]$Apply, [switch]$Uninstall)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-BytesHash([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}
function Assert-PlainPath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing a reparse-point path: $cursor"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}
function Write-Atomic([string]$Path, [byte[]]$Bytes) {
    Assert-PlainPath $Path
    $temporary = $Path + '.codex2-' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        if ([IO.File]::Exists($Path)) { [IO.File]::Move($temporary, $Path, $true) }
        else { [IO.File]::Move($temporary, $Path) }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$names = @('codex', 'codex.cmd', 'codex.ps1')
$recognized = @{
    'codex' = '6EE176B43F96B2294C8E2265D599F829E161B2C2AD34CB2B8FBF5F836FD34B8C'
    'codex.cmd' = 'C54DB6755E710C39703F7C37512F9E35ED41042D8080558D2B84B8D2694323C3'
    'codex.ps1' = '0C149DB80ED0BF442C810146B0AD0163B74982FE4542D673F56C354D7B8229CB'
}
if (-not $LauncherDirectory) {
    $entry = Get-Command codex -ErrorAction Stop
    if ($entry.CommandType -notin @('Application', 'ExternalScript')) {
        throw 'codex resolves to an alias/function. Specify the verified npm -LauncherDirectory explicitly.'
    }
    $LauncherDirectory = Split-Path -Parent $entry.Source
}
$LauncherDirectory = [IO.Path]::GetFullPath($LauncherDirectory)
Assert-PlainPath $LauncherDirectory
$modulePath = Join-Path $LauncherDirectory 'node_modules\@openai\codex\bin\codex.js'
$packagePath = Join-Path $LauncherDirectory 'node_modules\@openai\codex\package.json'
Assert-PlainPath $modulePath
Assert-PlainPath $packagePath
if (-not [IO.File]::Exists($modulePath)) { throw 'The original official npm CLI module is missing.' }
$package = Get-Content -LiteralPath $packagePath -Raw | ConvertFrom-Json
if ($package.name -ne '@openai/codex') { throw 'Expected the official npm @openai/codex package.' }
$stateDir = Join-Path $LauncherDirectory '.codex2-cli'
Assert-PlainPath $stateDir
$manifestPath = Join-Path $stateDir 'manifest.json'
$runtimePath = Join-Path $stateDir 'runtime.json'
$wrapperPath = Join-Path $stateDir 'cli-wrapper.mjs'
$sourceWrapper = Join-Path $PSScriptRoot 'cli-wrapper.mjs'
Assert-PlainPath $sourceWrapper
if (-not [IO.File]::Exists($sourceWrapper)) { throw 'The sibling CLI wrapper helper is missing.' }
$original = @{}
$installed = @{}
$current = @{}
$manifest = $null
$lock = $null
$locked = $false
$createdState = $false
try {
    if ($Apply) {
        $lockName = 'Local\Codex2CliInstall_' + (Get-BytesHash $utf8.GetBytes($LauncherDirectory.ToLowerInvariant()))
        $lock = [Threading.Mutex]::new($false, $lockName)
        try { $locked = $lock.WaitOne(0) }
        catch [Threading.AbandonedMutexException] { $locked = $true }
        if (-not $locked) { throw 'Another installer owns this npm entry directory.' }
    }
    foreach ($name in $names) {
        $target = Join-Path $LauncherDirectory $name
        Assert-PlainPath $target
        if (-not [IO.File]::Exists($target)) { throw "Missing standard npm launcher: $name" }
        $current[$name] = [IO.File]::ReadAllBytes($target)
    }
    if ([IO.File]::Exists($manifestPath)) {
        Assert-PlainPath $manifestPath
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ($manifest.schema -ne 1 -or $manifest.launcherDirectory -ne $LauncherDirectory -or $manifest.originalModule -ne $modulePath) {
            throw 'Installation manifest does not match this npm directory.'
        }
        foreach ($name in $names) {
            $record = $manifest.entries.PSObject.Properties[$name].Value
            $backup = Join-Path $stateDir ($name + '.original')
            Assert-PlainPath $backup
            $original[$name] = [IO.File]::ReadAllBytes($backup)
            if ((Get-BytesHash $original[$name]) -ne $record.originalHash -or $record.originalHash -ne $recognized[$name]) {
                throw "Backup integrity check failed: $name"
            }
            $currentHash = Get-BytesHash $current[$name]
            if ($currentHash -ne $record.originalHash -and $currentHash -ne $record.installedHash) {
                throw "Launcher changed outside this installer; refusing to overwrite: $name"
            }
        }
    } else {
        if (Test-Path -LiteralPath $stateDir) { throw 'An unrecognized .codex2-cli directory already exists.' }
        if ($Uninstall) { throw 'No managed CLI wrapper installation exists.' }
        foreach ($name in $names) {
            if ((Get-BytesHash $current[$name]) -ne $recognized[$name]) {
                throw "Unrecognized npm launcher bytes: $name. Review the current npm template before installing."
            }
            $original[$name] = $current[$name]
        }
    }
    foreach ($name in $names) {
        $text = $utf8.GetString($original[$name])
        if ($name -eq 'codex.cmd') {
            $oldPath = '%dp0%\node_modules\@openai\codex\bin\codex.js'
            $newPath = '%dp0%\.codex2-cli\cli-wrapper.mjs'
        } else {
            $oldPath = '$basedir/node_modules/@openai/codex/bin/codex.js'
            $newPath = '$basedir/.codex2-cli/cli-wrapper.mjs'
        }
        if (-not $text.Contains($oldPath)) { throw "Expected fixed module path absent: $name" }
        $installed[$name] = $utf8.GetBytes($text.Replace($oldPath, $newPath))
        if ($null -ne $manifest -and (Get-BytesHash $installed[$name]) -ne $manifest.entries.PSObject.Properties[$name].Value.installedHash) {
            throw 'Installed wrapper format differs; restore it using its original installer first.'
        }
    }
    $wrapperBytes = [IO.File]::ReadAllBytes($sourceWrapper)
    $runtimeBytes = $utf8.GetBytes(([ordered]@{schema=1;originalModule=$modulePath} | ConvertTo-Json))
    if ($null -ne $manifest) {
        foreach ($check in @(@{Path=$wrapperPath;Hash=$manifest.wrapperHash},@{Path=$runtimePath;Hash=$manifest.runtimeHash})) {
            Assert-PlainPath $check.Path
            if ((Get-BytesHash ([IO.File]::ReadAllBytes($check.Path))) -ne $check.Hash) { throw 'Installed helper integrity check failed.' }
        }
        if (-not $Uninstall -and ((Get-BytesHash $wrapperBytes) -ne $manifest.wrapperHash -or (Get-BytesHash $runtimeBytes) -ne $manifest.runtimeHash)) {
            throw 'Wrapper source changed; uninstall with the existing manifest before installing the new source.'
        }
    }
    $desired = if ($Uninstall) { $original } else { $installed }
    $changed = @($names | Where-Object { (Get-BytesHash $current[$_]) -ne (Get-BytesHash $desired[$_]) })
    $plan = [ordered]@{
        action = $(if ($Uninstall) {'restore'} else {'install'})
        applied = [bool]$Apply
        launcherDirectory = $LauncherDirectory
        cliVersion = $package.version
        entriesToChange = $changed
        stateDirectory = $stateDir
        nativeConfigChanged = $false
        daemonLifecycleAtInstall = 'none'
    }
    if (-not $Apply) { $plan | ConvertTo-Json -Depth 5; return }
    if ($null -eq $manifest) {
        [void][IO.Directory]::CreateDirectory($stateDir)
        $createdState = $true
        foreach ($name in $names) { Write-Atomic (Join-Path $stateDir ($name+'.original')) $original[$name] }
        Write-Atomic $wrapperPath $wrapperBytes
        Write-Atomic $runtimePath $runtimeBytes
        $entries = [ordered]@{}
        foreach ($name in $names) { $entries[$name] = @{originalHash=(Get-BytesHash $original[$name]);installedHash=(Get-BytesHash $installed[$name])} }
        $manifest = [ordered]@{
            schema=1;launcherDirectory=$LauncherDirectory;originalModule=$modulePath
            entries=$entries;wrapperHash=(Get-BytesHash $wrapperBytes);runtimeHash=(Get-BytesHash $runtimeBytes)
        }
        Write-Atomic $manifestPath $utf8.GetBytes(($manifest | ConvertTo-Json -Depth 6))
    }
    $written = [Collections.Generic.List[string]]::new()
    try {
        foreach ($name in $changed) {
            $target = Join-Path $LauncherDirectory $name
            # Recheck immediately before replacement; never overwrite concurrent updates.
            if ((Get-BytesHash ([IO.File]::ReadAllBytes($target))) -ne (Get-BytesHash $current[$name])) {
                throw "Launcher changed during installation: $name"
            }
            Write-Atomic $target $desired[$name]
            $written.Add($name)
        }
    } catch {
        $failure = $_
        foreach ($name in $written) {
            $target = Join-Path $LauncherDirectory $name
            if ((Get-BytesHash ([IO.File]::ReadAllBytes($target))) -eq (Get-BytesHash $desired[$name])) {
                Write-Atomic $target $current[$name]
            }
        }
        throw $failure
    }
    # Keep exact backups and manifest after restore, allowing audit and reapply.
    $plan | ConvertTo-Json -Depth 5
} catch {
    # Once published, retain the verified manifest/backups even if rollback
    # encounters another failure. Remaining wrappers must retain their helper,
    # and a later restore/apply can reconcile original/installed launcher bytes.
    if ($createdState -and -not [IO.File]::Exists($manifestPath)) {
        foreach ($path in @($manifestPath, $runtimePath, $wrapperPath) + @($names | ForEach-Object { Join-Path $stateDir ($_ + '.original') })) {
            if ([IO.File]::Exists($path)) { [IO.File]::Delete($path) }
        }
        if ([IO.Directory]::Exists($stateDir) -and -not (Get-ChildItem -LiteralPath $stateDir -Force)) {
            [IO.Directory]::Delete($stateDir)
        }
    }
    throw
} finally {
    if ($locked) { $lock.ReleaseMutex() }
    if ($null -ne $lock) { $lock.Dispose() }
}
