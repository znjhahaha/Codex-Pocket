#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$testRoot = Join-Path $PSScriptRoot ('..\.local\windows-readiness-tests\run-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$testRoot = [IO.Path]::GetFullPath($testRoot)
$script:passed = 0
function Assert($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Expect-Failure([scriptblock]$Action, [string]$Pattern) {
    try { & $Action | Out-Null } catch {
        Assert ($_.Exception.Message -match $Pattern) ('Wrong failure: ' + $_.Exception.Message)
        return
    }
    throw ('Expected failure: ' + $Pattern)
}
function Test([string]$Name, [scriptblock]$Action) {
    & $Action
    $script:passed++
    Write-Output ('PASS ' + $Name)
}
function Put([string]$Path, [string]$Text) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

# All integration state is redirected by these function boundaries. No native
# executable, registry mutation, desktop, tray, helper or build is invoked.
. (Join-Path $PSScriptRoot 'windows-common.ps1')
. (Join-Path $PSScriptRoot 'windows-install.ps1')
. (Join-Path $PSScriptRoot 'build-release.ps1')
function Get-PocketUserCliPath { return $script:fakeCliPath }
function Set-PocketUserCliPath([AllowNull()]$Value,[string]$Kind='String') { $script:fakeCliPath=$Value; $script:fakeCliKind=$Kind }
function Get-PocketUserCliKind { return $script:fakeCliKind }
function Send-PocketEnvironmentChanged { $script:broadcasts++ }
function Get-PocketDesktopPackage { return $script:fakePackage }
$script:fakeTask=$null
$script:taskFailure=$false
$script:taskReadFailure=$false
$script:taskFailureAfterWrite=$false
$script:taskWrites=0
$script:shortcutFailure=$false
$script:shortcutWrites=0
$script:realShortcutBytes=${function:New-PocketShortcutBytes}
function Get-PocketTaskXml {
    if($script:taskReadFailure){Invoke-PocketInstallStep 'startup_read' 'ITaskFolder.GetTask' {throw [Runtime.InteropServices.COMException]::new('fixture-secret-task-read',-2147024891)}}
    return $script:fakeTask
}
function Set-PocketTaskXml([AllowNull()]$Xml) {
    $script:taskWrites++
    if($script:taskFailureAfterWrite){$script:taskFailureAfterWrite=$false;$script:fakeTask=$Xml;$script:taskReadFailure=$true;Invoke-PocketInstallStep 'startup_register' 'ITaskFolder.RegisterTask' {throw [Runtime.InteropServices.COMException]::new('fixture-secret-unknown-outcome',-2147467259)}}
    if($script:taskFailure){$script:taskFailure=$false;Invoke-PocketInstallStep 'startup_register' 'ITaskFolder.RegisterTask' {throw [Runtime.InteropServices.COMException]::new('injected task registration failure',-2147467259)}}
    $script:fakeTask=$Xml
}
function New-PocketShortcutBytes([string]$Temporary,[string]$Launcher,[string]$Mode,[string]$IconPath) {
    $script:shortcutWrites++
    if($script:shortcutFailure){Invoke-PocketInstallStep 'shortcut' 'IPersistFile.Save' {throw [Runtime.InteropServices.COMException]::new('fixture-secret-shortcut',-2147467259)}}
    & $script:realShortcutBytes @PSBoundParameters
}
function Invoke-PocketDesktopBuild([string]$Root,[string]$Target) { $script:desktopBuilt=$true }
function Assert-PocketWindowsPrerequisites { } # Host runtime availability is not a fixture prerequisite.


Test 'shared path helpers reject extended and device namespaces before filesystem access' {
    $ordinary = Join-Path $testRoot 'ordinary child'
    Assert ((Get-PocketDirectoryPath ($testRoot + '\.\unused\..\ordinary child\')) -eq $ordinary) 'Ordinary Win32 normalization changed'
    Assert ((Get-PocketDirectoryPath $ordinary.Replace('\','/')) -eq $ordinary) 'Ordinary forward separators changed'
    Assert ((Get-PocketDirectoryPath '\\server\share\folder') -eq '\\server\share\folder') 'Ordinary UNC directory rejected'
    Assert-PocketPlainPath $ordinary
    $aliases = @(
        ('\\?\' + $ordinary), ('\\.\' + $ordinary), ('\??\' + $ordinary), ('\\??\' + $ordinary),
        ('//?/' + $ordinary.Replace('\','/')), ('//./' + $ordinary.Replace('\','/')),
        ('/??/' + $ordinary.Replace('\','/')), ('/\?/' + $ordinary),
        '\\?\UNC\server\share\folder', '\\?\GLOBALROOT\Device\fixture', '\\.\pipe\fixture'
    )
    foreach ($alias in $aliases) {
        Expect-Failure { Get-PocketDirectoryPath $alias } 'Unsupported Windows path namespace'
        Expect-Failure { Assert-PocketPlainPath $alias } 'Unsupported Windows path namespace'
    }
}

$script:fakeCliPath = 'C:\original fixture\codex.exe'
$script:fakeCliKind = 'String'
$script:broadcasts = 0
$native = Join-Path $testRoot 'native'
Put (Join-Path $native 'app\resources\codex.exe') 'native fixture; never execute'
Put (Join-Path $native 'app\Codex.exe') 'desktop fixture; never execute'
$script:fakePackage = [pscustomobject]@{InstallLocation=$native;PackageFamilyName='fixture-family'}
$binarySource = Join-Path $testRoot 'binaries'
Put (Join-Path $binarySource 'pocket-agent.exe') 'agent fixture; never execute'
Put (Join-Path $binarySource 'pocket-bridge.exe') 'bridge fixture; never execute'
foreach($name in @('pocket-desktop.exe','pocket-supervisor.exe','pocket-launcher.exe','pocket-upgrade.exe','pocket-runtime-host.exe','pocket-p2p.dll','datachannel.dll')) { Put (Join-Path $binarySource $name) ($name+' fixture; never execute') }
Put (Join-Path $binarySource 'private-key.der') 'must never ship'
$apk = Join-Path $testRoot 'synthetic.apk'
Put $apk 'unsigned fixture; never install'
$packageRoot = Join-Path $testRoot 'release'
$packageInputs=Join-Path $testRoot 'package-inputs'
foreach($name in Get-PocketWindowsFiles) {
    if($name -in @('cloudflared.exe','cloudflared-LICENSE.txt')){Put (Join-Path $packageInputs ('.local\tools\'+$name)) ('fixture '+$name)}
    elseif($name -notlike '*.exe' -and $name -notlike '*.dll') {
        $folder=if($name -like '*.md'){'docs'}else{'scripts'}
        Put (Join-Path $packageInputs ($folder+'\'+$name)) ([IO.File]::ReadAllText((Join-Path $PSScriptRoot ('..\'+$folder+'\'+$name))))
    }
}


Test 'package allowlist and complete SHA256 inventory' {
    New-PocketReleasePackage -Root $packageInputs -BinaryDirectory $binarySource -DesktopBinaryDirectory $binarySource -ApkPath $apk -OutputDirectory $packageRoot -Version '0.1.0' -BuildEvidence @{builtThisInvocation=$false;apkSignatureVerified=$false;fixture=$true} | Out-Null
    $manifest = Test-PocketReleaseDirectory $packageRoot
    Assert ($manifest.build.apkSignatureVerified -eq $false) 'Fixture was incorrectly marked signed'
    Assert (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'windows\private-key.der'))) 'Private data shipped'
    Assert ($manifest.files.Count -ge 13) 'Required delivery artifacts missing'
    Expect-Failure { New-PocketReleasePackage -Root $packageInputs -BinaryDirectory $binarySource -ApkPath $apk -OutputDirectory $packageRoot -Version '0.1.0' -BuildEvidence @{fixture=$true} } 'already exists'
    Expect-Failure { New-PocketReleasePackage -Root $packageInputs -BinaryDirectory $binarySource -ApkPath $apk -OutputDirectory (Join-Path $testRoot 'unbuilt') -Version '0.1.0' -BuildEvidence @{} } 'requires a fresh build'
}

$source = Join-Path $packageRoot 'windows'
$context = @{InstallRoot=(Join-Path $testRoot 'installed');Programs=(Join-Path $testRoot 'Programs');Startup=(Join-Path $testRoot 'Startup')}
$state = Join-Path $testRoot 'state with spaces'
$argsInstall = @{SourceDirectory=$source;StateDirectory=$state;Context=$context}
Test 'invalid state fails before any installation writes' {
    Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Apply } 'initialized|identity'
    Assert (-not (Test-Path -LiteralPath $context.InstallRoot)) 'Partial install left files'
    Assert ($script:fakeCliPath -eq 'C:\original fixture\codex.exe') 'Environment changed on preflight failure'
}
Put (Join-Path $state 'config.json') '{"protocol":1}'
Put (Join-Path $state 'identity\certificate.der') 'synthetic public identity'
Put (Join-Path $state 'identity\private-key.der') 'synthetic private identity'
Put (Join-Path $state 'identity\identity.lock') ''
# Synthetic installation fixtures must obey the same owner/ACL policy as TLS.
# Protect children before removing inheritable grants from their parent.
$identitySid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
foreach ($name in @('certificate.der','private-key.der','identity.lock')) {
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetSecurityDescriptorSddlForm(('O:' + $identitySid + 'D:P(A;;FA;;;' + $identitySid + ')'))
    Set-Acl -LiteralPath (Join-Path $state ('identity\' + $name)) -AclObject $acl
}
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetSecurityDescriptorSddlForm(('O:' + $identitySid + 'D:P(A;;FA;;;' + $identitySid + ')'))
Set-Acl -LiteralPath (Join-Path $state 'identity') -AclObject $acl
$shortcut = Join-Path $context.Programs 'Codex Pocket.lnk'
Put $shortcut 'pre-existing shortcut exact bytes'
$originalShortcut = [IO.File]::ReadAllBytes($shortcut)
Test 'extended-path migration overlap fails without copying or integration writes' {
    # The helper-policy test above fails safely first if namespace rejection is
    # removed; never run the old recursively self-copying migration unchecked.
    $destination = Join-Path $state 'migrated'
    $extendedSource = '\\?\' + $state
    $extendedDestination = '\\?\' + $destination
    $filesBefore = @{}
    $aclsBefore = @{}
    $itemsBefore = @((Get-Item -LiteralPath $state)) + @(Get-ChildItem -LiteralPath $state -Recurse -Force)
    foreach ($item in $itemsBefore) {
        $aclsBefore[$item.FullName] = (Get-Acl -LiteralPath $item.FullName).Sddl
        if (-not $item.PSIsContainer) { $filesBefore[$item.FullName] = Get-PocketFileHash $item.FullName }
    }
    $cliBefore = $script:fakeCliPath
    $broadcastsBefore = $script:broadcasts
    Assert (-not (Test-Path -LiteralPath (Join-Path $state 'agent.lock'))) 'Fixture unexpectedly has a source lock'
    Assert-PocketInitializedState $state
    foreach ($paths in @(
        @{Source=$state;Destination=$extendedDestination},
        @{Source=$extendedSource;Destination=$destination},
        @{Source=$extendedSource;Destination=$extendedDestination}
    )) {
        Expect-Failure { Copy-PocketState @paths } 'Unsupported Windows path namespace'
        foreach ($applyMigration in @($false,$true)) {
            Expect-Failure {
                Invoke-PocketWindowsInstall -SourceDirectory $source -ExistingStateDirectory $paths.Source -StateDirectory $paths.Destination -Context $context -Apply:$applyMigration
            } 'Unsupported Windows path namespace'
        }
    }
    # Ordinary normalized descendant paths must still hit the overlap guard.
    Expect-Failure { Copy-PocketState $state ($state + '\unused\..\migrated') } 'cannot overlap'
    $itemsAfter = @((Get-Item -LiteralPath $state)) + @(Get-ChildItem -LiteralPath $state -Recurse -Force)
    Assert ($itemsAfter.Count -eq $itemsBefore.Count) 'Migration created source lock/staging or removed source entries'
    foreach ($item in $itemsAfter) {
        Assert ($aclsBefore.ContainsKey($item.FullName)) 'Migration created an unexpected source entry'
        Assert ((Get-Acl -LiteralPath $item.FullName).Sddl -eq $aclsBefore[$item.FullName]) 'Source ACL changed'
        if (-not $item.PSIsContainer) { Assert ((Get-PocketFileHash $item.FullName) -eq $filesBefore[$item.FullName]) 'Source bytes changed' }
    }
    Assert (-not (Test-Path -LiteralPath $destination)) 'Migration published a destination'
    Assert (-not (Test-Path -LiteralPath (Join-Path $state 'agent.lock'))) 'Rejected migration created a source lock'
    Assert (-not (Test-Path -LiteralPath $context.InstallRoot)) 'Rejected migration wrote an installation'
    Assert ((Get-PocketFileHash $shortcut) -eq (Get-PocketBytesHash $originalShortcut)) 'Rejected migration changed an existing shortcut'
    Assert (@(Get-ChildItem -LiteralPath $context.Programs -Force).Count -eq 1) 'Rejected migration created a shortcut'
    Assert (-not (Test-Path -LiteralPath $context.Startup)) 'Rejected migration wrote startup integration'
    Assert ($script:fakeCliPath -eq $cliBefore -and $script:broadcasts -eq $broadcastsBefore) 'Rejected migration changed environment integration'
    Assert-PocketInitializedState $state
}
Test 'install and uninstall dry-runs are read-only' {
    Invoke-PocketWindowsInstall @argsInstall | Out-Null
    Assert (-not (Test-Path -LiteralPath $context.InstallRoot)) 'Dry-run wrote installation'
    Assert ($script:broadcasts -eq 0) 'Dry-run broadcast environment'
}
Test 'apply, idempotence, and preserved shortcut backup' {
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
    $script:installedCliPath = $script:fakeCliPath
    Assert ([IO.File]::Exists($script:installedCliPath)) 'Bridge not installed'
    $before = (Get-FileHash -LiteralPath (Join-Path $context.InstallRoot 'desktop-install.json')).Hash
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
    Assert ((Get-FileHash -LiteralPath (Join-Path $context.InstallRoot 'desktop-install.json')).Hash -eq $before) 'Idempotent apply rewrote manifest'
    $link = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut)
    Assert ($link.TargetPath -eq $script:installedCliPath -and $link.Arguments -eq '--pocket-ui') 'Stable UI entry point missing'
    Assert (-not (Test-Path -LiteralPath (Join-Path $context.Programs 'Codex Pocket Desktop.lnk'))) 'A second Pocket entry was created'
    Assert ($link.IconLocation -like '*codex-pocket.ico*') 'Pocket shortcut must use its stable brand icon'
    Assert (Test-PocketTaskLogon $script:fakeTask) 'Current-user logon task missing'
    Assert (-not (Test-Path -LiteralPath (Join-Path $context.Startup 'Codex Pocket.lnk'))) 'Legacy startup link was not migrated'
    Invoke-PocketWindowsInstall @argsInstall -Uninstall | Out-Null
    Assert ($script:fakeCliPath -eq $script:installedCliPath) 'Uninstall dry-run changed environment'
}
Test 'shortcut and environment drift refuse overwrite' {
    $installedBytes = [IO.File]::ReadAllBytes($shortcut)
    Put $shortcut 'external edit'
    Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply } 'changed|drift'
    Assert ([IO.File]::ReadAllText($shortcut) -eq 'external edit') 'External shortcut edit lost'
    [IO.File]::WriteAllBytes($shortcut, $installedBytes)
    $script:fakeCliPath = 'external override'
    Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Apply } 'changed|drift'
    $script:fakeCliPath = $script:installedCliPath
}
Test 'shortcut publication refuses an edit made after its snapshot' {
    $path=Join-Path $testRoot 'concurrently edited.lnk'
    Put $path 'owned snapshot'
    $snapshot=Get-PocketBackup $path
    Put $path 'new user edit'
    Expect-Failure { Write-PocketBackup @{path=$path;bytes=$null;hash=$null} -ExpectedHash $snapshot.hash -Compare } 'refusing drift'
    Assert ([IO.File]::ReadAllText($path) -eq 'new user edit') 'User edit was deleted'
}
Test 'matching legacy second entry migrates and rollback restores its exact bytes' {
    $recordPath=Join-Path $context.InstallRoot 'desktop-install.json'
    $record=Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $record.PSObject.Properties.Remove('singleEntry')
    $icon=Join-Path $context.InstallRoot ('resources\'+(Get-PocketFileHash (Join-Path $source 'codex-pocket.ico'))+'\codex-pocket.ico')
    $legacyPath=Join-Path $context.Programs 'Codex Pocket Desktop.lnk'
    $bytes=New-PocketShortcutBytes (Join-Path $testRoot 'legacy-temp.lnk') $script:installedCliPath '--pocket-desktop' $icon
    Write-PocketAtomic $legacyPath $bytes
    $record.shortcuts[1].installed=[Convert]::ToBase64String($bytes)
    $record.shortcuts[1].installedHash=Get-PocketBytesHash $bytes
    Write-PocketJson $recordPath $record
    $legacyRecord=Get-PocketFileHash $recordPath
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
    Assert (-not (Test-Path -LiteralPath $legacyPath)) 'Verified legacy shortcut remains'
    Invoke-PocketWindowsInstall -Context $context -Rollback -Apply | Out-Null
    Assert ((Get-PocketFileHash $recordPath) -eq $legacyRecord) 'Legacy rollback record changed'
    Assert ((Get-PocketFileHash $legacyPath) -eq (Get-PocketBytesHash $bytes)) 'Legacy link did not restore exactly'
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
}
Test 'uninstall restores exact prior shortcut and environment; reapply works' {
    Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply | Out-Null
    Assert ($script:fakeCliPath -eq 'C:\original fixture\codex.exe') 'Prior override lost'
    Assert ((Get-PocketBytesHash ([IO.File]::ReadAllBytes($shortcut))) -eq (Get-PocketBytesHash $originalShortcut)) 'Shortcut restore is not byte-exact'
    Assert (-not (Test-Path -LiteralPath (Join-Path $context.Startup 'Codex Pocket.lnk'))) 'New startup link not removed'
    Assert (Test-Path -LiteralPath (Join-Path $state 'identity\private-key.der')) 'Pairing data deleted'
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
}
Test 'locked shortcut causes reversible partial uninstall and supports retry' {
    $lockedPath = Join-Path $context.Programs 'Codex Pocket.lnk'
    $lock = [IO.File]::Open($lockedPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try { Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply } 'denied|another process|being used|access|使用|拒绝' }
    finally { $lock.Dispose() }
    Assert (Test-Path -LiteralPath (Join-Path $context.InstallRoot 'desktop-install.json')) 'Recovery metadata removed'
    Assert ($script:fakeCliPath -eq $script:installedCliPath) 'Environment not rolled back'
    Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply | Out-Null
    Assert ($script:fakeCliPath -eq 'C:\original fixture\codex.exe') 'Retry failed'
}
Test 'damaged package and traversal entries fail closed' {
    $bridge = Join-Path $source 'pocket-bridge.exe'
    $original = [IO.File]::ReadAllBytes($bridge)
    Put $bridge 'tampered'
    Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Apply } 'integrity|hash'
    Expect-Failure { Test-PocketReleaseDirectory $packageRoot } 'integrity|hash'
    [IO.File]::WriteAllBytes($bridge, $original)
    $manifestPath = Join-Path $source 'windows-manifest.json'
    $bytes = [IO.File]::ReadAllBytes($manifestPath)
    $bad = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $bad.files[0].path = '../outside'
    Put $manifestPath ($bad | ConvertTo-Json -Depth 12)
    Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Apply } 'path|inventory'
    [IO.File]::WriteAllBytes($manifestPath, $bytes)
}
Test 'corrupt shortcut backups and redirected recovery paths are refused' {
    $savedPath = Join-Path $context.InstallRoot 'desktop-install.json'
    $original = [IO.File]::ReadAllBytes($savedPath)
    $otherShortcut = Join-Path $testRoot 'unmanaged.lnk'
    Put $otherShortcut 'unmanaged shortcut must survive'
    try {
        $bad = Get-Content -LiteralPath $savedPath -Raw | ConvertFrom-Json
        $bad.shortcuts[0].originalHash = '0' * 64
        Put $savedPath ($bad | ConvertTo-Json -Depth 12)
        Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply } 'backup integrity'
        [IO.File]::WriteAllBytes($savedPath,$original)
        $bad = Get-Content -LiteralPath $savedPath -Raw | ConvertFrom-Json
        $bad.shortcuts[0].path = $otherShortcut
        Put $savedPath ($bad | ConvertTo-Json -Depth 12)
        Expect-Failure { Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply } 'record path'
        Assert ([IO.File]::ReadAllText($otherShortcut) -eq 'unmanaged shortcut must survive') 'Recovery followed an unmanaged path'
    } finally { [IO.File]::WriteAllBytes($savedPath,$original) }
}
Test 'reparse paths and unexpected delivery files are rejected' {
    $junction = Join-Path $testRoot 'linked-state'
    New-Item -ItemType Junction -Path $junction -Target $state | Out-Null
    Expect-Failure { Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $junction -Context $context } 'reparse'
    Put (Join-Path $source 'unlisted.txt') 'must not ship'
    Expect-Failure { Test-PocketReleaseDirectory $packageRoot } 'inventory'
    [IO.File]::Delete((Join-Path $source 'unlisted.txt'))
}
Test 'Windows argument quoting preserves trailing slash and embedded quote' {
    Assert ((ConvertTo-PocketArgument 'C:\folder with spaces\') -eq '"C:\folder with spaces\\"') 'Trailing slash was not escaped'
    Assert ((ConvertTo-PocketArgument 'a"b') -eq '"a\"b"') 'Quote was not escaped'
    Expect-Failure { Get-PocketDirectoryPath ([IO.Path]::GetPathRoot($testRoot)) } 'filesystem root'
}
Test 'state migration refuses a running writer and preserves identity with private ACL' {
    $newState = Join-Path $testRoot 'migrated state'
    $sourceLock = [IO.File]::Open((Join-Path $state 'agent.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
    $sourceLock.Lock(0,[long]::MaxValue)
    try { Expect-Failure { Copy-PocketState $state $newState } 'stopped' }
    finally { $sourceLock.Unlock(0,[long]::MaxValue); $sourceLock.Dispose() }
    Assert (-not (Test-Path -LiteralPath $newState)) 'Failed migration created destination'
    Copy-PocketState $state $newState
    Assert ((Get-PocketFileHash (Join-Path $state 'identity\private-key.der')) -eq (Get-PocketFileHash (Join-Path $newState 'identity\private-key.der'))) 'Migration changed identity'
    $acl = Get-Acl -LiteralPath $newState
    Assert $acl.AreAccessRulesProtected 'Migrated state inherits public ACL'
    $allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18')
    foreach ($rule in $acl.Access) { Assert ($rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -in $allowed) 'Unexpected migrated state access' }
}
Test 'desktop package replacement keeps the stable launcher and immutable generation' {
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
    $beforeRecord=Get-PocketFileHash (Join-Path $context.InstallRoot 'desktop-install.json')
    $native2=Join-Path $testRoot 'native updated'
    Put (Join-Path $native2 'app\resources\codex.exe') 'updated native fixture'
    $script:fakePackage=[pscustomobject]@{InstallLocation=$native2;PackageFamilyName='fixture-family'}
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
    Assert ((Get-PocketFileHash (Join-Path $context.InstallRoot 'desktop-install.json')) -eq $beforeRecord) 'Desktop update rewrote the immutable generation'
    Assert ($script:fakeCliPath -eq $script:installedCliPath) 'Stable entry point changed'
}
Test 'delivery upgrade and rollback restore integration byte for byte' {
    $recordBefore=Get-PocketFileHash (Join-Path $context.InstallRoot 'desktop-install.json')
    $linkBefore=Get-PocketFileHash $shortcut
    $launcherBefore=Get-PocketFileHash $script:installedCliPath
    $taskBefore=$script:fakeTask
    $upgraded=Join-Path $testRoot 'release-v02'
    Put (Join-Path $binarySource 'pocket-desktop.exe') 'updated desktop fixture'
    New-PocketReleasePackage -Root $packageInputs -BinaryDirectory $binarySource -DesktopBinaryDirectory $binarySource -ApkPath $apk -OutputDirectory $upgraded -Version '0.2.0' -BuildEvidence @{fixture=$true} | Out-Null
    Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $upgraded 'windows') -StateDirectory $state -Context $context -Apply | Out-Null
    Assert ((Get-PocketFileHash (Join-Path $context.InstallRoot 'desktop-install.json')) -ne $recordBefore) 'Upgrade record unchanged'
    Assert ((Get-PocketFileHash $script:installedCliPath) -eq $launcherBefore) 'Stable bootstrap was replaced'
    Invoke-PocketWindowsInstall -Context $context -Rollback -Apply | Out-Null
    Assert ((Get-PocketFileHash (Join-Path $context.InstallRoot 'desktop-install.json')) -eq $recordBefore) 'Rollback record is not exact'
    Assert ((Get-PocketFileHash $shortcut) -eq $linkBefore) 'Rollback shortcut is not exact'
    Assert ($script:fakeTask -eq $taskBefore) 'Rollback task differs'
}
Test 'failed scheduled task registration keeps verified core and retries only startup' {
    Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply | Out-Null
    $cliBefore=$script:fakeCliPath; $shortcutBefore=Get-PocketFileHash $shortcut
    $script:taskFailure=$true
    $result=Invoke-PocketWindowsInstall @argsInstall -Apply
    $recordPath=Join-Path $context.InstallRoot 'desktop-install.json'
    $partial=Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert ($result.state -eq 'installed_incomplete' -and $result.coreInstalled) 'Optional task failure lost verified core'
    Assert ($script:fakeCliPath -ne $cliBefore -and [IO.File]::Exists($script:fakeCliPath)) 'Verified CLI publication was rolled back'
    Assert ((Get-PocketFileHash $shortcut) -ne $shortcutBefore) 'Successful shortcut was rolled back'
    Assert ($partial.integration.startup.stage -eq 'startup_register' -and $partial.integration.startup.hresult -eq '0x80004005') 'Task failure attribution missing'
    Assert ($null -eq $script:fakeTask) 'Task failure left a task behind'
    $shortcutCount=$script:shortcutWrites
    $result=Invoke-PocketWindowsInstall @argsInstall -Apply
    Assert ($result.state -eq 'installed') 'Startup retry did not complete'
    Assert ($script:shortcutWrites -eq $shortcutCount) 'Startup retry regenerated a successful shortcut'
    $hash=Get-PocketFileHash $recordPath
    Invoke-PocketWindowsInstall @argsInstall -Apply | Out-Null
    Assert ((Get-PocketFileHash $recordPath) -eq $hash) 'Completed repeat apply rewrote its record'
    Invoke-PocketWindowsInstall @argsInstall -Uninstall -Apply | Out-Null
}
Test 'core publication failure still rolls back the previous CLI and record' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'core-publication');Programs=(Join-Path $testRoot 'core-publication-programs');Startup=(Join-Path $testRoot 'core-publication-startup')}
    $cliBefore=$script:fakeCliPath; $taskBefore=$script:fakeTask
    $script:realWriteBackup=${function:Write-PocketBackup}
    $script:failManifest=Join-Path $custom.InstallRoot 'desktop-install.json'
    function script:Write-PocketBackup($Backup,[AllowNull()]$ExpectedHash,[switch]$Compare) {
        if($Backup.path -eq $script:failManifest -and $null -ne $Backup.bytes){$script:failManifest=$null;throw 'injected core publication failure'}
        & $script:realWriteBackup @PSBoundParameters
    }
    try {
        Expect-Failure { Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply } 'injected core publication'
        Assert ($script:fakeCliPath -eq $cliBefore -and $script:fakeTask -eq $taskBefore) 'Core failure did not restore prior integration'
        Assert (-not [IO.File]::Exists((Join-Path $custom.InstallRoot 'desktop-install.json'))) 'Failed core publication left a valid install claim'
        Assert (-not [IO.File]::Exists((Join-Path $custom.InstallRoot 'installation-pending.json'))) 'Recoverable core failure retained a journal'
    } finally { Set-Item -LiteralPath Function:\Write-PocketBackup -Value $script:realWriteBackup; $script:failManifest=$null }
}
Test 'ambiguous task mutation retains its journal and does not replay until recovered' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'ambiguous-task');Programs=(Join-Path $testRoot 'ambiguous-task-programs');Startup=(Join-Path $testRoot 'ambiguous-task-startup')}
    $script:fakeTask=$null; $script:taskFailureAfterWrite=$true
    try {
        $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply
        $recordPath=Join-Path $custom.InstallRoot 'desktop-install.json'
        $journalPath=Join-Path $custom.InstallRoot 'installation-pending.json'
        Assert ($result.state -eq 'installed_incomplete' -and $result.recoveryPending -and [IO.File]::Exists($journalPath)) 'Ambiguous mutation was replayed or lost its journal'
        $journal=Get-Content -LiteralPath $journalPath -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert ($journal.failure.stage -eq 'startup_register' -and $journal.failure.hresult -eq '0x80004005') 'Unknown mutation did not persist the exact failure'
        $recordHash=Get-PocketFileHash $recordPath; $writes=$script:taskWrites; $shortcuts=$script:shortcutWrites
        $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply
        Assert ($result.recoveryPending -and $script:taskWrites -eq $writes -and $script:shortcutWrites -eq $shortcuts) 'Unknown outcome was automatically replayed'
        Assert ((Get-PocketFileHash $recordPath) -eq $recordHash) 'Unknown recovery changed committed core'
    } finally { $script:taskReadFailure=$false; $script:taskFailureAfterWrite=$false }
    $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply
    Assert ($result.state -eq 'installed' -and -not [IO.File]::Exists($journalPath)) 'Authoritative recovery did not finish'
    Invoke-PocketWindowsInstall -Context $custom -Uninstall -Apply | Out-Null
}
Test 'shortcut COM failure preserves custom links and retries only that slot' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'shortcut-partial');Programs=(Join-Path $testRoot 'shortcut-partial-programs');Startup=(Join-Path $testRoot 'shortcut-partial-startup')}
    $customLink=Join-Path $custom.Startup 'Codex Pocket.lnk'
    Put $customLink 'user custom startup bytes'
    $primaryLink=Join-Path $custom.Programs 'Codex Pocket.lnk'
    Put $primaryLink 'user custom primary bytes'
    $script:shortcutFailure=$true
    try {
        $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply
        Assert ($result.state -eq 'installed_incomplete') 'Shortcut failure was not partial success'
        $recordPath=Join-Path $custom.InstallRoot 'desktop-install.json'
        $record=Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert ($record.version -eq 3 -and $record.shortcuts.Count -eq 3) 'Version 3 shortcut ABI changed'
        Assert ($record.integration.shortcuts[0].stage -eq 'shortcut' -and $record.integration.shortcuts[0].hresult -eq '0x80004005') 'Shortcut COM attribution missing'
        Assert ([IO.File]::ReadAllText($customLink) -eq 'user custom startup bytes') 'Custom link was deleted'
        Assert ([IO.File]::ReadAllText($primaryLink) -eq 'user custom primary bytes') 'Failed COM overwrote the primary backup'
        $taskWrites=$script:taskWrites
    } finally { $script:shortcutFailure=$false }
    $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply
    Assert ($result.state -eq 'installed' -and $script:taskWrites -eq $taskWrites) 'Shortcut retry replayed startup'
    Invoke-PocketWindowsInstall -Context $custom -Uninstall -Apply | Out-Null
    Assert ([IO.File]::ReadAllText($customLink) -eq 'user custom startup bytes') 'Uninstall changed custom bytes'
}
Test 'unknown task read is pending without mutation and captures its original only on retry' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'task-read-partial');Programs=(Join-Path $testRoot 'task-read-programs');Startup=(Join-Path $testRoot 'task-read-startup')}
    $launcher=Join-Path $custom.InstallRoot 'pocket-launcher.exe'
    $originalTask=New-PocketTaskXml $launcher $false
    $script:fakeTask=$originalTask; $script:taskReadFailure=$true
    $writes=$script:taskWrites
    try {
        $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -UiStartup Enable -Apply
        Assert ($result.state -eq 'installed_incomplete' -and $script:taskWrites -eq $writes) 'Unknown task was treated as absence'
        $record=Get-Content -LiteralPath (Join-Path $custom.InstallRoot 'desktop-install.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert (-not $record.previousTaskKnown -and $record.integration.startup.hresult -eq '0x80070005') 'Unknown snapshot or HRESULT was lost'
        $shortcuts=$script:shortcutWrites
    } finally { $script:taskReadFailure=$false }
    Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply | Out-Null
    Assert (Test-PocketTaskLogon $script:fakeTask) 'Retry lost the requested Enable setting'
    Assert ($script:shortcutWrites -eq $shortcuts) 'Task read retry replayed shortcut COM'
    Invoke-PocketWindowsInstall -Context $custom -Uninstall -Apply | Out-Null
    Assert ($script:fakeTask -eq $originalTask) 'Retry did not preserve the late known original task'
    $script:fakeTask=$null
}
Test 'foreign task ownership remains pending and is never registered or deleted' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'foreign-task');Programs=(Join-Path $testRoot 'foreign-task-programs');Startup=(Join-Path $testRoot 'foreign-task-startup')}
    $script:fakeTask='<Task>custom owner</Task>'; $writes=$script:taskWrites
    $result=Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply
    Assert ($result.state -eq 'installed_incomplete' -and $script:taskWrites -eq $writes) 'Foreign task ownership was swallowed'
    Invoke-PocketWindowsInstall -Context $custom -Uninstall -Apply | Out-Null
    Assert ($script:fakeTask -eq '<Task>custom owner</Task>' -and $script:taskWrites -eq $writes) 'Foreign task was changed'
    $script:fakeTask=$null
}
Test 'partial upgrade rollback restores the prior generation and repeated rollback is safe' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'partial-rollback');Programs=(Join-Path $testRoot 'partial-rollback-programs');Startup=(Join-Path $testRoot 'partial-rollback-startup')}
    Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply | Out-Null
    $recordPath=Join-Path $custom.InstallRoot 'desktop-install.json'
    $prior=Get-PocketFileHash $recordPath
    $priorLink=Get-PocketFileHash (Join-Path $custom.Programs 'Codex Pocket.lnk')
    $priorTask=$script:fakeTask
    $script:taskFailure=$true
    $result=Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $testRoot 'release-v02\windows') -StateDirectory $state -Context $custom -UiStartup Disable -Apply
    Assert ($result.state -eq 'installed_incomplete') 'Upgrade task failure discarded core'
    # Same-generation retry must retain the original rollback archive.
    $script:taskFailure=$true
    Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $testRoot 'release-v02\windows') -StateDirectory $state -Context $custom -Apply | Out-Null
    Invoke-PocketWindowsInstall -Context $custom -Rollback -Apply | Out-Null
    Assert ((Get-PocketFileHash $recordPath) -eq $prior -and (Get-PocketFileHash (Join-Path $custom.Programs 'Codex Pocket.lnk')) -eq $priorLink) 'Partial rollback was not exact'
    Assert ($script:fakeTask -eq $priorTask) 'Partial rollback changed the prior task'
    Expect-Failure { Invoke-PocketWindowsInstall -Context $custom -Rollback -Apply } 'No previous installation'
    Assert ((Get-PocketFileHash $recordPath) -eq $prior) 'Repeated rollback changed the restored record'
    Invoke-PocketWindowsInstall -Context $custom -Uninstall -Apply | Out-Null
}
Test 'unknown upgrade snapshot is captured before retry and rollback restores the prior task' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'unknown-upgrade');Programs=(Join-Path $testRoot 'unknown-upgrade-programs');Startup=(Join-Path $testRoot 'unknown-upgrade-startup')}
    $script:fakeTask=$null
    Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply | Out-Null
    $recordPath=Join-Path $custom.InstallRoot 'desktop-install.json'
    $linkPath=Join-Path $custom.Programs 'Codex Pocket.lnk'
    $prior=Get-PocketFileHash $recordPath; $priorTask=$script:fakeTask; $priorCli=$script:fakeCliPath; $priorLink=Get-PocketFileHash $linkPath
    $script:taskReadFailure=$true
    try {
        Expect-Failure { Invoke-PocketWindowsInstall -Context $custom -Uninstall -Apply } 'snapshot is unknown'
        Assert ($script:fakeCliPath -eq $priorCli -and (Get-PocketFileHash $linkPath) -eq $priorLink) 'Unknown uninstall changed core integration'
        $result=Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $testRoot 'release-v02\windows') -StateDirectory $state -Context $custom -UiStartup Disable -Apply
        Assert ($result.state -eq 'installed_incomplete') 'Unknown upgrade snapshot was claimed as complete'
    } finally { $script:taskReadFailure=$false }
    Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $testRoot 'release-v02\windows') -StateDirectory $state -Context $custom -Apply | Out-Null
    Assert (-not (Test-PocketTaskLogon $script:fakeTask)) 'Late snapshot retry did not apply the pending preference'
    Invoke-PocketWindowsInstall -Context $custom -Rollback -Apply | Out-Null
    Assert ((Get-PocketFileHash $recordPath) -eq $prior -and $script:fakeTask -eq $priorTask) 'Late snapshot rollback lost the prior task or record'
    Invoke-PocketWindowsInstall -Context $custom -Uninstall -Apply | Out-Null
}
Test 'unset original environment and missing shortcuts restore to absence' {
    $emptyContext = @{InstallRoot=(Join-Path $testRoot 'empty-install');Programs=(Join-Path $testRoot 'empty-programs');Startup=(Join-Path $testRoot 'empty-startup')}
    $script:fakeCliPath = $null
    Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $emptyContext -Apply | Out-Null
    Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $testRoot 'missing-source') -StateDirectory $state -Context $emptyContext -Uninstall -Apply | Out-Null
    Assert ($null -eq $script:fakeCliPath) 'Unset environment did not restore to absence'
    Assert (-not (Test-Path -LiteralPath (Join-Path $emptyContext.Programs 'Codex Pocket.lnk'))) 'Absent shortcut restored as a file'
}
Test 'preserved user Startup link does not replace the previous-generation rollback on repeat install' {
    $custom=@{InstallRoot=(Join-Path $testRoot 'user-link-install');Programs=(Join-Path $testRoot 'user-link-programs');Startup=(Join-Path $testRoot 'user-link-startup')}
    $taskOriginal=$script:fakeTask; $cliOriginal=$script:fakeCliPath
    $script:fakeTask=$null; $script:fakeCliPath=$null
    $customLink=Join-Path $custom.Startup 'Codex Pocket.lnk'
    Put $customLink 'user-owned startup entry'
    try {
        Invoke-PocketWindowsInstall -SourceDirectory $source -StateDirectory $state -Context $custom -Apply | Out-Null
        $recordPath=Join-Path $custom.InstallRoot 'desktop-install.json'
        $first=Get-PocketFileHash $recordPath
        Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $testRoot 'release-v02\windows') -StateDirectory $state -Context $custom -Apply | Out-Null
        $upgradedRecord=Get-PocketFileHash $recordPath
        Invoke-PocketWindowsInstall -SourceDirectory (Join-Path $testRoot 'release-v02\windows') -StateDirectory $state -Context $custom -Apply | Out-Null
        Assert ((Get-PocketFileHash $recordPath) -eq $upgradedRecord) 'Repeated install rewrote the rollback chain'
        Assert ([IO.File]::ReadAllText($customLink) -eq 'user-owned startup entry') 'User Startup entry changed'
        Invoke-PocketWindowsInstall -Context $custom -Rollback -Apply | Out-Null
        Assert ((Get-PocketFileHash $recordPath) -eq $first) 'Rollback no longer points to the previous generation'
    } finally {$script:fakeTask=$taskOriginal; $script:fakeCliPath=$cliOriginal}
}
Test 'ZIP is independently usable with its complete Windows manifest' {
    $extract = Join-Path $testRoot 'extracted zip'
    [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $packageRoot 'codex-pocket-0.1.0-windows.zip'),$extract)
    Test-PocketWindowsDirectory $extract | Out-Null
}
Test 'fresh release build failures never publish or leak process signing environment' {
    # External build/signing commands are synthetic and confined to this fixture.
    # This does not compile Rust/Android or validate a real certificate/APK.
    $buildRoot = Join-Path $testRoot 'fake build'
    Put (Join-Path $buildRoot 'desktop\package.json') '{"version":"0.1.0"}'
    Put (Join-Path $buildRoot 'desktop\Cargo.toml') "[workspace.package]`nversion = `"0.1.0`""
    Put (Join-Path $buildRoot 'Cargo.toml') "[workspace.package]`nversion = `"0.1.0`""
    Put (Join-Path $buildRoot 'android\app\build.gradle.kts') 'versionName = "0.1.0"'
    $fakeSigning = Join-Path $buildRoot 'signing'
    Put (Join-Path $fakeSigning 'release.jks') 'not a real keystore'
    Put (Join-Path $fakeSigning 'password.txt') 'fixture-password-only'
    $script:fakeKeytool = Join-Path $buildRoot 'keytool.ps1'
    Put $script:fakeKeytool @'
if ($args -contains $env:POCKET_STORE_PASSWORD) { throw 'Password incorrectly passed on command line' }
Write-Output '-----BEGIN CERTIFICATE-----'
Write-Output 'Zml4dHVyZS1wdWJsaWMtY2VydGlmaWNhdGU='
Write-Output '-----END CERTIFICATE-----'
$global:LASTEXITCODE = 0
'@
    $fakeSigner = Join-Path $buildRoot 'apksigner.ps1'
    Put $fakeSigner ("Write-Output 'Number of signers: 1'`nWrite-Output 'Signer #1 certificate SHA-256 digest: " + ('0' * 64) + "'`n" + '$global:LASTEXITCODE = 0')
    function script:Get-PocketKeytoolPath { return $script:fakeKeytool }
    function script:cargo { $script:cargoArguments = @($args); $global:LASTEXITCODE = $script:cargoExit }
    Put (Join-Path $buildRoot 'scripts\fetch-cloudflared.ps1') '$global:LASTEXITCODE=0'
    Put (Join-Path $buildRoot 'scripts\build-p2p-product.ps1') '$global:LASTEXITCODE=0'
    foreach($library in @('pocket-p2p.dll','datachannel.dll')) {
        Put (Join-Path $buildRoot ('.local\p2p\product-windows\Release\'+$library)) ('native fixture '+$library)
    }
    [void][IO.Directory]::CreateDirectory((Join-Path $buildRoot 'target\x86_64-pc-windows-msvc\release'))
    $script:cargoExit = 17
    $script:cargoArguments = @()
    $environment = @{}
    $names = @('POCKET_KEYSTORE','POCKET_STORE_PASSWORD','POCKET_KEY_PASSWORD','POCKET_KEY_ALIAS')
    foreach ($name in $names) { $environment[$name] = [Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,('sentinel-' + $name),'Process') }
    $releaseOutput = Join-Path $buildRoot 'release'
    try {
        $buildArgs = @{Root=$buildRoot;SigningDirectory=$fakeSigning;OutputDirectory=$releaseOutput;ApkSignerPath=$fakeSigner}
        Expect-Failure { Invoke-PocketReleaseBuild @buildArgs } 'Windows Release build failed'
        Assert ($script:cargoArguments -contains '--release') 'Release build was not requested'
        Assert ($script:cargoArguments -contains '--locked') 'Locked dependency build was not requested'
        $script:cargoExit = 0
        Put (Join-Path $buildRoot 'android\gradlew.bat') '@exit /b 19'
        Expect-Failure { Invoke-PocketReleaseBuild @buildArgs } 'Android Release build failed'
        foreach ($name in $names) { Assert ([Environment]::GetEnvironmentVariable($name,'Process') -eq ('sentinel-' + $name)) 'Process signing environment not restored' }
        Put (Join-Path $buildRoot 'android\gradlew.bat') '@exit /b 0'
        Put (Join-Path $buildRoot 'android\app\build\outputs\apk\release\app-release.apk') 'fake APK'
        Expect-Failure { Invoke-PocketReleaseBuild @buildArgs } 'does not match'
        Assert (-not (Test-Path -LiteralPath $releaseOutput)) 'Failed build published a release'
        foreach ($name in $names) { Assert ([Environment]::GetEnvironmentVariable($name,'Process') -eq ('sentinel-' + $name)) 'Process signing environment not restored' }
        # Exercise the successful public pipeline with distinct native output
        # directories and a synthetic signer that matches only this fixture.
        $digest=Get-PocketBytesHash ([Text.Encoding]::UTF8.GetBytes('fixture-public-certificate'))
        Put $fakeSigner ("Write-Output 'Number of signers: 1'`nWrite-Output 'Signer #1 certificate SHA-256 digest: " + $digest + "'`n" + '$global:LASTEXITCODE = 0')
        foreach($file in Get-PocketWindowsFiles) {
            if($file -in @('cloudflared.exe','cloudflared-LICENSE.txt')){$targetFile=Join-Path $buildRoot ('.local\tools\'+$file)}
            elseif($file -in @('pocket-desktop.exe','pocket-supervisor.exe','pocket-launcher.exe','pocket-upgrade.exe')){$targetFile=Join-Path $buildRoot ('desktop\target\release\'+$file)}
            elseif($file -like '*.exe' -or $file -like '*.dll'){$targetFile=Join-Path $buildRoot ('target\x86_64-pc-windows-msvc\release\'+$file)}
            elseif($file -like '*.md'){$targetFile=Join-Path $buildRoot ('docs\'+$file)}
            else{$targetFile=Join-Path $buildRoot ('scripts\'+$file)}
            Put $targetFile ('synthetic '+$file)
        }
        foreach($file in @('Cargo.lock','desktop\Cargo.lock','desktop\package-lock.json')){Put (Join-Path $buildRoot $file) 'fixture lock'}
        Invoke-PocketReleaseBuild @buildArgs | Out-Null
        $published=Test-PocketReleaseDirectory $releaseOutput
        Assert ($published.build.frontendTestsPassed -and $script:desktopBuilt) 'Desktop build evidence missing'
        Assert ($published.build.desktopProfile -eq 'release') 'Desktop release profile absent'
        Assert ((Get-PocketFileHash (Join-Path $releaseOutput 'windows\pocket-desktop.exe')) -eq (Get-PocketFileHash (Join-Path $buildRoot 'desktop\target\release\pocket-desktop.exe'))) 'Wrong desktop output packaged'
        foreach($library in @('pocket-p2p.dll','datachannel.dll')) {
            Assert ((Get-PocketFileHash (Join-Path $releaseOutput ('windows\'+$library))) -eq (Get-PocketFileHash (Join-Path $buildRoot ('.local\p2p\product-windows\Release\'+$library)))) 'Wrong native transport library packaged'
        }
        foreach ($name in $names) { Assert ([Environment]::GetEnvironmentVariable($name,'Process') -eq ('sentinel-' + $name)) 'Successful build did not restore signing environment' }
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$environment[$name],'Process') }
    }
}
Test 'staging rename tolerates a temporary open file without overwriting a destination' {
    $destination = Join-Path $testRoot 'rename-publication'
    $stage = $destination + '.staging-' + [guid]::NewGuid().ToString('N')
    Put (Join-Path $stage 'held.txt') 'rename fixture'
    $handle = [IO.File]::Open((Join-Path $stage 'held.txt'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        Expect-Failure { [IO.Directory]::Move($stage,$destination) } 'denied|拒绝'
        Add-Type -TypeDefinition @'
using System;
using System.Threading.Tasks;
public static class PocketFixtureRelease {
    public static void Later(IDisposable handle) {
        Task.Run(async () => { await Task.Delay(350); handle.Dispose(); });
    }
}
'@
        [PocketFixtureRelease]::Later($handle)
        Move-PocketStagedDirectory $stage $destination
        Assert ([IO.File]::ReadAllText((Join-Path $destination 'held.txt')) -eq 'rename fixture') 'Published fixture missing'
        [void][IO.Directory]::CreateDirectory($stage)
        Expect-Failure { Move-PocketStagedDirectory $stage $destination } 'already exists'
    } finally { $handle.Dispose() }
}
Test 'task validation rejects additional actions and foreign logon triggers' {
    $launcher = Join-Path $context.InstallRoot 'pocket-launcher.exe'
    $valid = New-PocketTaskXml $launcher $true
    foreach ($bad in @(
        $valid.Replace('</Actions>','<ComHandler><ClassId>{00000000-0000-0000-0000-000000000000}</ClassId></ComHandler></Actions>'),
        $valid.Replace('</Triggers>','<BootTrigger><Enabled>true</Enabled></BootTrigger></Triggers>'),
        $valid.Replace('<Enabled>true</Enabled><UserId>','<Enabled>false</Enabled><UserId>')
    )) { Expect-Failure { Assert-PocketTaskOwned $bad $launcher } 'drift|task' }
    Assert (-not (Test-PocketTaskLogon ($valid.Replace('<Enabled>true</Enabled><UserId>','<Enabled>false</Enabled><UserId>')))) 'Disabled logon trigger reported enabled'
}
Test 'final package verification remains valid' { Test-PocketReleaseDirectory $packageRoot | Out-Null }
Write-Output ("$script:passed tests passed; fixtures retained at $testRoot")
