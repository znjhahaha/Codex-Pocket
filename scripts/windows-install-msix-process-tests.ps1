#requires -Version 5.1
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'windows-install.ps1')
$script:fixtureBase=Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.local'))) ('msix-install-'+[guid]::NewGuid().ToString('N'))
$script:fixtureLocal=Join-Path $script:fixtureBase 'Local'
$script:fixtureInstall=Join-Path $script:fixtureLocal 'CodexPocket'
$script:fixtureState=Join-Path $script:fixtureInstall 'agent'
$script:fixtureShadow=Join-Path $script:fixtureLocal 'Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\CodexPocket\pocket-launcher.exe'
$script:fixtureLauncher=Join-Path $script:fixtureInstall 'pocket-launcher.exe'
$script:fixturePassed=0
$script:fixtureParent=0
$script:fixtureFlushFailure=$false
$script:fixtureFlushCalls=0
$script:fixtureRealSync=${function:Sync-PocketRepairFile}
function Sync-PocketRepairFile([string]$Path){
    if($Path -like '*msix-launcher-*.exe'){
        $script:fixtureFlushCalls++
        if($script:fixtureFlushFailure){throw 'fixture archive flush failed'}
    }
    & $script:fixtureRealSync $Path
}
function Assert-Fixture($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Put-Fixture([string]$Path,[string]$Content){
    Assert-Fixture ($Path.StartsWith($script:fixtureBase+'\',[StringComparison]::OrdinalIgnoreCase)) 'Fixture write escaped scope.'
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path,$Content)
}
function Test-Fixture([string]$Name,[scriptblock]$Body){& $Body;$script:fixturePassed++;Write-Output ('PASS '+$Name)}
function Fails-Fixture([scriptblock]$Body,[string]$Pattern){try{& $Body|Out-Null}catch{Assert-Fixture ($_.Exception.Message -match $Pattern) ('Wrong failure: '+$_.Exception.Message);return};throw ('Expected '+$Pattern)}
function Get-PocketLocalAppData{return $script:fixtureLocal}
function Get-PocketDesktopPackage{return [pscustomobject]@{InstallLocation=(Join-Path $script:fixtureBase 'native');PackageFamilyName='OpenAI.Codex_2p2nqsd0c76g0'}}
function Get-PocketInstallProcesses{
    $rows=@([pscustomobject]@{ProcessId=654321;ParentProcessId=$script:fixtureParent;ExecutablePath=$script:fixtureLauncher;CommandLine='launcher';Name='pocket-launcher.exe'})
    if($script:fixtureParent -gt 0){$rows+=@([pscustomobject]@{ProcessId=$script:fixtureParent;ParentProcessId=0;ExecutablePath='C:\Windows\System32\svchost.exe';Name='svchost.exe';CommandLine='service'})}
    return $rows
}
function Get-PocketInstallProcessIdentity([int]$ProcessId){
    if($script:fixtureParent -gt 0 -and $ProcessId -eq $script:fixtureParent){throw [UnauthorizedAccessException]::new('SYSTEM ancestor cannot be captured')}
    if($script:fixtureAlive -and $ProcessId -eq 654321){return [pscustomobject]@{pid=654321;createdAt=1;executable=$script:fixtureShadow}};return $null
}
Put-Fixture $script:fixtureLauncher 'verified launcher fixture'
Put-Fixture $script:fixtureShadow 'verified launcher fixture'
$script:fixtureHash=Get-PocketFileHash $script:fixtureLauncher
Write-PocketJson (Join-Path $script:fixtureInstall 'desktop-install.json') @{installRoot=$script:fixtureInstall;stateDirectory=$script:fixtureState;bridge=$script:fixtureLauncher;launcherHash=$script:fixtureHash;generation=('a'*64)}
$script:fixtureAlive=$true
Test-Fixture 'known same-user MSIX launcher is captured by its actual image identity' {
    $group=Get-PocketInstallGroup $script:fixtureInstall $script:fixtureState
    Assert-Fixture ($group.Count -eq 1 -and $group[0].executable -ceq $script:fixtureShadow -and $group[0].sha256 -ceq $script:fixtureHash) 'Logical CIM path did not resolve to verified actual launcher.'
}
Test-Fixture 'SYSTEM task ancestor is excluded while the owned launcher remains captured' {
    $script:fixtureParent=654320
    $group=Get-PocketInstallGroup $script:fixtureInstall $script:fixtureState
    Assert-Fixture ($group.Count -eq 1 -and $group[0].pid -eq 654321) 'Service ancestor was included or owned child was lost.'
    $script:fixtureParent=0
}
Test-Fixture 'an unrelated shadow or modified launcher is rejected' {
    Put-Fixture $script:fixtureShadow 'changed launcher'
    Fails-Fixture {Get-PocketInstallGroup $script:fixtureInstall $script:fixtureState} 'shadow.*integrity|shadow.*hash'
    Put-Fixture $script:fixtureShadow 'verified launcher fixture'
    $original=$script:fixtureShadow
    $script:fixtureShadow=Join-Path $script:fixtureLocal 'Packages\DifferentPublisher\LocalCache\Local\CodexPocket\pocket-launcher.exe'
    Put-Fixture $script:fixtureShadow 'verified launcher fixture'
    Fails-Fixture {Get-PocketInstallGroup $script:fixtureInstall $script:fixtureState} 'identity changed|shadow.*identity'
    $script:fixtureShadow=$original
}
Test-Fixture 'stopped verified shadow launcher is durably archived so canonical launch can fall through' {
    $record=Read-PocketRepairJson (Join-Path $script:fixtureInstall 'desktop-install.json')
    $alias=Get-PocketInstallShadowLauncher $script:fixtureInstall $record
    $life=@{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$script:fixtureInstall;stateDirectory=$script:fixtureState;shadowLauncher=$alias}
    $lifePath=Join-Path $script:fixtureInstall 'installer-lifecycle.json'
    Fails-Fixture {Archive-PocketInstallShadowLauncher $life $lifePath} 'still running'
    Assert-Fixture ([IO.File]::Exists($script:fixtureShadow)) 'Live launcher moved.'
    $script:fixtureAlive=$false
    Archive-PocketInstallShadowLauncher $life $lifePath
    Assert-Fixture (-not [IO.File]::Exists($script:fixtureShadow)) 'Shadow remains in executable lookup path.'
    Assert-Fixture ((Get-PocketFileHash $life.shadowLauncher.archive) -ceq $script:fixtureHash) 'Archive did not preserve original launcher.'
    Assert-Fixture ($life.shadowLauncher.state -eq 'archived') 'Archive evidence not final.'
    Assert-Fixture ((Get-PocketFileHash $script:fixtureLauncher) -ceq $script:fixtureHash) 'Canonical launcher changed.'
    Archive-PocketInstallShadowLauncher $life $lifePath
}
Test-Fixture 'a changed alias is preserved instead of being silently removed' {
    Put-Fixture $script:fixtureShadow 'verified launcher fixture'
    $record=Read-PocketRepairJson (Join-Path $script:fixtureInstall 'desktop-install.json')
    $life=@{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$script:fixtureInstall;stateDirectory=$script:fixtureState;shadowLauncher=(Get-PocketInstallShadowLauncher $script:fixtureInstall $record)}
    $lifePath=Join-Path $script:fixtureInstall 'retry-lifecycle.json'
    Put-Fixture $script:fixtureShadow 'unrelated later edit'
    Fails-Fixture {Archive-PocketInstallShadowLauncher $life $lifePath} 'shadow.*changed|shadow.*integrity|shadow.*hash'
    Assert-Fixture ([IO.File]::ReadAllText($script:fixtureShadow) -eq 'unrelated later edit') 'Edited alias removed.'
}
Test-Fixture 'an interrupted archive after alias removal is recovered from exact retained bytes' {
    Put-Fixture $script:fixtureShadow 'verified launcher fixture'
    $record=Read-PocketRepairJson (Join-Path $script:fixtureInstall 'desktop-install.json')
    $alias=Get-PocketInstallShadowLauncher $script:fixtureInstall $record
    $alias.archive=Join-Path $script:fixtureInstall ('recovery\msix-launcher-'+$alias.sha256+'.exe')
    $alias.state='archive_pending'
    Put-Fixture $alias.archive 'verified launcher fixture'
    [IO.File]::Delete($script:fixtureShadow)
    $life=@{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$script:fixtureInstall;stateDirectory=$script:fixtureState;shadowLauncher=$alias}
    Archive-PocketInstallShadowLauncher $life (Join-Path $script:fixtureInstall 'interrupted-lifecycle.json')
    Assert-Fixture ($life.shadowLauncher.state -eq 'archived') 'Interrupted archival did not finish.'
    Assert-Fixture ((Get-PocketFileHash $alias.archive) -ceq $script:fixtureHash) 'Recovery bytes changed.'
    [IO.File]::Delete($alias.archive)
    Fails-Fixture {Archive-PocketInstallShadowLauncher $life (Join-Path $script:fixtureInstall 'missing-evidence.json')} 'evidence is missing'
}
Test-Fixture 'failed archive flush is retried and repeated failure preserves the source launcher' {
    Put-Fixture $script:fixtureShadow 'verified launcher fixture'
    $record=Read-PocketRepairJson (Join-Path $script:fixtureInstall 'desktop-install.json')
    $life=@{schema=1;operationId=[guid]::NewGuid().ToString();installRoot=$script:fixtureInstall;stateDirectory=$script:fixtureState;shadowLauncher=(Get-PocketInstallShadowLauncher $script:fixtureInstall $record)}
    $lifePath=Join-Path $script:fixtureInstall 'flush-failure-lifecycle.json'
    $script:fixtureFlushFailure=$true;$script:fixtureFlushCalls=0
    Fails-Fixture {Archive-PocketInstallShadowLauncher $life $lifePath} 'fixture archive flush failed'
    Fails-Fixture {Archive-PocketInstallShadowLauncher $life $lifePath} 'fixture archive flush failed'
    Assert-Fixture ($script:fixtureFlushCalls -eq 2 -and [IO.File]::Exists($script:fixtureShadow)) 'Retry skipped flush or lost original bytes.'
    $script:fixtureFlushFailure=$false
    Archive-PocketInstallShadowLauncher $life $lifePath
    Assert-Fixture ($script:fixtureFlushCalls -eq 3 -and -not [IO.File]::Exists($script:fixtureShadow)) 'Durable retry did not finish.'
}
Write-Output ("$script:fixturePassed MSIX process tests passed; fixture $script:fixtureBase")
