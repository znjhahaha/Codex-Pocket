#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-install.ps1')
function Assert($Value,[string]$Message) { if(-not $Value){throw $Message} }
$root=Join-Path ([IO.Path]::GetTempPath()) ('pocket-transparent-'+[guid]::NewGuid().ToString('N'))
$bin=Join-Path $root ('bin\'+('a'*64))
[void][IO.Directory]::CreateDirectory($bin)
$proxy=Join-Path $bin 'pocket-native-proxy.exe'
[IO.File]::WriteAllText($proxy,'unexecuted test fixture')
$choice=Get-PocketTransparentCliSnapshot @{cli=$null;cliKind='String'} $null $root $bin
Assert ($choice.cli -eq $proxy) 'New installations must use the dedicated native proxy'
Assert (Test-PocketOwnedCliPath $proxy $root) 'Proxy integration must be recognized for rollback'
$choice=Get-PocketTransparentCliSnapshot @{cli=(Join-Path $root 'pocket-launcher.exe');cliKind='String'} $null $root $bin
Assert ($choice.cli -eq $proxy) 'Old manager-based overrides must migrate to the native-only proxy'
$choice=Get-PocketTransparentCliSnapshot @{cli='C:\custom\codex.exe';cliKind='String'} $null $root $bin
Assert ($choice.cli -eq 'C:\custom\codex.exe') 'Explicit third-party native overrides must retain meaning'
[IO.File]::Delete($proxy)
$choice=Get-PocketTransparentCliSnapshot @{cli=(Join-Path $root 'pocket-launcher.exe');cliKind='String'} @{previousCliPath=$null;previousCliKind='String'} $root $bin
Assert ($null -eq $choice.cli) 'Legacy payloads must not install a nonexistent transparent entry'
'PASS transparent native integration and legacy rollback'
