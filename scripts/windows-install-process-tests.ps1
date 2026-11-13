#requires -Version 5.1
<#
Real Windows supervisor/agent lifecycle test. Run in a dedicated PowerShell:
  powershell -NoProfile -File scripts/windows-install-process-tests.ps1
  pwsh -NoProfile -File scripts/windows-install-process-tests.ps1 -CandidateSource C:\package\windows

Only shell integration functions and the stable launcher's entry point are
substituted. Process discovery, identity capture/stop, authenticated control,
owner locks, inventory validation and readiness use the installer unchanged.
The publication observer delegates to the real atomic writer.
#>
[CmdletBinding()]
param(
    [Alias('CandidateSource')][string]$CandidateDirectory='D:\codex2\dist\codex-pocket-0.6.6-assistant-candidate-20261112\release\windows',
    [string]$LegacyDirectory='\\localhost\c$\Users\82206\AppData\Local\CodexPocket\bin\c9d7fccdb667d38179899f23af6c4f9c5081f053616aca231a0f7078a5526a5e',
    [switch]$KeepFixture
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitProcess) { throw 'Windows x64 PowerShell required.' }
if ($MyInvocation.InvocationName -eq '.') { throw 'Run this test in a dedicated PowerShell process; do not dot-source it.' }
$script:fixtureCandidate=[IO.Path]::GetFullPath($CandidateDirectory)
$script:fixtureLegacy=[IO.Path]::GetFullPath($LegacyDirectory)
$installer=Join-Path $PSScriptRoot 'windows-install.ps1'
$script:installerHash=(Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
. $installer
if ((Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant() -ne $script:installerHash) { throw 'Installer source changed while loading; rerun against a stable script.' }
$script:realFixtureAtomic=${function:Write-PocketAtomic}
$script:realFixtureReadiness=${function:Test-PocketInstalledRuntime}
$script:fixtureId=[guid]::NewGuid().ToString('N')
# Keep mutable state on a physical workspace path, outside LocalAppData/MSIX.
$script:fixtureParent=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.local'))
$script:fixtureRoot=Join-Path $script:fixtureParent ('ip-'+$script:fixtureId.Substring(0,8))
$script:fixtureInstall=Join-Path $script:fixtureRoot 'install'
$script:fixtureState=Join-Path $script:fixtureRoot 'state with spaces'
$script:fixtureControl=Join-Path $script:fixtureInstall 'control'
$script:fixtureConfigHome=Join-Path $script:fixtureRoot 'empty-codex-home'
$script:fixturePackage=Join-Path $script:fixtureRoot 'unused-desktop-package'
$script:fixtureManifest=Join-Path $script:fixtureInstall 'desktop-install.json'
$script:fixtureCaptured=@{}
$script:fixtureProcesses=[Collections.Generic.List[Diagnostics.Process]]::new()
$script:fixtureEvents=[Collections.Generic.List[object]]::new()
$script:fixtureCases=[Collections.Generic.List[string]]::new()
$script:fixtureCli=$null
$script:fixtureTask=$null
$script:fixtureWatching=$false
$script:fixtureFailStart=$false
$script:fixtureBefore=@()
$script:fixtureSuccess=$false

function Assert-Fixture($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-FixturePath([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    Assert-Fixture ($full -eq $script:fixtureRoot -or $full.StartsWith($script:fixtureRoot+'\',[StringComparison]::OrdinalIgnoreCase)) ('Fixture boundary: '+$full)
    Assert-PocketUnredirectedPath $full
}
function New-FixtureDirectory([string]$Path) {
    Assert-FixturePath $Path
    [void][IO.Directory]::CreateDirectory($Path)
    Assert-FixturePath $Path
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=Get-Acl -LiteralPath $Path
    Assert-Fixture ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -eq $sid.Value) 'Fixture directory owner differs from current user.'
    $acl.SetAccessRuleProtection($true,$false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    # Apply only the changed access section. PowerShell 5.1 Set-Acl attempts
    # additional security sections and can require SeSecurityPrivilege.
    if ($PSVersionTable.PSEdition -eq 'Desktop') {
        [IO.Directory]::SetAccessControl($Path,$acl)
    } else {
        [IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($Path),$acl)
    }
}
function Write-FixtureText([string]$Path,[string]$Text) {
    Assert-FixturePath $Path
    New-FixtureDirectory ([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}
function Add-FixtureEvent([string]$Event,$Data) {
    $script:fixtureEvents.Add([ordered]@{event=$Event;at=[DateTimeOffset]::UtcNow.ToString('o');data=$Data})
}
function Pass-Fixture([string]$Name) { $script:fixtureCases.Add($Name); Write-Output ('PASS '+$Name) }
function Test-PocketInstalledRuntime([string]$InstallRoot,[string]$StateDirectory,[string]$Generation,[bool]$RequireDesktop=$false,[bool]$RequireUi=$false,$Diagnostics=$null) {
    try { return & $script:realFixtureReadiness $InstallRoot $StateDirectory $Generation $RequireDesktop $RequireUi $Diagnostics }
    catch {
        $failure=$_
        $assistantError=$null;$controlRead=$false
        try {$health=Invoke-PocketInstallControl $StateDirectory 'status';$assistantError=Get-PocketProperty $health 'assistantRestoreError' $null;$controlRead=$true} catch {}
        $detail=@{message=$failure.Exception.Message;controlRead=$controlRead;assistantRestoreError=$assistantError;profileExists=[IO.File]::Exists((Join-Path $StateDirectory 'assistant\profile.json'))}
        Write-FixtureText (Join-Path $script:fixtureRoot 'last-readiness-failure.json') ($detail|ConvertTo-Json -Depth 4)
        throw $failure
    }
}
function Register-FixtureIdentity($Identity) {
    if ($null -eq $Identity) { return }
    Assert-FixturePath $Identity.executable
    Assert-Fixture ($Identity.pid -ne $PID) 'Refusing to capture the test coordinator for termination.'
    $script:fixtureCaptured[([string]$Identity.pid+':'+[string]$Identity.createdAt)]=@{
        pid=$Identity.pid;createdAt=$Identity.createdAt;executable=$Identity.executable
        sha256=(Get-PocketFileHash $Identity.executable);name=[IO.Path]::GetFileName($Identity.executable)
    }
}
function Get-FixtureGroup {
    $group=Get-PocketInstallGroup $script:fixtureInstall $script:fixtureState
    foreach($identity in $group) {
        Assert-FixturePath $identity.executable
        Register-FixtureIdentity $identity
        Assert-Fixture ($identity.name -in @('pocket-supervisor.exe','pocket-agent.exe')) 'Unexpected runtime or native process: fixture isolation failed.'
    }
    return ,$group
}
function Assert-FixtureGone($Identities) {
    foreach($identity in $Identities) {
        $current=Get-PocketInstallProcessIdentity $identity.pid
        Assert-Fixture ($null -eq $current -or $current.createdAt -ne $identity.createdAt -or $current.executable -ne $identity.executable) ('Captured process still alive: '+$identity.pid)
    }
}
function Assert-FixtureConfiguration {
    $config=Read-PocketRepairJson (Join-Path $script:fixtureState 'config.json')
    Assert-Fixture (@($config.runtimeDirectories).Count -eq 0 -and @($config.codexHomes).Count -eq 0) 'Runtime discovery escaped the empty fixture configuration.'
    Assert-Fixture ($config.relayUrl -eq '' -and $config.routePreferences.autoLan -eq $false -and @($config.routePreferences.routes).Count -eq 0) 'Fixture enabled a network route.'
    Assert-Fixture ($null -eq (Get-PocketProperty $config.routePreferences 'lan' $null) -and $null -eq (Get-PocketProperty $config.routePreferences 'gateway' $null)) 'Fixture listener configuration is not disabled.'
    Assert-Fixture (-not (Test-Path -LiteralPath (Join-Path $script:fixtureState 'assistant\profile.json'))) 'Fixture unexpectedly created an assistant profile.'
    Assert-Fixture (@(Get-ChildItem -LiteralPath $script:fixtureConfigHome -Force).Count -eq 0) 'Fixture Codex home is no longer empty.'
}
function New-FixtureProcess([string]$Executable,[string[]]$Arguments) {
    Assert-FixturePath $Executable
    Assert-Fixture ([IO.Path]::GetFileName($Executable) -in @('pocket-agent.exe','pocket-supervisor.exe')) 'Only real fixture Pocket binaries may execute.'
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable
    $info.Arguments=($Arguments | ForEach-Object { ConvertTo-PocketArgument $_ }) -join ' '
    $info.WorkingDirectory=$script:fixtureRoot
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.WindowStyle='Hidden'
    # Environment changes apply only to the child. No user environment/registry
    # changes. Every discoverable home/root is empty and inside this fixture.
    foreach($name in @($info.EnvironmentVariables.Keys)) {
        if ([string]$name -match '^(POCKET_|CODEX_|OPENAI_|ANTHROPIC_|AZURE_OPENAI_|VOLTA_HOME$)') { $info.EnvironmentVariables.Remove([string]$name) }
    }
    $info.EnvironmentVariables['CODEX_HOME']=$script:fixtureConfigHome
    $info.EnvironmentVariables['USERPROFILE']=Join-Path $script:fixtureRoot 'profile'
    $info.EnvironmentVariables['APPDATA']=Join-Path $script:fixtureRoot 'profile\AppData\Roaming'
    $info.EnvironmentVariables['LOCALAPPDATA']=Join-Path $script:fixtureRoot 'profile\AppData\Local'
    $info.EnvironmentVariables['PATH']=Join-Path $env:SystemRoot 'System32'
    $process=[Diagnostics.Process]::Start($info)
    $script:fixtureProcesses.Add($process)
    Register-FixtureIdentity (Get-PocketInstallProcessIdentity $process.Id)
    Add-FixtureEvent 'start' @{pid=$process.Id;executable=$Executable;arguments=$Arguments}
    return $process
}
function Start-FixtureSupervisor([string]$Generation) {
    Assert-FixtureConfiguration
    Assert-Fixture ($Generation -match '^[a-f0-9]{64}$') 'Invalid fixture generation.'
    $bin=Join-Path $script:fixtureInstall ('bin\'+$Generation)
    $agent=Join-Path $bin 'pocket-agent.exe'
    if ($script:fixtureFailStart) { $agent=Join-Path $script:fixtureRoot 'missing-agent\pocket-agent.exe' }
    New-FixtureProcess (Join-Path $bin 'pocket-supervisor.exe') @('--state',$script:fixtureState,'--owner-directory',$script:fixtureControl,'--agent',$agent) | Out-Null
}
function Wait-FixtureRuntime([string]$Generation) {
    $deadline=[DateTime]::UtcNow.AddSeconds(45)
    $last='No owner observed'
    do {
        try {
            $group=Get-FixtureGroup
            Assert-Fixture ($group.Count -eq 2) 'Waiting for supervisor and agent.'
            $health=Invoke-PocketInstallControl $script:fixtureState 'status'
            $supervisor=@($group | Where-Object {$_.name -eq 'pocket-supervisor.exe'})[0]
            $agent=@($group | Where-Object {$_.name -eq 'pocket-agent.exe'})[0]
            Assert-Fixture ($health.helperRuntime.pid -eq $agent.pid -and $health.helperRuntime.generation -eq $Generation -and $agent.parentPid -eq $supervisor.pid) 'Live helper identity differs from the fixture owner.'
            Assert-Fixture ($health.remoteEnabled -eq $false) 'Fixture remote ingress unexpectedly enabled.'
            Assert-Fixture (@($health.runtimes).Count -eq 0) 'Fixture discovered a user runtime.'
            Invoke-PocketInstallControl $script:fixtureState 'routes/read' | Out-Null
            Assert-FixtureConfiguration
            return ,$group
        } catch { $last=$_.Exception.Message }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw ('Fixture startup did not become ready: '+$last)
}
function Copy-FixturePayload([string]$Source,[string]$Destination) {
    New-FixtureDirectory $Destination
    $manifestPath=Join-Path $Source 'windows-manifest.json'
    $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    foreach($name in @($manifest.files | ForEach-Object {$_.path})+@('windows-manifest.json')) {
        Assert-Fixture ($name -match '^[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*$' -and -not $name.Contains('..')) 'Unsafe source inventory path.'
        $target=Join-Path $Destination $name
        Assert-FixturePath $target
        New-FixtureDirectory ([IO.Path]::GetDirectoryName($target))
        [IO.File]::Copy((Join-Path $Source $name),$target,$false)
    }
    Test-PocketWindowsDirectory $Destination | Out-Null
}

# Shell integration fixture boundaries. No registry, Task Scheduler, shell COM
# or installed desktop is modified or launched by these substitutions.
function Get-PocketDesktopPackage { return [pscustomobject]@{InstallLocation=$script:fixturePackage;PackageFamilyName='isolated-fixture'} }
function Get-PocketUserCliPath { return $script:fixtureCli }
function Get-PocketUserCliKind { return 'String' }
function Set-PocketUserCliPath($Value,[string]$Kind='String') {
    if ($null -ne $Value) { Assert-FixturePath $Value }
    Assert-Fixture ($Kind -eq 'String') 'Unexpected environment value kind.'
    $script:fixtureCli=$Value
}
function Send-PocketEnvironmentChanged {}
function Get-PocketTaskXml { return $script:fixtureTask }
function Set-PocketTaskXml($Xml) { if ($null -ne $Xml) { Assert-PocketTaskOwned $Xml (Join-Path $script:fixtureInstall 'pocket-launcher.exe') }; $script:fixtureTask=$Xml }
function Test-PocketTaskEquivalent($Left,$Right) { return $Left -ceq $Right }
function New-PocketShortcutBytes([string]$Temporary,[string]$Launcher,[string]$Mode,[string]$IconPath) {
    foreach($path in @($Temporary,$Launcher,$IconPath)) { Assert-FixturePath $path }
    return ,([Text.Encoding]::UTF8.GetBytes('fixture shortcut '+$Mode))
}
function Test-PocketShortcutTarget { return $false }
function Start-PocketInstalledDesktop { throw 'Fixture boundary: desktop/native startup is forbidden.' }
function Start-PocketInstalledSupervisor([string]$InstallRoot) {
    Assert-Fixture ($InstallRoot -eq $script:fixtureInstall) 'Startup escaped fixture install root.'
    Assert-FixtureGone $script:fixtureBefore
    Assert-PocketInstallLocksFree $script:fixtureInstall $script:fixtureState
    $record=Read-PocketRepairJson $script:fixtureManifest
    Assert-Fixture ($record.generation -eq $script:fixtureTarget) 'Supervisor started before candidate publication.'
    Start-FixtureSupervisor $record.generation
}
function Write-PocketAtomic([string]$Path,[byte[]]$Bytes) {
    Assert-FixturePath $Path
    if ($script:fixtureWatching -and $Path -eq $script:fixtureManifest) {
        Assert-FixtureGone $script:fixtureBefore
        Assert-Fixture ((Get-FixtureGroup).Count -eq 0) 'Publication preceded complete process stop.'
        Assert-PocketInstallLocksFree $script:fixtureInstall $script:fixtureState
        Add-FixtureEvent 'publish_after_exit_and_unlock' @{generation=([Text.Encoding]::UTF8.GetString($Bytes)|ConvertFrom-Json).generation}
    }
    & $script:realFixtureAtomic $Path $Bytes
}
function Assert-FixtureRetained {
    foreach($entry in $script:fixtureRetained.GetEnumerator()) { Assert-Fixture ((Get-PocketFileHash $entry.Key) -eq $entry.Value) ('Retained identity/state changed: '+$entry.Key) }
    $ledger=Read-PocketRepairJson (Join-Path $script:fixtureState 'commands.json')
    Assert-Fixture (@($ledger.entries).Count -eq 1 -and $ledger.entries[0].status -eq 'Unknown' -and $ledger.entries[0].key.command_id -eq 'never-replay') 'Unknown write was lost, replayed or reported confirmed.'
    Assert-Fixture (($ledger.entries[0] | ConvertTo-Json -Depth 10 -Compress) -eq $script:fixtureUnknownEntry) 'Unresolved write receipt changed.'
}

try {
    Assert-PocketUnredirectedPath $script:fixtureParent
    [void][IO.Directory]::CreateDirectory($script:fixtureParent)
    Assert-Fixture (-not (Test-Path -LiteralPath $script:fixtureRoot)) 'Fixture root already exists.'
    New-FixtureDirectory $script:fixtureRoot
    Write-FixtureText (Join-Path $script:fixtureRoot '.fixture-owner') $script:fixtureId
    foreach($path in @($script:fixtureInstall,$script:fixtureControl,$script:fixtureConfigHome,$script:fixturePackage,(Join-Path $script:fixtureRoot 'Programs'),(Join-Path $script:fixtureRoot 'Startup'),(Join-Path $script:fixtureRoot 'profile'),(Join-Path $script:fixtureRoot 'profile\AppData\Roaming'),(Join-Path $script:fixtureRoot 'profile\AppData\Local'))) { New-FixtureDirectory $path }
    Write-FixtureText (Join-Path $script:fixturePackage 'app\resources\codex.exe') 'non-executable fixture placeholder; never launched'
    $legacyHash=Get-PocketFileHash (Join-Path $script:fixtureLegacy 'windows-manifest.json')
    Assert-Fixture ($legacyHash -eq 'c9d7fccdb667d38179899f23af6c4f9c5081f053616aca231a0f7078a5526a5e') 'Legacy source differs from the authorized 0.6.3 payload.'
    $script:fixtureTarget=Get-PocketFileHash (Join-Path $script:fixtureCandidate 'windows-manifest.json')
    Assert-Fixture ($script:fixtureTarget -ne $legacyHash) 'Candidate must differ from legacy generation.'
    $legacyBin=Join-Path $script:fixtureInstall ('bin\'+$legacyHash)
    Copy-FixturePayload $script:fixtureLegacy $legacyBin
    $candidateCopy=Join-Path $script:fixtureRoot 'candidate'
    Copy-FixturePayload $script:fixtureCandidate $candidateCopy
    Assert-Fixture ((Get-PocketFileHash (Join-Path $candidateCopy 'windows-manifest.json')) -eq $script:fixtureTarget) 'Candidate changed during fixture copy.'
    $init=New-FixtureProcess (Join-Path $legacyBin 'pocket-agent.exe') @('init','--state',$script:fixtureState,'--json')
    Assert-Fixture ($init.WaitForExit(20000) -and $init.ExitCode -eq 0) 'Actual legacy agent init failed.'
    $config=Read-PocketRepairJson (Join-Path $script:fixtureState 'config.json')
    $config.runtimeDirectories=@();$config.codexHomes=@();$config.relayUrl=''
    $config.routePreferences=@{autoLan=$false;routes=@();policy='automatic'}
    Write-PocketJson (Join-Path $script:fixtureState 'config.json') $config
    New-FixtureDirectory (Join-Path $script:fixtureState 'desktop')
    Write-PocketJson (Join-Path $script:fixtureState 'desktop\desired.json') @{enabled=$false;changedAt=1}
    $unknown=@{key=@{caller_id='isolated-test';command_id='never-replay'};fingerprint=@(1..32);status='Unknown';generation=1;created_at=1;finished_at=$null}
    Write-PocketJson (Join-Path $script:fixtureState 'commands.json') @{version=1;runtime=@{computer_id=$config.computerId;runtime_id='broker'};generation=1;last_now=1;entries=@($unknown)}
    $launcher=Join-Path $script:fixtureInstall 'pocket-launcher.exe'
    [IO.File]::Copy((Join-Path $legacyBin 'pocket-launcher.exe'),$launcher,$false)
    $context=@{InstallRoot=$script:fixtureInstall;Programs=(Join-Path $script:fixtureRoot 'Programs');Startup=(Join-Path $script:fixtureRoot 'Startup')}
    $links=@((Join-Path $context.Programs 'Codex Pocket.lnk'),(Join-Path $context.Programs 'Codex Pocket Desktop.lnk'),(Join-Path $context.Startup 'Codex Pocket.lnk'))
    $record=@{version=3;singleEntry=$true;installRoot=$script:fixtureInstall;stateDirectory=$script:fixtureState;generation=$legacyHash;releaseHash=$legacyHash;bridge=$launcher;launcherHash=(Get-PocketFileHash $launcher);previousCliPath=$null;previousCliKind='String';previousTask=$null;previousTaskKnown=$true;rollback=$null;shortcuts=@($links|ForEach-Object{@{path=$_;original=$null;originalHash=$null;installed=$null;installedHash=$null}})}
    Write-PocketJson $script:fixtureManifest $record
    $script:fixtureCli=$launcher
    Start-FixtureSupervisor $legacyHash
    $script:fixtureBefore=Wait-FixtureRuntime $legacyHash
    $script:fixtureUnknownEntry=(Read-PocketRepairJson (Join-Path $script:fixtureState 'commands.json')).entries[0] | ConvertTo-Json -Depth 10 -Compress
    $script:fixtureRetained=@{}
    foreach($name in @('config.json','identity\private-key.der','identity\certificate.der')) { $path=Join-Path $script:fixtureState $name; $script:fixtureRetained[$path]=Get-PocketFileHash $path }
    $legacyRecordHash=Get-PocketFileHash $script:fixtureManifest
    # The lock test must fail while the real owner is running.
    $locked=$false
    try { Assert-PocketInstallLocksFree $script:fixtureInstall $script:fixtureState } catch { $locked=$_.Exception.Message -match 'lock is still held' }
    Assert-Fixture $locked 'Real running supervisor/agent locks were not detected.'
    Pass-Fixture 'legacy 0.6.3 owns fixture locks and authenticated local control'

    $script:fixtureWatching=$true
    $result=Invoke-PocketWindowsInstall -SourceDirectory $candidateCopy -StateDirectory $script:fixtureState -Context $context -Apply
    Assert-Fixture ($result.runtimeVerification -eq 'verified' -and $result.processesStopped -eq 2 -and $result.processesStarted -eq 1) 'Old-to-new install returned incomplete runtime evidence.'
    $newGroup=Wait-FixtureRuntime $script:fixtureTarget
    Assert-FixtureGone $script:fixtureBefore
    foreach($identity in $newGroup) { Assert-Fixture ($identity.pid -notin @($script:fixtureBefore|ForEach-Object{$_.pid}) -and $identity.generation -eq $script:fixtureTarget) 'Candidate did not obtain new process identities/generation.' }
    Assert-Fixture (@($script:fixtureEvents | Where-Object {$_.event -eq 'publish_after_exit_and_unlock'}).Count -gt 0) 'No stop-before-publication evidence captured.'
    Assert-Fixture ((Get-PocketFileHash (Join-Path $script:fixtureInstall ('desktop-install.'+$legacyHash+'.json'))) -eq $legacyRecordHash) 'Legacy installation record archive changed.'
    Assert-FixtureRetained
    Pass-Fixture 'old-to-new stops real owners before publication and verifies new identities'

    $recordHash=Get-PocketFileHash $script:fixtureManifest
    $lifecyclePath=Join-Path $script:fixtureInstall 'installer-lifecycle.json'
    $lifecycle=Read-PocketRepairJson $lifecyclePath
    $lifecycleHash=Get-PocketFileHash $lifecyclePath
    # Seed a valid failed upgrade diagnostic, not a successful checkpoint. The
    # installer must archive its exact bytes only after all old owners exit.
    $failedUpgradePath=Join-Path $script:fixtureState 'desktop\upgrade.json'
    Write-PocketJson $failedUpgradePath @{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$script:fixtureInstall;stateDirectory=$script:fixtureState;fromGeneration=$legacyHash;toGeneration=$script:fixtureTarget;phase='failed';updatedAt=1;checkpoint=$null;verification=$null;failure='rollback_failed';bridgeRestartRequired=$true}
    $failedUpgradeHash=Get-PocketFileHash $failedUpgradePath
    $script:fixtureBefore=$newGroup
    $repeat=Invoke-PocketWindowsInstall -SourceDirectory $candidateCopy -StateDirectory $script:fixtureState -Context $context -Apply
    Assert-Fixture ($repeat.runtimeVerification -eq 'verified' -and $repeat.processesStopped -eq 2 -and $repeat.processesStarted -eq 1) 'Repeated install did not perform a verified real restart.'
    $newGroup=Wait-FixtureRuntime $script:fixtureTarget
    Assert-FixtureGone $script:fixtureBefore
    Assert-Fixture ((Get-PocketFileHash $script:fixtureManifest) -eq $recordHash) 'Repeated installation rewrote completed installation records.'
    Assert-Fixture ((Get-PocketFileHash ($lifecyclePath+'.'+$lifecycle.operationId+'.'+$lifecycleHash+'.json')) -eq $lifecycleHash) 'Previous lifecycle evidence was not retained byte-for-byte.'
    Assert-FixtureRetained
    Pass-Fixture 'repeated modern install restarts real processes and retains records and unknown writes'
    $recovered=Read-PocketRepairJson $lifecyclePath
    Assert-Fixture ($recovered.supersession.reason -eq 'user_authorized_full_restart' -and $recovered.supersession.nativeTasksPreserved -eq $false -and $recovered.supersession.ownersExited -eq $true -and $recovered.supersession.locksReleased -eq $true) 'Full restart was misreported as native handoff preservation.'
    $archives=@($recovered.supersession.archives | Where-Object {$_.source -eq $failedUpgradePath})
    Assert-Fixture ($archives.Count -eq 1 -and $archives[0].sha256 -eq $failedUpgradeHash -and $archives[0].state -eq 'superseded') 'Failed upgrade supersession evidence missing.'
    Assert-FixturePath $archives[0].archive
    Assert-Fixture ((Get-PocketFileHash $archives[0].archive) -eq $failedUpgradeHash -and -not (Test-Path -LiteralPath $failedUpgradePath)) 'Failed upgrade journal was lost or left active.'
    $archivedUpgrade=Read-PocketRepairJson $archives[0].archive
    Assert-Fixture ($archivedUpgrade.phase -eq 'failed' -and $null -eq $archivedUpgrade.checkpoint -and $null -eq $archivedUpgrade.verification) 'Failed upgrade was rewritten as checkpoint/handoff success.'
    Pass-Fixture 'failed upgrade journal is superseded after exit with exact failure evidence retained'

    # Real startup failure: the fixture supervisor is started with a missing
    # fixture agent path. Readiness/RPC are not mocked or forced to succeed.
    $script:fixtureBefore=$newGroup;$script:fixtureFailStart=$true
    $failed=$false;$failureResult=$null
    try { $failureResult=Invoke-PocketWindowsInstall -SourceDirectory $candidateCopy -StateDirectory $script:fixtureState -Context $context -Apply }
    catch { $failed=$_.Exception.Message -match 'runtime_verification_failed'; if (-not $failed) { throw } }
    Assert-Fixture ($failed -and $null -eq $failureResult) 'Failed runtime startup was reported as installation success.'
    Assert-Fixture ((Read-PocketRepairJson $lifecyclePath).phase -eq 'verification_failed') 'Failed verification lifecycle was not retained.'
    Assert-FixtureRetained
    Pass-Fixture 'actual supervisor startup failure stays failed with retained lifecycle and receipts'
    Assert-Fixture ((Get-PocketFileHash $installer) -eq $script:installerHash) 'Installer source changed during the run; rerun against the final script.'
    $script:fixtureSuccess=$true
} catch {
    Write-Output ('TEST FAILURE: '+$_.Exception.Message)
    throw
} finally {
    # Capture any last fixture child before cleanup, then stop supervisors first.
    # Termination uses captured creation time, path and hash, never process names.
    $cleanupError=$null
    try {
        if (Test-Path -LiteralPath $script:fixtureInstall) { Get-FixtureGroup | Out-Null }
        foreach($identity in @($script:fixtureCaptured.Values | Sort-Object @{Expression={if($_.name -eq 'pocket-supervisor.exe'){0}else{1}}})) {
            Assert-FixturePath $identity.executable
            $current=Get-PocketInstallProcessIdentity $identity.pid
            if ($null -ne $current -and $current.createdAt -eq $identity.createdAt -and $current.executable -eq $identity.executable) { Stop-PocketInstallProcess $identity }
        }
        Assert-FixtureGone @($script:fixtureCaptured.Values)
        Assert-Fixture ((Get-FixtureGroup).Count -eq 0) 'Fixture processes remain after cleanup.'
        Assert-PocketInstallLocksFree $script:fixtureInstall $script:fixtureState
        if (Test-Path -LiteralPath (Join-Path $script:fixtureRoot '.fixture-owner')) {
            $summary=[ordered]@{passed=$script:fixtureCases.Count;cases=$script:fixtureCases.ToArray();success=$script:fixtureSuccess;powerShellVersion=$PSVersionTable.PSVersion.ToString();installerSha256=$script:installerHash;candidateGeneration=(Get-Variable fixtureTarget -Scope Script -ValueOnly -ErrorAction SilentlyContinue);captured=@($script:fixtureCaptured.Values);events=$script:fixtureEvents.ToArray();cleanup='captured identities exited and locks free'}
            Write-PocketJson (Join-Path $script:fixtureRoot 'process-test-result.json') $summary
            Assert-FixturePath $script:fixtureRoot
            Assert-Fixture (([IO.File]::ReadAllText((Join-Path $script:fixtureRoot '.fixture-owner'))) -eq $script:fixtureId) 'Fixture cleanup marker changed.'
            if (-not $KeepFixture -and $script:fixtureSuccess) {
                # Validate the whole tree without traversing a reparse point.
                $pending=[Collections.Generic.Queue[string]]::new();$pending.Enqueue($script:fixtureRoot)
                while($pending.Count) { foreach($item in Get-ChildItem -LiteralPath $pending.Dequeue() -Force) { Assert-FixturePath $item.FullName; if($item.PSIsContainer){$pending.Enqueue($item.FullName)} } }
                Assert-Fixture ([IO.Path]::GetDirectoryName($script:fixtureRoot) -eq $script:fixtureParent -and [IO.Path]::GetFileName($script:fixtureRoot) -eq ('ip-'+$script:fixtureId.Substring(0,8))) 'Cleanup root is not the generated workspace fixture.'
                Remove-Item -LiteralPath $script:fixtureRoot -Recurse -Force
            }
        }
    } catch { $cleanupError=$_ }
    foreach($process in $script:fixtureProcesses) { $process.Dispose() }
    if ($null -ne $cleanupError) { throw ('Fixture cleanup incomplete; retained '+$script:fixtureRoot+': '+$cleanupError.Exception.Message) }
    if (Test-Path -LiteralPath $script:fixtureRoot) { Write-Output ('Fixture evidence retained: '+$script:fixtureRoot) }
}
Write-Output ("$($script:fixtureCases.Count) real-process installer lifecycle tests passed; captured test processes exited and locks released.")
