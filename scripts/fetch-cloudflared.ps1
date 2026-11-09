#requires -Version 7.0
[CmdletBinding()]
param([string]$Destination = (Join-Path $PSScriptRoot '../.local/tools'))
$ErrorActionPreference = 'Stop'
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../deploy/cloudflared-lock.json') -Raw | ConvertFrom-Json
$target = $lock.'windows-x86_64'
if ($target.sha256 -notmatch '^[a-f0-9]{64}$' -or $target.url -notmatch '^https://github.com/cloudflare/cloudflared/releases/download/') { throw 'Invalid cloudflared lock.' }
$folder = [IO.Path]::GetFullPath($Destination)
[void][IO.Directory]::CreateDirectory($folder)
$binary = Join-Path $folder 'cloudflared.exe'
if (-not (Test-Path -LiteralPath $binary) -or (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $target.sha256) {
    $partial = Join-Path $folder ('cloudflared.' + [guid]::NewGuid().ToString('N') + '.partial')
    try {
        Invoke-WebRequest -Uri $target.url -OutFile $partial -TimeoutSec 180
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $target.sha256) { throw 'cloudflared checksum mismatch; bundle stopped.' }
        Move-Item -LiteralPath $partial -Destination $binary -Force
    } finally { if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial } }
}
Invoke-WebRequest -Uri $lock.licenseUrl -OutFile (Join-Path $folder 'cloudflared-LICENSE.txt') -TimeoutSec 30
[ordered]@{version=$lock.version;binary=$binary;sha256=$target.sha256} | ConvertTo-Json
