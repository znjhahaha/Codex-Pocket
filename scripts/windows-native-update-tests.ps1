#requires -Version 5.1
# Production inventory logic; synthetic OS rows, no process effects or registry.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-install.ps1')
$root=Join-Path ([IO.Path]::GetTempPath()) ('pocket-native-inventory-'+[guid]::NewGuid().ToString('N'))
$state=Join-Path $root 'state'
[void][IO.Directory]::CreateDirectory((Join-Path $root 'runtimes'))
[void][IO.Directory]::CreateDirectory((Join-Path $root 'app'))
foreach($file in @('app/Codex.exe','bridge.exe','native.exe')){[IO.File]::WriteAllText((Join-Path $root $file),'synthetic; never executed')}
$desktop=Join-Path $root 'app/Codex.exe'
$script:fixtureGroup=@(
    @{pid=101;createdAt=1;executable=$desktop;sha256=(Get-PocketFileHash $desktop);name='associated-desktop'},
    @{pid=102;createdAt=2;executable=(Join-Path $root 'bridge.exe');name='pocket-bridge.exe'},
    @{pid=103;createdAt=3;executable=(Join-Path $root 'native.exe');name='associated-native'},
    @{pid=201;createdAt=4;executable=(Join-Path $root 'host.exe');name='pocket-runtime-host.exe'}
)
$script:fixtureRows=@(
    @{ProcessId=101;ParentProcessId=10;ExecutablePath=$desktop},
    @{ProcessId=102;ParentProcessId=101;ExecutablePath=(Join-Path $root 'bridge.exe')},
    @{ProcessId=103;ParentProcessId=102;ExecutablePath=(Join-Path $root 'native.exe')},
    @{ProcessId=104;ParentProcessId=101;ExecutablePath=$desktop},
    @{ProcessId=201;ParentProcessId=20;ExecutablePath=(Join-Path $root 'host.exe')}
)
Write-PocketJson (Join-Path $root 'runtimes/fixture.json') @{pid=102;runtimeId='fixture'}
function Get-PocketInstallGroup {param($InstallRoot,$StateDirectory,[switch]$InspectOnly) if(-not $InspectOnly){throw 'Inventory must be read-only'};return ,$script:fixtureGroup}
function Get-PocketInstallProcesses {return $script:fixtureRows}
function Get-PocketInstallProcessIdentity {
    param($ProcessId)
    $row=$script:fixtureRows|Where-Object {$_.ProcessId -eq $ProcessId}
    $known=@($script:fixtureGroup|Where-Object {$_.pid -eq $ProcessId})
    $birth=if($row.ContainsKey('CreatedAt')){$row.CreatedAt}elseif($known.Count){$known[0].createdAt}else{[long]$ProcessId}
    $parent=if($row.ContainsKey('ActualParent')){$row.ActualParent}else{$row.ParentProcessId}
    return @{pid=$ProcessId;createdAt=$birth;executable=$row.ExecutablePath;parentPid=$parent}
}
function Get-PocketDesktopPackage {return @{InstallLocation=$root}}
function Reject([scriptblock]$Action){$rejected=$false;try{& $Action|Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'Unsafe native tree accepted'}}
$result=Get-PocketNativeInventory $root $state
if($result.group.Count -ne 4 -or @($result.group|Where-Object {$_.pid -eq 201}).Count -or $result.descriptors.Count -ne 1){throw 'Complete tree projection or unrelated Host exclusion failed'}
# An orphan's historical parent PID has been reused by a younger child of the
# target desktop. It is not ours, despite matching root image/user/creation.
$script:fixtureRows+=@{ProcessId=105;ParentProcessId=104;ExecutablePath=$desktop;CreatedAt=50}
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureRows=$script:fixtureRows|Where-Object {$_.ProcessId -ne 105}
# Same birth is not a proven parent edge either.
$script:fixtureRows+=@{ProcessId=105;ParentProcessId=104;ExecutablePath=$desktop;CreatedAt=104}
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureRows=$script:fixtureRows|Where-Object {$_.ProcessId -ne 105}
# PID changed after the OS row snapshot: its current handle names another parent.
$script:fixtureRows[3].ActualParent=999
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureRows[3].Remove('ActualParent')
# Each selected edge must carry the exact parent identity, not just the root PID.
$result=Get-PocketNativeInventory $root $state
foreach($member in $result.group|Where-Object {$_.pid -ne 101}) {
    $parent=@($result.group|Where-Object {$_.pid -eq $member.parentIdentity.pid})
    if($parent.Count -ne 1 -or $parent[0].createdAt -ne $member.parentIdentity.createdAt -or $parent[0].executable -ne $member.parentIdentity.executable){throw 'Parent creation identity missing from inventory'}
}
$script:fixtureRows[3].ExecutablePath=Join-Path $root 'unknown.exe'
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureRows[3].ExecutablePath=$desktop
# CREATE_NO_WINDOW still creates Windows console hosts on this Windows build.
# Only the OS system image under an already verified owner joins the tree.
$console=Get-PocketConsoleHostPath
$script:fixtureRows+=@{ProcessId=105;ParentProcessId=103;ExecutablePath=$console}
$consoleTree=Get-PocketNativeInventory $root $state
if(@($consoleTree.group|Where-Object {$_.name -eq 'console-host'}).Count -ne 1){throw 'Exact system console host was not included'}
$script:fixtureRows[-1].ExecutablePath=Join-Path $root 'conhost.exe'
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureRows=$script:fixtureRows|Where-Object {$_.ProcessId -ne 105}
# Multiple managed Bridges under the same exact desktop must all be projected.
$beforeGroup=$script:fixtureGroup;$beforeRows=$script:fixtureRows
$script:fixtureGroup+=@(
    @{pid=105;createdAt=105;executable=(Join-Path $root 'bridge.exe');name='pocket-bridge.exe'},
    @{pid=106;createdAt=106;executable=(Join-Path $root 'native.exe');name='associated-native'}
)
$script:fixtureRows+=@(
    @{ProcessId=105;ParentProcessId=101;ExecutablePath=(Join-Path $root 'bridge.exe')},
    @{ProcessId=106;ParentProcessId=105;ExecutablePath=(Join-Path $root 'native.exe')}
)
Write-PocketJson (Join-Path $root 'runtimes/second.json') @{pid=105;runtimeId='second'}
$multiple=Get-PocketNativeInventory $root $state
if($multiple.group.Count -ne 6 -or $multiple.descriptors.Count -ne 2){throw 'Multiple native connections were not included'}
$script:fixtureRows[-2].ParentProcessId=999
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureRows[-2].ParentProcessId=101
[IO.File]::Delete((Join-Path $root 'runtimes/second.json'))
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureGroup=$beforeGroup;$script:fixtureRows=$beforeRows
$script:fixtureGroup+=@{pid=105;createdAt=5;executable=(Join-Path $root 'other-bridge.exe');name='pocket-bridge.exe'}
Reject {Get-PocketNativeInventory $root $state}
$script:fixtureGroup=@()
Reject {Get-PocketNativeInventory $root $state}
Write-Output ('PASS single complete tree, array shape, unrelated Host exclusion, unknown descendants, multiple Bridges and unbridged desktop startup; fixture '+$root)
