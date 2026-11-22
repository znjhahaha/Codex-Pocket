<#
Cross-builds the Linux host (pocket-agent and pocket-runtime-host, static musl), signs the pair and places it
where the Android build packages it: .local/execution-runtime/assets/linux-runtime/<architecture>/ (never
committed). An APK built without that directory works as before and answers "ssh_runtime_missing" for the
architecture when a Linux host is added.

Needs the Rust target <architecture>-unknown-linux-musl, cargo-zigbuild, zig (on PATH or CARGO_ZIGBUILD_ZIG_PATH)
and a Python with `cryptography`. The signing key is the existing Ed25519 key whose public half the app pins;
it is read in place, and no key is ever created or replaced here.
#>
param(
    [ValidateSet('x86_64','aarch64')][string]$Architecture = 'x86_64',
    [Parameter(Mandatory)][string]$SigningKey,
    [string]$TargetDirectory,
    [string]$Toolchain,
    [string]$Python = 'python'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not [IO.File]::Exists($SigningKey)) { throw 'Existing runtime signing key missing. No key will be generated.' }
$triple = "$Architecture-unknown-linux-musl"
if (-not $TargetDirectory) { $TargetDirectory = Join-Path $root 'target\linux-runtime' }
$version = ([regex]::Match([IO.File]::ReadAllText((Join-Path $root 'Cargo.toml')), '(?m)^version = "([0-9A-Za-z._-]+)"')).Groups[1].Value
if (-not $version) { throw 'Workspace version not found.' }
$cargo = @(); if ($Toolchain) { $cargo += "+$Toolchain" }
$assets = Join-Path $root ".local\execution-runtime\assets\linux-runtime\$Architecture"
$staged = Join-Path $root ".local\execution-runtime\staging\$Architecture"
$previousTarget = $env:CARGO_TARGET_DIR
Push-Location $root
try {
    $env:CARGO_TARGET_DIR = $TargetDirectory
    & cargo @cargo zigbuild --release --locked --target $triple -p pocket-agent --bin pocket-agent
    if ($LASTEXITCODE -ne 0) { throw 'Linux pocket-agent build failed.' }
    & cargo @cargo zigbuild --release --locked --target $triple --manifest-path crates/runtime-host/Cargo.toml --bin pocket-runtime-host
    if ($LASTEXITCODE -ne 0) { throw 'Linux pocket-runtime-host build failed.' }
    $bin = Join-Path $TargetDirectory "$triple\release"
    if (Test-Path -LiteralPath $staged) { Remove-Item -LiteralPath $staged -Recurse -Force }
    [void][IO.Directory]::CreateDirectory((Split-Path $staged))
    & $Python scripts/linux/package-runtime.py --bin $bin --host-bin $bin --output $staged --version $version --architecture $Architecture --signing-key $SigningKey
    if ($LASTEXITCODE -ne 0) { throw 'Linux runtime was not signed; the packaged assets were left as they were.' }
    # Only a complete, signed bundle takes the place of the one an APK would package.
    if (Test-Path -LiteralPath $assets) { Remove-Item -LiteralPath $assets -Recurse -Force }
    [void][IO.Directory]::CreateDirectory((Split-Path $assets))
    Move-Item -LiteralPath $staged -Destination $assets
    $bytes = (Get-ChildItem -LiteralPath $assets -File | Measure-Object Length -Sum).Sum
    Write-Host "Linux runtime $version ($Architecture, $bytes bytes) is ready for packaging: $assets"
}
finally {
    $env:CARGO_TARGET_DIR = $previousTarget
    Pop-Location
}
