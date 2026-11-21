#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$root=Join-Path (Get-PocketLocalAppData) 'CodexPocket'
Assert-PocketUnredirectedPath $root
$launcher=Join-Path $root 'pocket-launcher.exe'
$recordPath=Join-Path $root 'desktop-install.json'
Assert-PocketPlainPath $recordPath
$record=Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($record.version -ne 3 -or $record.bridge -ne $launcher -or (Get-PocketFileHash $launcher) -ne $record.launcherHash) { throw 'Stable launcher integrity failed.' }
$bin=Join-Path $root ('bin\'+$record.generation)
$versionedLauncher=Join-Path $bin 'pocket-launcher.exe'
if(Test-Path -LiteralPath $versionedLauncher){
    $manifest=Get-Content -LiteralPath (Join-Path $bin 'windows-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $entry=@($manifest.files | Where-Object {$_.path -eq 'pocket-launcher.exe'})
    if($entry.Count -eq 1 -and (Get-PocketFileHash $versionedLauncher) -eq $entry[0].sha256){$launcher=$versionedLauncher}
}
Start-Process -FilePath $launcher -ArgumentList '--pocket-desktop' -WindowStyle Hidden
