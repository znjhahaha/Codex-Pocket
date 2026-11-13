#requires -Version 5.1
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'windows-install.ps1')
$script:realEnter=${function:Enter-PocketInstallLifecycle}
$script:realComplete=${function:Complete-PocketInstallLifecycle}
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('pocket-installer-life-'+[guid]::NewGuid().ToString('N'))
$testRoot=[IO.Path]::GetFullPath($testRoot)
[void][IO.Directory]::CreateDirectory($testRoot)
function Assert($Condition,[string]$Message) { if(-not $Condition){throw $Message} }
function Put([string]$Path,[string]$Text) {
    Assert ($Path.StartsWith($testRoot+'\',[StringComparison]::OrdinalIgnoreCase)) 'Fixture write escaped test root'
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}
function Test([string]$Name,[scriptblock]$Body) { & $Body; $script:passed++; Write-Output ('PASS '+$Name) }
function Fails([scriptblock]$Body,[string]$Pattern) {
    try { & $Body | Out-Null } catch { Assert ($_.Exception.Message -match $Pattern) ('Wrong failure: '+$_.Exception.Message); return }
    throw ('Expected failure: '+$Pattern)
}
$script:passed=0
# Synthetic inventory and all OS/process/control boundaries are replaced before
# installation. No live process, endpoint, installed directory or model is used.
$source=Join-Path $testRoot 'payload'
foreach($name in Get-PocketWindowsFiles){Put (Join-Path $source $name) ('fixture '+$name)}
$files=@(foreach($name in Get-PocketWindowsFiles){@{path=$name;bytes=(Get-Item (Join-Path $source $name)).Length;sha256=(Get-PocketFileHash (Join-Path $source $name))}})
Write-PocketJson (Join-Path $source 'windows-manifest.json') @{protocol=1;version='0.6.3';files=$files}
$state=Join-Path $testRoot 'state with spaces'
Put (Join-Path $state 'config.json') '{"protocol":1,"computerId":"fixture-computer"}'
Put (Join-Path $state 'history.json') 'history must survive'
Put (Join-Path $state 'identity\private-key.der') 'identity must survive'
Put (Join-Path $testRoot 'native\app\resources\codex.exe') 'unexecuted native'
$context=@{InstallRoot=(Join-Path $testRoot 'installed');Programs=(Join-Path $testRoot 'Programs');Startup=(Join-Path $testRoot 'Startup')}
$script:cli=$null;$script:task=$null;$script:events=@()
function Assert-PocketWindowsPrerequisites {}
function Assert-PocketInitializedState([string]$Directory) {}
function Test-PocketWindowsDirectory([string]$Directory) { return Get-Content (Join-Path $Directory 'windows-manifest.json') -Raw | ConvertFrom-Json }
function Get-PocketDesktopPackage { return [pscustomobject]@{InstallLocation=(Join-Path $testRoot 'native');PackageFamilyName='fixture'} }
function Get-PocketUserCliPath {return $script:cli}
function Get-PocketUserCliKind {return 'String'}
function Set-PocketUserCliPath($Value,[string]$Kind='String') {$script:cli=$Value}
function Send-PocketEnvironmentChanged {}
function Get-PocketTaskXml {return $script:task}
function Set-PocketTaskXml($Xml) {$script:task=$Xml}
function New-PocketShortcutBytes([string]$Temporary,[string]$Launcher,[string]$Mode,[string]$IconPath) {return ,([Text.Encoding]::UTF8.GetBytes('fixture shortcut'))}
function Test-PocketShortcutTarget {return $false}

# First regressions exercise the installer's integration with lifecycle stages.
function Enter-PocketInstallLifecycle([string]$InstallRoot,[string]$StateDirectory,$Saved) {
    $script:events+=@('stop')
    return @{processesStopped=2;processesStarted=0}
}
function Complete-PocketInstallLifecycle($Lifecycle,[string]$InstallRoot,[string]$StateDirectory,[string]$Generation) {
    Assert ((Get-Content (Join-Path $InstallRoot 'desktop-install.json') -Raw | ConvertFrom-Json).generation -eq $Generation) 'Restart occurred before publication'
    $script:events+=@('restart','verified')
    return @{processesStarted=1;runtimeVerification='verified'}
}
Test 'apply stops before publication and automatically restarts with verified result' {
    $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $context -Apply
    Assert (($script:events -join ',') -eq 'stop,restart,verified') 'Applied installer omitted stop/restart/verify lifecycle'
    Assert ($result.runtimeVerification -eq 'verified' -and $result.processesStopped -eq 2 -and $result.processesStarted -eq 1) 'Install success lacked runtime evidence'
}
Test 'dry run never enters lifecycle' {
    $script:events=@()
    Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $context | Out-Null
    Assert ($script:events.Count -eq 0) 'Dry run touched runtime'
}
Test 'verification failure cannot become installation success' {
    function Complete-PocketInstallLifecycle {throw 'fixture_runtime_verification_failed'}
    Fails {Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $context -Apply} 'runtime_verification_failed'
    Assert ((Get-PocketFileHash (Join-Path $state 'history.json')) -eq (Get-PocketBytesHash ([Text.Encoding]::UTF8.GetBytes('history must survive')))) 'History changed'
    Assert ((Get-PocketFileHash (Join-Path $state 'identity\private-key.der')) -eq (Get-PocketBytesHash ([Text.Encoding]::UTF8.GetBytes('identity must survive')))) 'Pairing identity changed'
}
${function:Enter-PocketInstallLifecycle}=$script:realEnter
${function:Complete-PocketInstallLifecycle}=$script:realComplete
$generation=Get-PocketFileHash (Join-Path $source 'windows-manifest.json')
$bin=Join-Path $context.InstallRoot ('bin\'+$generation)
$script:rows=@();$script:identities=@{};$script:stops=@();$script:locks=0;$script:launches=0
function Get-PocketInstallProcesses {return $script:rows}
function Get-PocketInstallProcessIdentity([int]$ProcessId) {return $script:identities[$ProcessId]}
function Stop-PocketInstallProcess($Identity) {
    $current=$script:identities[$Identity.pid]
    Assert ($null -ne $current -and $current.createdAt -eq $Identity.createdAt -and $current.executable -eq $Identity.executable) 'identity_changed'
    $script:stops+=@($Identity.pid)
    $script:identities.Remove($Identity.pid)
    $script:rows=@($script:rows|Where-Object {$_.ProcessId -ne $Identity.pid})
}
function Assert-PocketInstallLocksFree {$script:locks++}
function Start-PocketInstalledSupervisor {$script:launches++}
function Start-PocketInstalledDesktop {throw 'Unexpected Desktop launch in fixture'}
function Start-PocketInstalledUi {throw 'Unexpected Pocket UI launch in fixture'}
function Invoke-PocketInstallControl([string]$StateDirectory,[string]$Method) {
    Assert ($Method -in @('status','routes/read')) 'Installer called an unauthorized method'
    if($Method -eq 'routes/read'){return @{routePreferences=@{fixture=$true}}}
    return $script:health
}
function Get-PocketInstallControlOwner {return 20}
function Add-Process([int]$Id,[string]$Name,[int]$Parent=0,[string]$Directory=$bin,[string]$ProcessState=$state) {
    $path=Join-Path $Directory $Name
    $script:rows+=@([pscustomobject]@{ProcessId=$Id;ParentProcessId=$Parent;ExecutablePath=$path;CommandLine=('"'+$path+'" --state "'+$ProcessState+'"')})
    $script:identities[$Id]=@{pid=$Id;createdAt=[uint64](1000+$Id);executable=$path}
}
function Legacy-Group {
    $script:rows=@();$script:identities=@{};$script:stops=@();$script:locks=0
    Add-Process 10 'pocket-supervisor.exe'
    Add-Process 20 'pocket-agent.exe' 10
    Add-Process 30 'cloudflared.exe' 20
    $script:health=@{protocol=1;computerId='fixture-computer';helperRuntime=@{pid=20;generation=$generation};remoteControlProtocol=1;acceptingWrites=$true;remoteEnabled=$false}
}
function Remove-TestLifecycle {
    $path=Join-Path $context.InstallRoot 'installer-lifecycle.json'
    if([IO.File]::Exists($path)){[IO.File]::Delete($path)} # test-only disposable journal
}
Test 'legacy 0.6.3 stops supervisor before exact children and retains write ledger' {
    Legacy-Group
    Put (Join-Path $state 'commands.json') '{"unconfirmedWrites":6,"result":"unknown"}'
    $ledgerHash=Get-PocketFileHash (Join-Path $state 'commands.json')
    $r=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    Assert (($script:stops -join ',') -eq '10,20,30' -and $script:locks -eq 1) 'Legacy group exit order or natural lock proof missing'
    Assert ($r.mode -eq 'full_process_restart' -and $r.nativeTasksPreserved -eq $false -and $r.phase -eq 'stopped') 'Termination misrepresented as safe handoff'
    Assert ((Get-PocketFileHash (Join-Path $state 'commands.json')) -eq $ledgerHash) 'Unknown write ledger changed'
}
Test 'stock 0.6.3 status without runtime stamp uses exact control socket owner' {
    Remove-TestLifecycle;Legacy-Group
    $script:health.Remove('helperRuntime')
    $result=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    Assert ($result.phase -eq 'stopped' -and ($script:stops -join ',') -eq '10,20,30') 'Stock legacy status without stamp was rejected'
    Remove-TestLifecycle
}
Test 'optional discovery warnings cannot replace the required authenticated bridge' {
    Remove-TestLifecycle;Legacy-Group
    Put (Join-Path $testRoot 'native\app\Codex.exe') 'associated desktop fixture'
    Add-Process 5 'Codex.exe' 0 (Join-Path $testRoot 'native\app')
    Add-Process 25 'pocket-bridge.exe' 5
    Add-Process 35 'codex.exe' 25 (Join-Path $testRoot 'native\app\resources')
    $group=Get-PocketInstallGroup $context.InstallRoot $state;$sup=$group[0];$agent=$group[1]
    [void][IO.Directory]::CreateDirectory((Join-Path $state 'desktop'))
    Write-PocketJson (Join-Path $state 'desktop\supervisor.json') @{pid=10;childPid=20;runtime=@{generation=$generation;processCreatedAt=$sup.createdAt;executableSha256=$sup.sha256}}
    $script:health.helperRuntime.processIdentity=@{pid=20;createdAt=$agent.createdAt}
    $script:health.helperRuntime.executableSha256=$agent.sha256
    $script:health.discoveryErrors=@(@{source='cli';code='unavailable'},@{source='desktop';code='unavailable'})
    $script:health.runtimes=@(@{id='unrelated-runtime';source='desktop';online=$true;bridgeVersion='0.6.3'})
    $descriptor=Join-Path $context.InstallRoot 'runtimes\optional-warning.json'
    Put $descriptor '{"protocol":1,"runtimeId":"required-runtime","pid":25}'
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $true} 'bridge discovery not verified'
    $script:health.runtimes[0].id='required-runtime'
    Assert (Test-PocketInstalledRuntime $context.InstallRoot $state $generation $true) 'Unrelated discovery warnings blocked the exact online bridge'
    Put $descriptor '{"protocol":1,"runtimeId":"required-runtime","pid":999}'
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $true} 'bridge discovery not verified'
    Put $descriptor '{"protocol":1,"runtimeId":"required-runtime","pid":25}'
    $script:health.runtimes[0].online=$false
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $true} 'bridge discovery not verified'
    $script:health.runtimes[0].online=$true
    function Start-PocketInstalledDesktop {}
    $lifecycle=@{phase='stopped';restartDesktop=$true;processesStarted=0}
    $plan=@{installRoot=$context.InstallRoot;stateDirectory=$state;processesStarted=0}
    $record=Read-PocketRepairJson (Join-Path $context.InstallRoot 'desktop-install.json')
    $result=Complete-PocketWindowsInstall $plan $record $lifecycle
    $saved=Read-PocketRepairJson (Join-Path $context.InstallRoot 'installer-lifecycle.json')
    Assert ($result.runtimeVerification -eq 'verified' -and $result.discoveryWarnings.Count -eq 2 -and $saved.discoveryWarnings.Count -eq 2) 'Discovery warnings were lost from durable evidence or install result'
    Assert ($saved.discoveryWarnings[0].source -eq 'cli' -and $saved.discoveryWarnings[1].source -eq 'desktop') 'Discovery warning sources changed'
    [IO.File]::Delete($descriptor);Remove-TestLifecycle
}
Test 'captured Pocket UI is stopped with durable restart intent' {
    Remove-TestLifecycle;Legacy-Group
    Add-Process 15 'pocket-desktop.exe'
    $result=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    $saved=Read-PocketRepairJson (Join-Path $context.InstallRoot 'installer-lifecycle.json')
    Assert ((Get-PocketProperty $result 'restartUi' $false) -and (Get-PocketProperty $saved 'restartUi' $false)) 'Stopped Pocket UI lost durable restart intent'
    Assert (($script:stops -join ',') -eq '10,15,20,30' -and -not $result.restartDesktop) 'Pocket UI restart was confused with associated Codex Desktop'
    Remove-TestLifecycle
}
Test 'interrupted retry retains UI restart intent after the old UI has exited' {
    foreach($phase in @('stopping','stopped','starting','verification_failed')) {
        Remove-TestLifecycle;Legacy-Group
        Write-PocketJson (Join-Path $context.InstallRoot 'installer-lifecycle.json') @{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$context.InstallRoot;stateDirectory=$state;phase=$phase;processes=@();restartUi=$true}
        $result=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
        Assert (Get-PocketProperty $result 'restartUi' $false) ('Retry lost UI restart intent in phase '+$phase)
    }
    Remove-TestLifecycle
}
Test 'older interrupted lifecycle recovers UI intent from captured identities' {
    Legacy-Group;Add-Process 15 'pocket-desktop.exe'
    $captured=Get-PocketInstallGroup $context.InstallRoot $state
    Legacy-Group
    Write-PocketJson (Join-Path $context.InstallRoot 'installer-lifecycle.json') @{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$context.InstallRoot;stateDirectory=$state;phase='stopped';processes=$captured}
    $result=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    Assert (Get-PocketProperty $result 'restartUi' $false) 'Older lifecycle lost already-stopped Pocket UI'
    Remove-TestLifecycle
}
Test 'later install does not reopen UI closed after a verified lifecycle' {
    Legacy-Group
    Write-PocketJson (Join-Path $context.InstallRoot 'installer-lifecycle.json') @{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$context.InstallRoot;stateDirectory=$state;phase='verified';processes=@();restartUi=$true}
    $result=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    Assert (-not (Get-PocketProperty $result 'restartUi' $false)) 'Verified historical UI intent reopened a closed app'
    Remove-TestLifecycle
}
Test 'completion restores UI only when requested and requires it in readiness' {
    function Start-PocketInstalledUi {$script:uiLaunches++}
    function Test-PocketInstalledRuntime([string]$InstallRoot,[string]$StateDirectory,[string]$Generation,[bool]$RequireDesktop=$false,[bool]$RequireUi=$false) {
        Assert ($RequireUi -eq $script:expectedUi -and -not $RequireDesktop) 'UI readiness requirement did not follow retained intent'
        return $true
    }
    foreach($uiIntent in @($false,$true,$null)) {
        $script:uiLaunches=0;$script:launches=0;$script:expectedUi=[bool]$uiIntent
        $lifecycle=@{phase='stopped';restartDesktop=$false;processesStarted=0}
        if($null -ne $uiIntent){$lifecycle.restartUi=$uiIntent}
        $result=Complete-PocketInstallLifecycle $lifecycle $context.InstallRoot $state $generation
        Assert ($script:uiLaunches -eq [int]$script:expectedUi -and $script:launches -eq 1) 'Stopped UI was not restored exactly once'
        Assert ($result.processesStarted -eq (1+[int]$script:expectedUi) -and $result.runtimeVerification -eq 'verified') 'UI restart accounting or verification failed'
    }
    Remove-TestLifecycle
}
Test 'legacy socket owned by another PID cannot authorize a group stop' {
    Legacy-Group;$script:health.Remove('helperRuntime')
    function Get-PocketInstallControlOwner {return 999}
    Fails {Enter-PocketInstallLifecycle $context.InstallRoot $state $null} 'identity does not match'
    Assert ($script:stops.Count -eq 0) 'Foreign listener caused termination'
}
Test 'foreign native Codex and global cloudflared never join the Pocket group' {
    Legacy-Group
    Add-Process 40 'cloudflared.exe' 999 (Join-Path $testRoot 'global')
    Add-Process 50 'codex.exe' 999 (Join-Path $testRoot 'native\app\resources')
    Add-Process 60 'cloudflared.exe' 999
    $group=Get-PocketInstallGroup $context.InstallRoot $state
    Assert (($group.pid -join ',') -eq '10,20,30') 'Unrelated processes entered Pocket group'
}
Test 'supervisor-owned tunnel joins full restart while global tunnel stays outside' {
    Legacy-Group
    Add-Process 15 'cloudflared.exe' 10
    Add-Process 45 'cloudflared.exe' 999
    $group=Get-PocketInstallGroup $context.InstallRoot $state
    Assert (@($group|Where-Object {$_.pid -eq 15 -and $_.parentPid -eq 10}).Count -eq 1) 'Supervisor-owned tunnel was omitted'
    Assert (@($group|Where-Object {$_.pid -eq 45}).Count -eq 0) 'Unrelated global tunnel entered restart group'
}
Test 'only bridge-associated native and desktop ancestry enter the restart group' {
    Legacy-Group
    Put (Join-Path $testRoot 'native\app\Codex.exe') 'associated desktop fixture'
    Add-Process 5 'Codex.exe' 0 (Join-Path $testRoot 'native\app')
    Add-Process 25 'pocket-bridge.exe' 5
    Add-Process 35 'codex.exe' 25 (Join-Path $testRoot 'native\app\resources')
    Add-Process 45 'codex.exe' 999 (Join-Path $testRoot 'native\app\resources')
    $group=Get-PocketInstallGroup $context.InstallRoot $state
    Assert (@($group|Where-Object {$_.pid -eq 5 -and $_.name -eq 'associated-desktop'}).Count -eq 1) 'Associated desktop not captured'
    Assert (@($group|Where-Object {$_.pid -eq 35 -and $_.name -eq 'associated-native'}).Count -eq 1) 'Bridge native not captured'
    Assert (@($group|Where-Object {$_.pid -eq 45}).Count -eq 0) 'Unrelated same-image native was captured'
}
Test 'wrong state directory is rejected before any process exit' {
    Legacy-Group
    $script:rows[0].CommandLine=$script:rows[0].CommandLine.Replace($state,(Join-Path $testRoot 'other state'))
    Fails {Get-PocketInstallGroup $context.InstallRoot $state} 'state directory differs'
    Assert ($script:stops.Count -eq 0) 'Wrong state caused termination'
}
Test 'authenticated status from another helper is rejected' {
    Remove-TestLifecycle;Legacy-Group
    $script:health.helperRuntime.pid=99
    Fails {Enter-PocketInstallLifecycle $context.InstallRoot $state $null} 'identity does not match'
    Assert ($script:stops.Count -eq 0) 'Wrong authenticated owner was terminated'
}
Test 'modern draining handoff is cancelled by exact operation and run before full restart' {
    Legacy-Group
    $script:health.handoffCapabilities=@{supported=$true;generation='11111111-1111-4111-8111-111111111111'}
    $script:health.acceptingWrites=$false
    $run=$script:health.handoffCapabilities.generation;$op='22222222-2222-4222-8222-222222222222'
    $path=Join-Path $state 'handoff\state.json'
    $script:cancelExpectedId=$op;$script:cancelExpectedRun=$run;$script:cancelExpectedPath=$path
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    Write-PocketJson $path @{protocol=1;generation=$run;operations=@{$op=@{receipt=@{id=$op;generation=$run;phase='draining';checkpoint=$null;unconfirmedWrites=6}}}}
    function Invoke-PocketInstallControl([string]$StateDirectory,[string]$Method,$Params) {
        if($Method -eq 'upgrade/cancel') {
            Assert ($Params.id -eq $script:cancelExpectedId -and $Params.fromGeneration -eq $script:cancelExpectedRun -and $null -eq $Params.checkpoint) 'Wrong operation cancellation'
            Assert ($script:stops.Count -eq 0) 'Helper stopped before authenticated cancellation'
            $saved=Read-PocketRepairJson $script:cancelExpectedPath;$saved.operations.($script:cancelExpectedId).receipt.phase='cancelled';Write-PocketJson $script:cancelExpectedPath $saved
            $script:health.acceptingWrites=$true
            return @{protocol=1;id=$script:cancelExpectedId;generation=$script:cancelExpectedRun;phase='cancelled';acceptingWrites=$true;unconfirmedWrites=6}
        }
        return $script:health
    }
    $r=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    Assert ($r.phase -eq 'stopped' -and $r.cancellations.Count -eq 1 -and $r.cancellations[0].unconfirmedWrites -eq 6) 'Modern cancellation/restart absent'
    Assert ((Read-PocketRepairJson $path).operations.$op.receipt.phase -eq 'cancelled') 'Cancelled receipt was deleted'
    Remove-TestLifecycle;[IO.File]::Delete($path)
}
Test 'failed upgrade is archived byte-identically only after exact owner exit' {
    Legacy-Group
    $path=Join-Path $state 'desktop\upgrade.json'
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    Write-PocketJson $path @{phase='failed';operationId='original-failed-operation';installRoot=$context.InstallRoot;stateDirectory=$state;owner=$script:identities[10]}
    $hash=Get-PocketFileHash $path
    $result=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    $archive=$result.supersession.archives[0]
    Assert ((Get-PocketFileHash $archive.archive) -eq $hash -and $archive.originalPhase -eq 'failed' -and $archive.state -eq 'superseded') 'Failed upgrade evidence changed or misclassified'
    Assert (-not [IO.File]::Exists($path) -and $result.supersession.ownersExited -and $result.supersession.locksReleased -and $result.supersession.nativeTasksPreserved -eq $false) 'Supersession evidence incomplete'
    Remove-TestLifecycle
}
Test 'release pending remains unchanged and blocks unresolved full restart' {
    Legacy-Group
    $path=Join-Path $state 'handoff\state.json'
    Put $path '{"generation":"fixture-run","operations":{"recovery-verify":{"receipt":{"generation":"fixture-run","phase":"release_pending","unconfirmedWrites":6}}}}'
    $hash=Get-PocketFileHash $path
    Fails {Enter-PocketInstallLifecycle $context.InstallRoot $state $null} 'handoff_release_exit_unresolved'
    Assert ((Get-PocketFileHash $path) -eq $hash -and $script:stops.Count -eq 0) 'Handoff evidence discarded'
    [IO.File]::Delete($path);Remove-TestLifecycle
}
Test 'retry recovers interrupted installer using exact captured identity and archive evidence' {
    Legacy-Group
    $path=Join-Path $context.InstallRoot 'installer-lifecycle.json'
    $priorId=[guid]::NewGuid().ToString()
    Write-PocketJson $path @{schema=1;operationId=$priorId;installRoot=$context.InstallRoot;stateDirectory=$state;phase='verification_failed';processes=(Get-PocketInstallGroup $context.InstallRoot $state)}
    $hash=Get-PocketFileHash $path
    $result=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    Assert ($result.recoveredFrom.operationId -eq $priorId -and (Get-PocketFileHash $result.recoveredFrom.archive) -eq $hash) 'Interrupted lifecycle evidence lost on retry'
    Assert ($result.phase -eq 'stopped' -and ($script:stops -join ',') -eq '10,20,30') 'Exact retry failed to stop verified group'
    Remove-TestLifecycle
}
Test 'successful lifecycle evidence is archived before the next stop' {
    Legacy-Group
    $path=Join-Path $context.InstallRoot 'installer-lifecycle.json'
    $op=[guid]::NewGuid().ToString()
    Write-PocketJson $path @{schema=1;operationId=$op;installRoot=$context.InstallRoot;stateDirectory=$state;phase='verified'}
    $hash=Get-PocketFileHash $path
    Enter-PocketInstallLifecycle $context.InstallRoot $state $null | Out-Null
    Assert ((Get-PocketFileHash ($path+'.'+$op+'.'+$hash+'.json')) -eq $hash) 'Prior successful evidence was overwritten'
    Remove-TestLifecycle
}
Test 'retry verifies partially archived supersession and rejects corrupt evidence' {
    Legacy-Group
    $id=[guid]::NewGuid().ToString();$sourceJournal=Join-Path $state 'desktop\maintenance.json'
    Put $sourceJournal '{"phase":"owned"}'
    $hash=Get-PocketFileHash $sourceJournal
    $archive=Join-Path $state ('desktop\recovery\installer-'+$id+'\maintenance.json.'+$hash+'.json')
    Put $archive 'corrupt archive'
    $prior=@{schema=1;operationId=$id;installRoot=$context.InstallRoot;stateDirectory=$state;phase='stopped';processes=(Get-PocketInstallGroup $context.InstallRoot $state);supersession=@{archives=@(@{source=$sourceJournal;archive=$archive;sha256=$hash;state='archived'})}}
    Write-PocketJson (Join-Path $context.InstallRoot 'installer-lifecycle.json') $prior
    Fails {Enter-PocketInstallLifecycle $context.InstallRoot $state $null} 'archive integrity failed'
    Assert ($script:stops.Count -eq 0) 'Corrupt archive triggered process exit'
    [IO.File]::Copy($sourceJournal,$archive,$true)
    $r=Enter-PocketInstallLifecycle $context.InstallRoot $state $null
    Assert ($r.recoveredFrom.operationId -eq $id -and (Get-PocketFileHash $archive) -eq $hash) 'Partial archive did not recover'
    Remove-TestLifecycle
}
Test 'new generation requires authenticated helper identity and routes' {
    Legacy-Group
    $group=Get-PocketInstallGroup $context.InstallRoot $state
    $sup=$group[0];$agent=$group[1]
    Write-PocketJson (Join-Path $state 'desktop\supervisor.json') @{pid=10;childPid=20;runtime=@{generation=$generation;processCreatedAt=$sup.createdAt;executableSha256=$sup.sha256}}
    $script:health.helperRuntime.processIdentity=@{pid=20;createdAt=$agent.createdAt}
    $script:health.helperRuntime.executableSha256=$agent.sha256
    Assert (Test-PocketInstalledRuntime $context.InstallRoot $state $generation) 'Healthy new runtime not accepted'
    $script:health.helperRuntime.processIdentity.createdAt++
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation} 'identity or admission unverified'
    $script:health.helperRuntime.processIdentity.createdAt--
    $script:health.acceptingWrites=$false
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation} 'identity or admission unverified'
}
Test 'a launch receipt alone never confirms readiness' {
    $script:rows=@();$script:identities=@{}
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation} 'generation not verified'
}
Test 'saved assistant restoration must be verified before install success' {
    Legacy-Group
    $group=Get-PocketInstallGroup $context.InstallRoot $state
    $sup=$group[0];$agent=$group[1]
    Write-PocketJson (Join-Path $state 'desktop\supervisor.json') @{pid=10;childPid=20;runtime=@{generation=$generation;processCreatedAt=$sup.createdAt;executableSha256=$sup.sha256}}
    $script:health.helperRuntime.processIdentity=@{pid=20;createdAt=$agent.createdAt}
    $script:health.helperRuntime.executableSha256=$agent.sha256
    $script:health.assistantRestoreError='assistant_missing_config'
    $script:health.Remove('runtimes')
    Assert (Test-PocketInstalledRuntime $context.InstallRoot $state $generation) 'An absent optional inventory must not require an unconfigured assistant'
    $profilePath=Join-Path $state 'assistant\profile.json'
    [void][IO.Directory]::CreateDirectory((Split-Path $profilePath -Parent))
    Write-PocketJson $profilePath @{schema=1;configHome='fixture';nativeBinary='fixture';sourceRuntimeId='fixture'}
    try {
        $script:health.assistantRestoreError='assistant_runtime_start_failed'
        Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation} 'Assistant restoration not verified'
        $script:health.assistantRestoreError=$null
        Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation} 'Assistant restoration not verified'
        $script:health.runtimes=@(@{source='cli';online=$true;role=@{kind='managedHost';generation='fixture';reconciliationRequired=$true}})
        Assert (Test-PocketInstalledRuntime $context.InstallRoot $state $generation) 'Attached but reconciliation-fenced assistant must remain available without reissuing work'
        $script:health.runtimes[0].online=$false
        Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation} 'Assistant restoration not verified'
    } finally {
        Remove-Item -LiteralPath $profilePath -Force
        $script:health.runtimes=@()
    }
}
Test 'UI readiness requires one verified UI in the installed generation' {
    Legacy-Group
    $group=Get-PocketInstallGroup $context.InstallRoot $state;$sup=$group[0];$agent=$group[1]
    Write-PocketJson (Join-Path $state 'desktop\supervisor.json') @{pid=10;childPid=20;runtime=@{generation=$generation;processCreatedAt=$sup.createdAt;executableSha256=$sup.sha256}}
    $script:health.helperRuntime.processIdentity=@{pid=20;createdAt=$agent.createdAt}
    $script:health.helperRuntime.executableSha256=$agent.sha256
    Assert (Test-PocketInstalledRuntime $context.InstallRoot $state $generation) 'Headless install incorrectly required UI'
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $false $true} 'Pocket UI restart not verified'
    Add-Process 40 'pocket-desktop.exe'
    Assert (Test-PocketInstalledRuntime $context.InstallRoot $state $generation $false $true) 'Verified same-generation UI was rejected'
    Add-Process 45 'pocket-desktop.exe'
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $false $true} 'Pocket UI restart not verified'
    $script:rows=@($script:rows|Where-Object {$_.ProcessId -ne 45});$script:identities.Remove(45)
    $script:uiReadinessGroup=Get-PocketInstallGroup $context.InstallRoot $state
    @($script:uiReadinessGroup|Where-Object {$_.name -eq 'pocket-desktop.exe'})[0].generation=('0'*64)
    function Get-PocketInstallGroup {return $script:uiReadinessGroup}
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $false $true} 'generation not verified'
}
Test 'associated desktop restart requires authenticated new bridge discovery' {
    Legacy-Group
    Put (Join-Path $testRoot 'native\app\Codex.exe') 'associated desktop fixture'
    Add-Process 5 'Codex.exe' 0 (Join-Path $testRoot 'native\app')
    Add-Process 25 'pocket-bridge.exe' 5
    Add-Process 35 'codex.exe' 25 (Join-Path $testRoot 'native\app\resources')
    $group=Get-PocketInstallGroup $context.InstallRoot $state;$sup=$group[0];$agent=$group[1]
    Write-PocketJson (Join-Path $state 'desktop\supervisor.json') @{pid=10;childPid=20;runtime=@{generation=$generation;processCreatedAt=$sup.createdAt;executableSha256=$sup.sha256}}
    $script:health.helperRuntime.processIdentity=@{pid=20;createdAt=$agent.createdAt}
    $script:health.helperRuntime.executableSha256=$agent.sha256
    $script:health.discoveryErrors=@();$script:health.runtimes=@()
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $true} 'bridge discovery not verified'
    $directory=Join-Path $context.InstallRoot 'runtimes';[void][IO.Directory]::CreateDirectory($directory)
    Write-PocketJson (Join-Path $directory 'fixture.json') @{protocol=1;runtimeId='fixture-runtime';pid=25}
    $script:health.runtimes=@(@{id='fixture-runtime';source='desktop';online=$true;bridgeVersion='0.6.3'})
    Assert (Test-PocketInstalledRuntime $context.InstallRoot $state $generation $true) 'New authenticated bridge not accepted'
    $script:health.runtimes[0].bridgeVersion='old-version'
    Fails {Test-PocketInstalledRuntime $context.InstallRoot $state $generation $true} 'bridge discovery not verified'
}
Test 'journal process identity still alive prevents supersession' {
    Legacy-Group
    Fails {Assert-PocketJournalOwnersExited @{request=@{child=$script:identities[20]}}} 'owner exit unverified'
}
Test 'real disposable process validates identity on the termination handle' {
    # Only these newly spawned fixture PIDs are used by the native process API.
    $scriptPath=Join-Path $testRoot 'disposable-process.ps1'
    Put $scriptPath 'Start-Sleep -Seconds 60'
    $shell=(Get-Process -Id $PID).Path
    $child=Start-Process -FilePath $shell -ArgumentList @('-NoProfile','-NonInteractive','-File',('"'+$scriptPath+'"')) -WindowStyle Hidden -PassThru
    try {
        Initialize-PocketInstallProcessApi
        $identity=[PocketInstallProcess]::Capture($child.Id)
        Assert ($identity.pid -eq $child.Id) 'Fixture PID capture failed'
        Fails {[PocketInstallProcess]::Stop($identity.pid,($identity.createdAt+1),$identity.executable)} 'identity changed'
        Assert (-not $child.HasExited) 'Mismatched creation time killed a process'
        [PocketInstallProcess]::Stop($identity.pid,$identity.createdAt,$identity.executable)
        Assert ($child.WaitForExit(3000)) 'Exact fixture identity did not exit'
    } finally {if(-not $child.HasExited){$child.Kill()};$child.Dispose()}
}
Test 'kernel TCP owner is verified for a real disposable loopback listener' {
    Initialize-PocketInstallProcessApi
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try {
        $listener.Start();$port=$listener.LocalEndpoint.Port
        Assert ([PocketInstallProcess]::LoopbackListenerOwner($port) -eq $PID) 'Kernel returned the wrong listener PID'
    } finally {$listener.Stop()}
}
Test 'Setup copy requires automatic verification and does not advise logoff' {
    $builder=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'build-windows-installer.ps1'))
    Assert ($builder.Contains('后台已自动重启并通过新代次与本机认证核验')) 'Setup claims files-only completion'
    Assert (-not $builder.Contains('退出并重新登录 Windows')) 'Setup still delegates restart to the user'
}
Write-Output ("$script:passed lifecycle tests passed; fixtures retained at $testRoot")
