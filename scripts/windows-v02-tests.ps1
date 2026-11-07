#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'windows-install.ps1')
. (Join-Path $PSScriptRoot 'build-release.ps1')
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('pocket-v02-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$script:passed=0; $script:failed=0
function Assert($Value,[string]$Message) { if(-not $Value){throw $Message} }
function Test([string]$Name,[scriptblock]$Body) {
    try { & $Body; $script:passed++; Write-Output ('PASS '+$Name) }
    catch { $script:failed++; Write-Output ('FAIL '+$Name+': '+$_.Exception.Message) }
}
function Fails([scriptblock]$Body,[string]$Pattern) {
    try { & $Body | Out-Null } catch { Assert ($_.Exception.Message -match $Pattern) ('Unexpected failure: '+$_.Exception.Message); return }
    throw ('Expected failure: '+$Pattern)
}
function Put([string]$Path,[string]$Text) {
    Assert (([IO.Path]::GetFullPath($Path)).StartsWith($testRoot+'\',[StringComparison]::OrdinalIgnoreCase)) 'Write escaped temp fixture'
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}
# Replace every operating-system integration boundary before invoking installation.
$script:fakeCli='C:\original fixture\codex.exe'; $script:fakeKind='ExpandString'
$script:fakeTask=$null; $script:starts=0; $script:registrations=0
function Get-PocketUserCliPath { return $script:fakeCli }
function Set-PocketUserCliPath([AllowNull()]$Value,[string]$Kind='String') { $script:fakeCli=$Value; $script:fakeKind=$Kind }
function Get-PocketUserCliKind { return $script:fakeKind }
function Send-PocketEnvironmentChanged { }
function Get-PocketTaskXml { return $script:fakeTask }
function Set-PocketTaskXml([AllowNull()]$Xml) { $script:fakeTask=$Xml; $script:registrations++ }
function Start-PocketTask { $script:starts++ }
function Assert-PocketWindowsPrerequisites { }
function Get-PocketDesktopPackage { return [pscustomobject]@{InstallLocation=(Join-Path $testRoot 'native');PackageFamilyName='fixture'} }

Test 'canonical LocalAppData ignores a virtualized process environment' {
    $old=$env:LOCALAPPDATA
    try {
        $env:LOCALAPPDATA=Join-Path $testRoot 'Packages\fixture\LocalCache\Local'
        $canonical=Get-PocketLocalAppData
        Assert ($canonical -ne $env:LOCALAPPDATA) 'Canonical lookup followed package environment'
        Assert ([IO.Path]::IsPathRooted($canonical)) 'Canonical lookup is not absolute'
    } finally { $env:LOCALAPPDATA=$old }
}
Test 'native installation paths reject UNC and alternate data streams' {
    Fails { Assert-PocketLocalPath '\\server\share\Pocket' } 'local drive'
    Fails { Assert-PocketLocalPath ((Join-Path $testRoot 'state')+':stream') } 'stream'
}
Test 'desktop build runs locked npm install, tests, frontend build, then standalone Cargo Release' {
    $root=Join-Path $testRoot 'build'
    Put (Join-Path $root 'desktop\Cargo.toml') '[workspace]'
    $script:steps=@(); $script:failStep=''
    function script:npm.cmd { $step='npm '+($args -join ' '); $script:steps+=@{step=$step;cwd=(Get-Location).Path}; $global:LASTEXITCODE=if($script:failStep -eq $step){23}else{0} }
    function script:cargo { $script:steps+=@{step=('cargo '+($args -join ' '));cwd=(Get-Location).Path}; $global:LASTEXITCODE=if($script:failStep -eq 'cargo'){27}else{0} }
    Invoke-PocketDesktopBuild $root 'x86_64-pc-windows-msvc'
    Assert ($script:steps.Count -eq 4) 'Expected npm ci/test/build followed by cargo'
    Assert ($script:steps[0].step -like 'npm ci *') 'npm lock not used'
    Assert ($script:steps[1].step -eq 'npm test') 'Frontend tests skipped'
    Assert ($script:steps[2].step -eq 'npm run build') 'Frontend not built'
    Assert ($script:steps[3].step -match '--manifest-path .*desktop\\Cargo.toml') 'Standalone manifest not explicit'
    Assert ($script:steps[3].step -match '--release.*--locked') 'Cargo release/lock flags missing'
    Assert ($script:steps[3].step -notmatch '--target ') 'Desktop output must be desktop/target/release'
    foreach($step in $script:steps){Assert ($step.cwd -eq (Join-Path $root 'desktop')) 'Build escaped desktop workspace'}
}
Test 'each desktop failure stops subsequent commands and restores working directory' {
    $root=Join-Path $testRoot 'build'; $before=(Get-Location).Path
    foreach($case in @(@('npm ci --no-audit --no-fund',1),@('npm test',2),@('npm run build',3),@('cargo',4))) {
        $script:steps=@(); $script:failStep=$case[0]
        Fails { Invoke-PocketDesktopBuild $root 'x86_64-pc-windows-msvc' } 'failed'
        Assert ($script:steps.Count -eq $case[1]) 'Failed build continued'
        Assert ((Get-Location).Path -eq $before) 'Build did not restore cwd'
    }
    $script:failStep=''
}
$root=Join-Path $testRoot 'package-input'
Put (Join-Path $testRoot 'native\app\resources\codex.exe') 'never execute'
foreach($name in Get-PocketWindowsFiles) {
    if($name -in @('pocket-desktop.exe','pocket-supervisor.exe','pocket-launcher.exe')){$path=Join-Path $root ('desktop\target\release\'+$name)}
    elseif($name -like '*.exe'){$path=Join-Path $root ('target\release\'+$name)}
    elseif($name -like '*.md'){$path=Join-Path $root ('docs\'+$name)}
    else{$path=Join-Path $root ('scripts\'+$name)}
    Put $path ('fixture '+$name)
}
Put (Join-Path $root 'app.apk') 'synthetic apk'
$package=Join-Path $testRoot 'release'
Test 'package defaults desktop binaries to independent desktop target/release' {
    New-PocketReleasePackage -Root $root -BinaryDirectory (Join-Path $root 'target\release') -ApkPath (Join-Path $root 'app.apk') -OutputDirectory $package -Version '0.2.0' -BuildEvidence @{fixture=$true} | Out-Null
    Test-PocketReleaseDirectory $package | Out-Null
    Assert ('V02-WINDOWS-INSTALL.md' -in (Get-PocketWindowsFiles)) '0.2 installation guide absent'
}
# Make a standalone synthetic verified delivery so installer regressions run even
# when the packaging default above has not been implemented yet.
$source=Join-Path $testRoot 'delivery'
foreach($name in Get-PocketWindowsFiles){Put (Join-Path $source $name) ('fixture '+$name)}
Write-PocketJson (Join-Path $source 'windows-manifest.json') @{schema=1;version='0.2.0';files=(Get-PocketInventory $source (Get-PocketWindowsFiles))}
$state=Join-Path $testRoot 'state'
Put (Join-Path $state 'config.json') '{"protocol":1}'
Put (Join-Path $state 'devices.json') '{"fixture":"paired device survives"}'
foreach($name in @('certificate.der','private-key.der','identity.lock')) {Put (Join-Path $state ('identity\'+$name)) ('fixture '+$name)}
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
foreach($name in @('certificate.der','private-key.der','identity.lock','')) {
    $path=Join-Path $state ('identity\'+$name)
    $acl=if($name){[Security.AccessControl.FileSecurity]::new()}else{[Security.AccessControl.DirectorySecurity]::new()}
    $acl.SetSecurityDescriptorSddlForm(('O:'+ $sid +'D:P(A;;FA;;;'+$sid+')'))
    Set-Acl -LiteralPath $path -AclObject $acl
}
$identityBefore=@{}
foreach($item in Get-ChildItem -LiteralPath $state -Recurse){$identityBefore[$item.FullName]=@{hash=$(if(-not $item.PSIsContainer){Get-PocketFileHash $item.FullName}else{$null});acl=(Get-Acl -LiteralPath $item.FullName).Sddl}}
$context=@{InstallRoot=(Join-Path $testRoot 'install');Programs=(Join-Path $testRoot 'programs');Startup=(Join-Path $testRoot 'startup')}
$installArgs=@{Context=$context;SourceDirectory=$source;StateDirectory=$state}
Test 'v3 record and supervisor task match native launcher contract without starting processes' {
    Invoke-PocketWindowsInstall @installArgs -Apply | Out-Null
    $record=Get-Content (Join-Path $context.InstallRoot 'desktop-install.json') -Raw | ConvertFrom-Json
    Assert ($record.version -eq 3) 'Native launcher requires record v3'
    Assert ($record.generation -cmatch '^[a-f0-9]{64}$') 'Invalid generation'
    Assert ($record.releaseHash -eq (Get-PocketFileHash (Join-Path $source 'windows-manifest.json'))) 'Manifest hash differs'
    Assert ($record.generation -eq $record.releaseHash) 'Generation is not manifest hash'
    Assert ($script:fakeCli -eq (Join-Path $context.InstallRoot 'pocket-launcher.exe')) 'CLI does not point directly to stable launcher'
    Assert ($record.stateDirectory -eq $state) 'State changed'
    $task=([xml]$script:fakeTask).Task
    Assert ($task.Actions.Exec.Arguments -eq '--pocket-supervise') 'Task launches UI instead of supervisor'
    Assert ($task.Principals.Principal.RunLevel -eq 'LeastPrivilege') 'Task needs elevation'
    Assert ($task.Principals.Principal.UserId -eq $sid) 'Task is not current-user scoped'
    Assert ($script:starts -eq 0) 'Install started a task'
}
Test 'UI startup is independent, optional, idempotent and reversible' {
    Invoke-PocketWindowsInstall @installArgs -UiStartup Enable -Apply | Out-Null
    $link=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $context.Startup 'Codex Pocket.lnk'))
    Assert ($link.Arguments -eq '--pocket-ui') 'UI startup does not use stable UI mode'
    $recordHash=Get-PocketFileHash (Join-Path $context.InstallRoot 'desktop-install.json')
    Invoke-PocketWindowsInstall @installArgs -Apply | Out-Null
    Assert ((Get-PocketFileHash (Join-Path $context.InstallRoot 'desktop-install.json')) -eq $recordHash) 'Default apply lost UI preference'
    $startup=Invoke-PocketStartup -Action Disable -InstallRoot $context.InstallRoot
    Assert (-not $startup.enabled) 'Supervisor logon not disabled'
    Assert ([IO.File]::Exists((Join-Path $context.Startup 'Codex Pocket.lnk'))) 'Supervisor setting removed UI startup'
    Invoke-PocketWindowsInstall @installArgs -UiStartup Disable -Apply | Out-Null
    Assert (-not [IO.File]::Exists((Join-Path $context.Startup 'Codex Pocket.lnk'))) 'UI startup not removed'
    Assert (-not (Test-PocketTaskLogon $script:fakeTask)) 'Apply reenabled supervisor logon'
}
Test 'startup status/start/enable/disable resolve the recorded installation independently of state' {
    $before=$script:starts
    $status=Invoke-PocketStartup -Action Status -InstallRoot $context.InstallRoot
    Assert ($status.registered -and -not $status.enabled -and $script:starts -eq $before) 'Status changed or misreported startup'
    $status=Invoke-PocketStartup -Action Start -InstallRoot $context.InstallRoot
    Assert ($script:starts -eq ($before+1) -and -not $status.enabled) 'Start changed logon preference or did not run fixture task'
    $status=Invoke-PocketStartup -Action Enable -InstallRoot $context.InstallRoot
    Assert ($status.enabled) 'Enable did not create logon trigger'
    $status=Invoke-PocketStartup -Action Disable -InstallRoot $context.InstallRoot
    Assert (-not $status.enabled -and $script:starts -eq ($before+1)) 'Disable started a task or left logon enabled'
    $registrations=$script:registrations
    Invoke-PocketWindowsInstall @installArgs -Apply | Out-Null
    Assert ($registrations -eq $script:registrations) 'Idempotent install registered the existing task again'
}
Test 'uninstall restores original environment registry kind as well as value' {
    Invoke-PocketWindowsInstall @installArgs -Uninstall -Apply | Out-Null
    Assert ($script:fakeCli -eq 'C:\original fixture\codex.exe') 'Environment value changed'
    Assert ($script:fakeKind -eq 'ExpandString') 'Original environment registry kind lost'
}
Test 'v2 migration rolls back exact original record, env and all three links' {
    $legacy=@{InstallRoot=(Join-Path $testRoot 'legacy');Programs=(Join-Path $testRoot 'legacy-programs');Startup=(Join-Path $testRoot 'legacy-startup')}
    $legacyGeneration='a'*64; $bridge=Join-Path $legacy.InstallRoot ('bin\'+$legacyGeneration+'\pocket-bridge.exe')
    Put $bridge 'legacy bridge'
    $entries=@()
    foreach($path in @((Join-Path $legacy.Programs 'Codex Pocket.lnk'),(Join-Path $legacy.Programs 'Codex Pocket Desktop.lnk'),(Join-Path $legacy.Startup 'Codex Pocket.lnk'))) {
        Put $path ('legacy link '+$entries.Count)
        $backup=Get-PocketBackup $path
        $entries+=@{path=$path;original=$null;originalHash=$null;installed=$backup.bytes;installedHash=$backup.hash}
    }
    $manifest=Join-Path $legacy.InstallRoot 'desktop-install.json'
    Write-PocketJson $manifest @{version=2;installRoot=$legacy.InstallRoot;generation=$legacyGeneration;releaseHash=('b'*64);bridge=$bridge;previousCliPath=$null;stateDirectory=$state;shortcuts=$entries}
    $hash=Get-PocketFileHash $manifest; $script:fakeCli=$bridge; $script:fakeKind='ExpandString'; $script:fakeTask=$null
    Invoke-PocketWindowsInstall -Context $legacy -SourceDirectory $source -Apply | Out-Null
    Assert (-not [IO.File]::Exists($entries[2].path)) 'Old helper startup link not removed'
    Invoke-PocketWindowsInstall -Context $legacy -Rollback -Apply | Out-Null
    Assert ((Get-PocketFileHash $manifest) -eq $hash) 'Legacy record changed'
    Assert ($script:fakeCli -eq $bridge -and $script:fakeKind -eq 'ExpandString') 'Legacy CLI not restored exactly'
    Assert ($null -eq $script:fakeTask) 'New supervisor task remains after rollback'
    foreach($entry in $entries){Assert ((Get-PocketFileHash $entry.path) -eq $entry.installedHash) 'Legacy shortcut not byte-exact'}
}
Test 'all installation and rollback operations preserve pairing bytes and ACLs' {
    foreach($path in $identityBefore.Keys){
        Assert ((Get-Acl -LiteralPath $path).Sddl -eq $identityBefore[$path].acl) 'Pairing ACL changed'
        if([IO.File]::Exists($path)){Assert ((Get-PocketFileHash $path) -eq $identityBefore[$path].hash) 'Pairing state changed'}
    }
}
Test 'reused immutable generation must match the recorded manifest bytes' {
    $record=Get-Content (Join-Path $context.InstallRoot 'desktop-install.json') -Raw | ConvertFrom-Json
    $manifest=Join-Path $context.InstallRoot ('bin\'+$record.generation+'\windows-manifest.json')
    $bytes=[IO.File]::ReadAllBytes($manifest)
    $script:fakeCli=$record.previousCliPath; $script:fakeKind=$record.previousCliKind; $script:fakeTask=$null
    try {
        Put $manifest ([Text.Encoding]::UTF8.GetString($bytes)+' ')
        $cliBefore=$script:fakeCli
        Fails { Invoke-PocketWindowsInstall @installArgs -Apply } 'manifest.*hash|generation'
        Assert ($script:fakeCli -eq $cliBefore) 'Tampered manifest changed integration'
    } finally { [IO.File]::WriteAllBytes($manifest,$bytes) }
}
Test 'raw empty and expandable environment values survive install and uninstall' {
    foreach($value in @('', '%USERPROFILE%\custom\codex.exe')) {
        $suffix=[guid]::NewGuid().ToString('N')
        $envContext=@{InstallRoot=(Join-Path $testRoot ('env-'+$suffix));Programs=(Join-Path $testRoot ('programs-'+$suffix));Startup=(Join-Path $testRoot ('startup-'+$suffix))}
        $script:fakeCli=$value; $script:fakeKind='ExpandString'; $script:fakeTask=$null
        Invoke-PocketWindowsInstall -Context $envContext -SourceDirectory $source -StateDirectory $state -Apply | Out-Null
        Invoke-PocketWindowsInstall -Context $envContext -Uninstall -Apply | Out-Null
        Assert ($null -ne $script:fakeCli -and $script:fakeCli -ceq $value -and $script:fakeKind -eq 'ExpandString') 'Raw environment state not exact'
    }
}
Test 'interrupted registration accepts scheduler defaults and restores exact prior snapshot' {
    $root=Join-Path $testRoot 'interrupted'
    [void][IO.Directory]::CreateDirectory($root)
    $manifest=Join-Path $root 'desktop-install.json'; $launcher=Join-Path $root 'pocket-launcher.exe'
    $links=@((Join-Path $root 'a.lnk'),(Join-Path $root 'b.lnk'),(Join-Path $root 'c.lnk'))
    $script:fakeCli='%USERPROFILE%\old.exe'; $script:fakeKind='ExpandString'; $script:fakeTask=$null
    $before=Get-PocketIntegrationSnapshot $manifest $links
    $after=@{manifest=$before.manifest;links=$before.links;cli=$launcher;cliKind='String';task=(New-PocketTaskXml $launcher $true)}
    $script:fakeTask=$after.task.Replace('</Settings>','<Priority>7</Priority><RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable></Settings>')
    $script:fakeCli=$launcher; $script:fakeKind='String'
    Restore-PocketTransaction @{before=$before;after=$after} $manifest $links $launcher
    Assert ($null -eq $script:fakeTask) 'Interrupted task not removed'
    Assert ($script:fakeCli -ceq $before.cli -and $script:fakeKind -eq 'ExpandString') 'Interrupted environment not restored'
}
Test 'recovery never overwrites a third party task edit' {
    $root=Join-Path $testRoot 'interrupted'
    $manifest=Join-Path $root 'desktop-install.json'; $launcher=Join-Path $root 'pocket-launcher.exe'
    $links=@((Join-Path $root 'a.lnk'),(Join-Path $root 'b.lnk'),(Join-Path $root 'c.lnk'))
    $script:fakeTask=$null
    $before=Get-PocketIntegrationSnapshot $manifest $links
    $after=@{manifest=$before.manifest;links=$before.links;cli=$launcher;cliKind='String';task=(New-PocketTaskXml $launcher $true)}
    $script:fakeTask=$after.task.Replace('<Count>999</Count>','<Count>2</Count>')
    $changed=$script:fakeTask
    Fails { Restore-PocketTransaction @{before=$before;after=$after} $manifest $links $launcher } 'task.*drift'
    Assert ($script:fakeTask -ceq $changed) 'Recovery replaced external task edit'
}
Test 'registered scheduler omission of defaults and account-name trigger remains owned' {
    $launcher='C:\fixture\pocket-launcher.exe'
    $user=[Security.Principal.WindowsIdentity]::GetCurrent()
    $xml=New-PocketTaskXml $launcher $true
    $xml=$xml.Replace('<RunLevel>LeastPrivilege</RunLevel>','').Replace('<Enabled>true</Enabled>','')
    $xml=$xml.Replace('<LogonTrigger><UserId>'+$user.User.Value+'</UserId>','<LogonTrigger><UserId>'+[Security.SecurityElement]::Escape($user.Name)+'</UserId>')
    Assert-PocketTaskOwned $xml $launcher
    Assert (Test-PocketTaskLogon $xml) 'Implicit default enabled logon trigger was ignored'
    Fails { Assert-PocketTaskOwned ($xml.Replace('<LogonType>InteractiveToken</LogonType>','<LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel>')) $launcher } 'drift'
    Fails { Assert-PocketTaskOwned ($xml.Replace('<LogonTrigger>','<LogonTrigger><Enabled>false</Enabled>')) $launcher } 'drift'
}
Test 'repeated upgrades keep bootstrap record bounded with exact verified rollback' {
    $small=@{InstallRoot=(Join-Path $testRoot 'bounded-install');Programs=(Join-Path $testRoot 'bounded-programs');Startup=(Join-Path $testRoot 'bounded-startup')}
    $script:fakeCli='C:\original fixture\codex.exe';$script:fakeKind='String';$script:fakeTask=$null
    $argsBounded=@{Context=$small;SourceDirectory=$source;StateDirectory=$state;Apply=$true}
    Invoke-PocketWindowsInstall @argsBounded | Out-Null
    $recordPath=Join-Path $small.InstallRoot 'desktop-install.json'
    for($upgrade=0;$upgrade -lt 24;$upgrade++) {
        $previous=Get-PocketFileHash $recordPath
        Invoke-PocketWindowsInstall @argsBounded -UiStartup $(if($upgrade%2 -eq 0){'Enable'}else{'Disable'}) | Out-Null
        Assert ((Get-Item -LiteralPath $recordPath).Length -lt 128KB) 'Repeated integration grew the bootstrap record'
        $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
        $snapshot=Read-PocketRollbackSnapshot $record.rollback $small.InstallRoot
        Assert ($snapshot.manifest.hash -eq $previous) 'Previous installation was not retained exactly'
        Assert-PocketBackup $snapshot.manifest
    }
    # Repair an already oversized legacy record without updating the stable launcher.
    $legacyBytes=[IO.File]::ReadAllText($recordPath)+(' '*1MB)
    [IO.File]::WriteAllText($recordPath,$legacyBytes,[Text.UTF8Encoding]::new($false))
    $legacyHash=Get-PocketFileHash $recordPath
    Invoke-PocketWindowsInstall @argsBounded | Out-Null
    Assert ((Get-Item -LiteralPath $recordPath).Length -lt 128KB) 'Oversized same-generation record was not repaired'
    $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    $archivePath=Join-Path (Join-Path $small.InstallRoot 'rollback') $record.rollback.manifest.archiveFile
    Assert ((Get-PocketFileHash $archivePath) -eq $legacyHash) 'Legacy snapshot bytes lost'
    $archiveBytes=[IO.File]::ReadAllBytes($archivePath)
    try {
        [IO.File]::WriteAllText($archivePath,'tampered')
        Fails { Invoke-PocketWindowsInstall -Context $small -Rollback -Apply } 'integrity'
    } finally { [IO.File]::WriteAllBytes($archivePath,$archiveBytes) }
    Invoke-PocketWindowsInstall -Context $small -Rollback -Apply | Out-Null
    Assert ((Get-PocketFileHash $recordPath) -eq $legacyHash) 'Rollback failed to restore legacy record exactly'
}
Write-Output ("$script:passed passed; $script:failed failed; fixtures: $testRoot")
if($script:failed){exit 1}
