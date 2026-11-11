param([string]$Deps = (Join-Path $PSScriptRoot '..\..\..\..\target\debug\deps'), [switch]$LiveProbe)
$ErrorActionPreference = 'Stop'
$Deps = [IO.Path]::GetFullPath($Deps)
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('pocket-usage-tests-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($scratch)
try {
    $source = if ($LiveProbe) { 'live_probe.rs' } else { 'standalone_tests.rs' }
    $rustArgs = @('--edition=2024', (Join-Path $PSScriptRoot $source), '-L', "dependency=$Deps", '-o', (Join-Path $scratch 'usage-tests.exe'))
    if (-not $LiveProbe) { $rustArgs += '--test' }
    $agent = Get-ChildItem -LiteralPath $Deps -Filter 'libpocket_agent-*.rlib' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $agent) { throw 'Missing cached pocket-agent library. Run the host offline build first.' }
    $agentHash = $agent.BaseName -replace '^libpocket_agent-', ''
    $fingerprints = [IO.Path]::GetFullPath((Join-Path $Deps '..\.fingerprint'))
    $graph = Get-Content -LiteralPath (Join-Path $fingerprints "pocket-agent-$agentHash/lib-pocket_agent.json") -Raw | ConvertFrom-Json
    foreach ($dep in @('rusqlite','serde','serde_json','anyhow','tempfile','windows_sys','rand','hex','dunce','sha2','tokio')) {
        $lib = $null
        $expected = ($graph.deps | Where-Object { $_[1] -eq $dep })[3].ToString()
        $packageName = if ($dep -eq 'windows_sys') { 'windows-sys' } else { $dep }
        foreach ($folder in Get-ChildItem -LiteralPath $fingerprints -Directory -Filter "$packageName-*") {
            $stampPath = Join-Path $folder.FullName "lib-$dep"
            if (-not (Test-Path -LiteralPath $stampPath)) { continue }
            $stamp = Get-Item -LiteralPath $stampPath
            $hexStamp = (Get-Content -LiteralPath $stamp.FullName -Raw).Trim()
            if ([BitConverter]::ToUInt64([Convert]::FromHexString($hexStamp),0).ToString() -eq $expected) {
                $hash = $stamp.Directory.Name.Split('-')[-1]
                $candidate = Join-Path $Deps "lib$dep-$hash.rlib"
                if (Test-Path -LiteralPath $candidate) { $lib = Get-Item -LiteralPath $candidate; break }
            }
        }
        if (-not $lib) { throw "Missing cached dependency: $dep. Run the host offline build first." }
        $rustArgs += @('--extern', "$dep=$($lib.FullName)")
    }
    Write-Output $(if ($LiveProbe) { 'Compiling the explicit current-user read-only aggregate probe.' } else { 'Compiling standalone usage fixtures from cached dependencies.' })
    Get-ChildItem (Join-Path $Deps '..\build\*\out') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $rustArgs += @('-L', "native=$($_.FullName)") }
    & rustc @rustArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($LiveProbe) { & (Join-Path $scratch 'usage-tests.exe') }
    else { & (Join-Path $scratch 'usage-tests.exe') '--skip' 'storage::tests::' '--test-threads=1' }
    $testExit = $LASTEXITCODE
} finally {
    # Only the exact temporary directory created above is removed.
    if ([IO.Path]::GetFullPath($scratch).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $scratch -Recurse -Force
    }
}
exit $testExit
