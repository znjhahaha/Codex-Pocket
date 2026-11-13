#requires -Version 5.1
[CmdletBinding()]
param([string]$AgentPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $AgentPath) { $AgentPath = Join-Path $PSScriptRoot '..\dist\delivery-final\windows\pocket-agent.exe' }
. (Join-Path $PSScriptRoot 'windows-install.ps1')
function Enter-PocketInstallLifecycle { return @{processesStopped=0} }
function Complete-PocketInstallLifecycle { return @{processesStarted=0;runtimeVerification='verified'} }
$AgentPath = [IO.Path]::GetFullPath($AgentPath)
if (-not [IO.File]::Exists($AgentPath)) { throw 'The final Release helper is required; this test never builds it.' }
$testRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('pocket-identity-' + [guid]::NewGuid().ToString('N'))))
[void][IO.Directory]::CreateDirectory($testRoot)
$script:passed = 0
function Assert($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Test([string]$Name,[scriptblock]$Action) { & $Action; $script:passed++; Write-Output ('PASS ' + $Name) }
function Expect-Failure([scriptblock]$Action,[string]$Pattern) {
    try { & $Action | Out-Null } catch { Assert ($_.Exception.Message -match $Pattern) ('Unexpected failure (details omitted), expected: ' + $Pattern); return }
    throw ('Expected failure: ' + $Pattern)
}
function Invoke-LocalHelper([string]$Command,[string]$State) {
    Assert ($Command -in @('init','pair')) 'Only local init/pair commands are allowed'
    Assert (([IO.Path]::GetFullPath($State)).StartsWith($testRoot + '\',[StringComparison]::OrdinalIgnoreCase)) 'State escaped the disposable fixture'
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $AgentPath
    $psi.Arguments = $Command + ' --state ' + (ConvertTo-PocketArgument $State)
    if ($Command -eq 'init') { $psi.Arguments += ' --relay wss://127.0.0.1:9/pocket' }
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $testRoot
    $psi.EnvironmentVariables['CODEX_HOME'] = Join-Path $testRoot 'unused-codex-home'
    $psi.EnvironmentVariables['LOCALAPPDATA'] = Join-Path $testRoot 'unused-local-appdata'
    $process = [Diagnostics.Process]::Start($psi)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) { $process.Kill(); [void]$process.WaitForExit(3000); throw 'Disposable local helper timed out' }
        # Capture and discard all helper output, including invitation paths/content.
        [void]$stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        return [pscustomobject]@{exitCode=$process.ExitCode;insecureIdentity=($errorText -match 'identity storage is not private')}
    } finally { $process.Dispose() }
}
function Identity-Snapshot([string]$State) {
    $result = @{}
    foreach ($name in @('identity','identity\certificate.der','identity\private-key.der','identity\identity.lock')) {
        $path = Join-Path $State $name
        $acl = Get-Acl -LiteralPath $path
        $raw = [Security.AccessControl.RawSecurityDescriptor]::new($acl.GetSecurityDescriptorBinaryForm(),0)
        $daclBytes = [byte[]]::new($raw.DiscretionaryAcl.BinaryLength)
        $raw.DiscretionaryAcl.GetBinaryForm($daclBytes,0)
        $policy = $raw.Owner.Value + ':' + $acl.AreAccessRulesProtected + ':' + [Convert]::ToBase64String($daclBytes)
        $result[$name] = @{policy=$policy;sddl=$acl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]'Owner,Access');hash=$(if($name -ne 'identity'){Get-PocketFileHash $path}else{$null})}
    }
    return $result
}
function Assert-SameIdentity($Expected,[string]$State,[switch]$ExactDescriptor) {
    $actual = Identity-Snapshot $State
    foreach ($name in $Expected.Keys) {
        Assert ($actual[$name].hash -eq $Expected[$name].hash) 'Identity bytes changed (values omitted)'
        # Windows Set-Acl can mark SE_DACL_AUTO_INHERITED while every ACE still has
        # flags=0. Compare owner, protection and exact DACL bytes used by TLS.
        Assert ($actual[$name].policy -eq $Expected[$name].policy) 'Identity owner/protected DACL changed (values omitted)'
        if ($ExactDescriptor) { Assert ($actual[$name].sddl -eq $Expected[$name].sddl) 'Source descriptor changed (values omitted)' }
    }
}
$sourceState = Join-Path $testRoot 'source state'
$destination = Join-Path $testRoot 'migrated state'
$binaryHash = Get-PocketFileHash $AgentPath
Test 'final Release helper initializes and loads a disposable identity locally' {
    $result = Invoke-LocalHelper 'init' $sourceState
    Assert ($result.exitCode -eq 0) 'Real helper init failed (output omitted)'
    $result = Invoke-LocalHelper 'pair' $sourceState
    Assert ($result.exitCode -eq 0) 'Real helper could not load its original identity (output omitted)'
}
$snapshot = Identity-Snapshot $sourceState
Test 'migrated identity loads in the same final Release helper without network' {
    Copy-PocketState $sourceState $destination
    $result = Invoke-LocalHelper 'pair' $destination
    Assert ($result.exitCode -eq 0) ('Migrated real helper pair failed; insecureIdentity=' + $result.insecureIdentity + ' (output omitted)')
    Assert-SameIdentity $snapshot $destination
    Assert-SameIdentity $snapshot $sourceState -ExactDescriptor
    Assert-PocketInitializedState $destination
}
foreach ($case in @('directory-system','key-system','lock-system','directory-inheritance','key-unprotected','certificate-hardlink')) {
    Test ('refuses insecure source without modifying it: ' + $case) {
        $bad = Join-Path $testRoot $case
        $rejectedDestination = Join-Path $testRoot ($case + '-must-not-exist')
        Copy-PocketState $sourceState $bad
        $relative = if($case -like 'directory-*'){'identity'}elseif($case -like 'lock-*'){'identity\identity.lock'}elseif($case -eq 'certificate-hardlink'){'identity\certificate.der'}else{'identity\private-key.der'}
        $path = Join-Path $bad $relative
        if ($case -eq 'certificate-hardlink') {
            New-Item -ItemType HardLink -Path (Join-Path $bad 'extra-certificate-link') -Target $path | Out-Null
        } else {
            # Use native DACL-only edits for deliberately invalid fixtures; Set-Acl
            # can request audit privilege when changing an already protected object.
            if ($case -like '*-system') {
                $aclArguments = @($path,'/grant','*S-1-5-18:(F)')
            } elseif ($case -eq 'directory-inheritance') {
                $aclArguments = @($path,'/grant:r',('*' + [Security.Principal.WindowsIdentity]::GetCurrent().User.Value + ':(OI)(CI)(F)'))
            } else { $aclArguments = @($path,'/inheritance:e') }
            $aclOutput = @(& icacls.exe @aclArguments 2>&1)
            Assert ($LASTEXITCODE -eq 0) 'Could not construct the deliberately invalid disposable ACL (output omitted)'
        }
        $before = Identity-Snapshot $bad
        Expect-Failure { Assert-PocketInitializedState $bad } 'Identity ACL|unlinked file'
        Expect-Failure { Copy-PocketState $bad $rejectedDestination } 'Identity ACL|unlinked file'
        Assert (-not (Test-Path -LiteralPath $rejectedDestination)) 'Invalid source was published'
        Assert-SameIdentity $before $bad -ExactDescriptor
    }
}
Test 'binary and source identity remain unchanged' {
    Assert ((Get-PocketFileHash $AgentPath) -eq $binaryHash) 'Release artifact changed'
    Assert-SameIdentity $snapshot $sourceState -ExactDescriptor
}
Write-Output ("$script:passed identity tests passed; fixtures retained at $testRoot")
