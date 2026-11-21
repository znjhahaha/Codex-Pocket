#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('pocket-update-trust-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixture)
$names=@(Get-PocketWindowsFiles -IncludeUpdateTrust)
foreach($name in $names){[IO.File]::WriteAllText((Join-Path $fixture $name),'fixture payload; never execute')}
$trust=Join-Path $fixture 'windows-update-trust.json'
[IO.File]::WriteAllText($trust,'{"schema":1,"feed":"https://fixture.invalid/stable.json","publicKey":"fixture-only","allowedHosts":["fixture.invalid"]}')
Write-PocketJson (Join-Path $fixture 'windows-manifest.json') @{schema=1;version='0.0.0-fixture';files=(Get-PocketInventory $fixture $names)}
$manifest=Test-PocketWindowsDirectory $fixture
if(@($manifest.files | Where-Object {$_.path -eq 'windows-update-trust.json'}).Count -ne 1){throw 'Trusted feed is not preserved in candidate inventory'}
[IO.File]::AppendAllText($trust,'tampered')
$rejected=$false
try {Test-PocketWindowsDirectory $fixture | Out-Null}catch{$rejected=$true}
if(-not $rejected){throw 'Tampered update trust was accepted'}
Write-Output ('PASS optional signed update trust inventory and tamper rejection; fixture retained at '+$fixture)
