#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ReleaseDirectory,[Parameter(Mandatory)][string]$PrivateKeyPath,
    [Parameter(Mandatory)][string]$TrustPath,[Parameter(Mandatory)][string]$AssetBase,
    [string]$VpkDll,[string]$SetupExecutable=(Join-Path $PSScriptRoot '../desktop/target/release/pocket-setup.exe'))
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($ReleaseDirectory)
$public=Get-Content -LiteralPath $TrustPath -Raw | ConvertFrom-Json
if (!(Test-Path -LiteralPath $SetupExecutable -PathType Leaf)) {
    throw 'Fresh pocket-setup.exe is required from the desktop Release build.'
}
if(!$VpkDll){
    $toolDirectory=Join-Path ([IO.Path]::GetTempPath()) ('pocket-vpk-'+[guid]::NewGuid().ToString('N'))
    & dotnet tool install vpk --version 1.2.161 --framework net8.0 --tool-path $toolDirectory
    if($LASTEXITCODE -ne 0){throw 'Pinned Velopack tool installation failed'}
    $entries=@(Get-ChildItem -LiteralPath $toolDirectory -Filter vpk.dll -Recurse -Force | Where-Object {$_.FullName -match '[\\/]net8\.0[\\/]any[\\/]vpk\.dll$'})
    if($entries.Count -ne 1){throw 'Expected one pinned Velopack .NET 8 entry'}
    $VpkDll=$entries[0].FullName
}
$offer=Join-Path $root 'pocket-install-offer.json'
& python (Join-Path $PSScriptRoot 'sign-update-feed.py') --release $root --key $PrivateKeyPath --trust $TrustPath --asset-base $AssetBase --output $offer
if($LASTEXITCODE -ne 0){throw 'Payload signature failed'}
$packages=Join-Path $root 'velopack'
& (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -ReleaseDirectory $root -OutputDirectory $packages -VpkDll $VpkDll -SetupExecutable $SetupExecutable -UpdateOfferPath $offer -TrustedUpdatePublicKey $public.publicKey
if($LASTEXITCODE -ne 0){throw 'Windows installer creation failed'}
$full=@(Get-ChildItem -LiteralPath $packages -Filter '*-full.nupkg' -File)
if($full.Count -ne 1){throw 'Expected one full Velopack package'}
$setup=@(Get-ChildItem -LiteralPath $packages -Filter '*Setup*.exe' -File)
if($setup.Count -ne 1){throw 'Expected one Velopack Setup executable'}
& python (Join-Path $PSScriptRoot 'sign-update-feed.py') --release $root --key $PrivateKeyPath --trust $TrustPath --asset-base $AssetBase --output (Join-Path $root 'windows-update.json') --package $full[0].FullName
if($LASTEXITCODE -ne 0){throw 'Full package signature failed'}
Get-ChildItem -LiteralPath $packages -File | Where-Object {$_.Extension -in '.exe','.nupkg','.json'} | Copy-Item -Destination $root
Write-Output 'Signed Velopack installer and full update package are ready for release acceptance.'
