#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ReleaseDirectory,[Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$VpkDll,[Parameter(Mandatory)][string]$SetupExecutable,
    [Parameter(Mandatory)][string]$UpdateOfferPath,[Parameter(Mandatory)][string]$TrustedUpdatePublicKey)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$source=Join-Path ([IO.Path]::GetFullPath($ReleaseDirectory)) 'windows'
$manifest=Test-PocketWindowsDirectory $source
if(Test-Path -LiteralPath $OutputDirectory){throw 'Output directory already exists'}
$verification=Start-Process -FilePath (Join-Path $source 'pocket-upgrade.exe') -ArgumentList @('--verify-local-source','--source',('"'+$source+'"'),'--offer',('"'+[IO.Path]::GetFullPath($UpdateOfferPath)+'"'),'--trusted-key',$TrustedUpdatePublicKey) -WindowStyle Hidden -PassThru -Wait
if($verification.ExitCode -ne 0){throw 'Signed setup payload verification failed'}
$stage=[IO.Path]::GetFullPath($OutputDirectory)+'.payload'
if(Test-Path -LiteralPath $stage){throw 'Staging directory already exists'}
[void][IO.Directory]::CreateDirectory($stage)
Copy-Item -LiteralPath $source -Destination (Join-Path $stage 'payload') -Recurse
Copy-Item -LiteralPath $SetupExecutable -Destination (Join-Path $stage 'pocket-setup.exe')
Copy-Item -LiteralPath $UpdateOfferPath -Destination (Join-Path $stage 'pocket-install-offer.json')
& dotnet $VpkDll pack --packId CodexPocketManager --packVersion $manifest.version --packDir $stage --mainExe pocket-setup.exe --packTitle 'Codex Pocket' --icon (Join-Path $source 'codex-pocket.ico') --runtime win-x64 --delta None --outputDir $OutputDirectory --shortcuts 'Desktop,StartMenuRoot'
if($LASTEXITCODE -ne 0){throw 'Velopack packaging failed; staging retained'}
Write-Output 'Velopack manager package created. Sign the full nupkg with sign-update-feed.py --package before publishing the update feed.'
