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
Start-Process -FilePath $launcher -ArgumentList '--pocket-ui' -WindowStyle Hidden
