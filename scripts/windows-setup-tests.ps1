#requires -Version 7.0
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('pocket-setup-fixture-'+[guid]::NewGuid().ToString('N'))
$windows=Join-Path $fixture 'windows'
[void][IO.Directory]::CreateDirectory($windows)
foreach($name in Get-PocketWindowsFiles){[IO.File]::WriteAllText((Join-Path $windows $name),('Synthetic fixture, never execute: '+$name))}
Write-PocketJson (Join-Path $windows 'windows-manifest.json') @{schema=1;version='0.6.0-fixture';files=(Get-PocketInventory $windows (Get-PocketWindowsFiles))}
& (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -ReleaseDirectory $fixture -OutputDirectory $fixture
$setup=Get-ChildItem -LiteralPath $fixture -Filter '*setup*.exe'
if($setup.Count -ne 1 -or $setup.Length -lt 10000){throw 'Expected one compiled NSIS setup fixture'}
[IO.File]::AppendAllText((Join-Path $windows 'pocket-agent.exe'),'tampered')
$rejected=$false
try{& (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -ReleaseDirectory $fixture -OutputDirectory (Join-Path $fixture 'rejected')}catch{if($_.Exception.Message -match 'integrity/hash'){$rejected=$true}else{throw}}
if(-not $rejected){throw 'Setup builder accepted a changed binary'}
Write-Output ('PASS exact-payload NSIS packaging and tamper refusal; fixture was never launched: '+$fixture)
