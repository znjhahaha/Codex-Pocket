#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$SourceRoot = 'E:\pocket-build\codex-0.171.0',
    [string]$CargoHome = 'E:\cn-cargo',
    [string]$SeedCargoHome
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$inputs = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'inputs.lock.json') -Raw | ConvertFrom-Json
$SourceRoot = [IO.Path]::GetFullPath($SourceRoot)
$CargoHome = [IO.Path]::GetFullPath($CargoHome)
if (-not $SourceRoot.StartsWith('E:\', [StringComparison]::OrdinalIgnoreCase) -or
    -not $CargoHome.StartsWith('E:\', [StringComparison]::OrdinalIgnoreCase) -or $CargoHome.Length -gt 32) {
    throw 'Keep the source on E: and use a short isolated Cargo home on E: (32 characters maximum).'
}
if (-not (Test-Path -LiteralPath $SourceRoot)) {
    & git -c core.longpaths=true clone --depth 1 --branch $inputs.codex.tag $inputs.codex.repository $SourceRoot
    if ($LASTEXITCODE -ne 0) { throw 'Pinned Codex clone failed.' }
}
$head = & git -C $SourceRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $head -cne $inputs.codex.commit) {
    throw "Expected Codex $($inputs.codex.commit), found $head. No checkout/reset was performed."
}
foreach ($patchName in $inputs.source_patches) {
    $patch = Join-Path $PSScriptRoot $patchName
    & git -C $SourceRoot apply --reverse --check $patch 2>$null
    if ($LASTEXITCODE -eq 0) { continue }
    & git -C $SourceRoot apply --check $patch
    if ($LASTEXITCODE -ne 0) { throw "Patch does not apply to the current source: $patchName" }
    & git -C $SourceRoot apply $patch
    if ($LASTEXITCODE -ne 0) { throw "Patch application failed: $patchName" }
}
& python (Join-Path $PSScriptRoot 'prepare_lock.py') (Join-Path $SourceRoot 'codex-rs')
if ($LASTEXITCODE -ne 0) { throw 'Pinned release lockfile preparation failed.' }
[void][IO.Directory]::CreateDirectory($CargoHome)
if ($SeedCargoHome) {
    $SeedCargoHome = [IO.Path]::GetFullPath($SeedCargoHome)
    if ($SeedCargoHome -eq $CargoHome) { throw 'Seed and destination Cargo homes must differ.' }
    # Copy immutable package material only. Do not copy credentials/configuration,
    # live Cargo locks, or the original long-path source checkouts.
    foreach ($relative in @('registry/cache', 'registry/index', 'git/db')) {
        $from = Join-Path $SeedCargoHome $relative
        if (-not (Test-Path -LiteralPath $from)) { continue }
        & robocopy $from (Join-Path $CargoHome $relative) /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
        if ($LASTEXITCODE -ge 8) { throw "Dependency cache copy failed: $relative" }
    }
}
Write-Output "Prepared Codex $($inputs.codex.version) at $SourceRoot; isolated Cargo home: $CargoHome"
