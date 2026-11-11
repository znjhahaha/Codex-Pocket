param([switch]$Broker, [string]$TestFilter = '')
$ErrorActionPreference = 'Stop'
$handoffRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$handoffDeps = Join-Path $handoffRepo 'target/debug/deps'
$handoffArtifacts = Join-Path $handoffRepo '.local/agent-handoff-tests'
[IO.Directory]::CreateDirectory($handoffArtifacts) | Out-Null
# Reuse one coherent Cargo dependency graph, rather than selecting arbitrary
# newest rlibs with incompatible serde/tokio features. No Cargo/Git mutation.
$handoffAgentLib = Get-ChildItem $handoffDeps -Filter 'libpocket_agent-*.rlib' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$handoffAgentLib) { throw 'Build the agent dependencies once before running this directed harness.' }
$handoffAgentHash = $handoffAgentLib.BaseName.Substring('libpocket_agent-'.Length)
$handoffFingerprintRoot = Join-Path $handoffRepo 'target/debug/.fingerprint'
$handoffGraph = Get-Content (Join-Path $handoffFingerprintRoot "pocket-agent-$handoffAgentHash/lib-pocket_agent.json") -Raw | ConvertFrom-Json
$handoffArgs = @('--edition=2024','-L',"dependency=$handoffDeps")
foreach ($handoffDependency in $handoffGraph.deps) {
    $handoffName = [string]$handoffDependency[1]
    $handoffBytes = [BitConverter]::GetBytes([UInt64]$handoffDependency[3])
    $handoffValue = [Convert]::ToHexString($handoffBytes).ToLowerInvariant()
    $handoffMatch = Get-ChildItem $handoffFingerprintRoot -Directory | Where-Object {
        $handoffMark = Join-Path $_.FullName "lib-$handoffName"
        (Test-Path -LiteralPath $handoffMark) -and (Get-Content -LiteralPath $handoffMark -Raw) -eq $handoffValue
    } | Where-Object { Test-Path (Join-Path $handoffDeps "lib$handoffName-$($_.Name.Substring($_.Name.LastIndexOf('-')+1)).rlib") } | Select-Object -First 1
    if (!$handoffMatch) { throw "No coherent artifact for $handoffName" }
    $handoffHash = $handoffMatch.Name.Substring($handoffMatch.Name.LastIndexOf('-')+1)
    $handoffArgs += @('--extern',"$handoffName=$(Join-Path $handoffDeps "lib$handoffName-$handoffHash.rlib")")
}
$handoffArgs += @('--extern',"pocket_agent=$($handoffAgentLib.FullName)")
$env:CARGO_MANIFEST_DIR = Join-Path $handoffRepo 'crates/agent'
$env:CARGO_PKG_VERSION = '0.6.3'
if ($Broker) {
    # A concurrently added P2P module can precede a Cargo rebuild. Compile its
    # real library directly against the same tokio graph; never alter manifests.
    if (!(($handoffGraph.deps | ForEach-Object { $_[1] }) -contains 'pocket_p2p') -and (Test-Path (Join-Path $handoffRepo 'crates/agent/src/p2p.rs'))) {
        $handoffLoading = Get-ChildItem $handoffDeps -Filter 'liblibloading-*.rlib' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (!$handoffLoading) { throw 'P2P requires an existing libloading dependency artifact.' }
        $handoffP2p = Join-Path $handoffArtifacts 'libpocket_p2p_handoff.rlib'
        & rustc @handoffArgs --extern "libloading=$($handoffLoading.FullName)" --crate-type rlib --crate-name pocket_p2p (Join-Path $handoffRepo 'crates/p2p/src/lib.rs') -o $handoffP2p
        if ($LASTEXITCODE -ne 0) { throw 'P2P fixture dependency compilation failed' }
        $handoffArgs += @('--extern',"pocket_p2p=$handoffP2p",'-L',"dependency=$handoffArtifacts")
    }
    # Add only the declarations assigned to the main controller, in disposable
    # test copies. The actual Broker/State/Runtime and adapter bodies compile.
    $handoffSrc = Join-Path $handoffRepo 'crates/agent/src'
    $handoffBrokerText = Get-Content (Join-Path $handoffSrc 'broker.rs') -Raw
    $handoffBrokerText = [regex]::Replace($handoffBrokerText,'#\[path\s*=\s*"([^"]+)"\]', {
        param($m)
        $p=[IO.Path]::GetFullPath((Join-Path $handoffSrc $m.Groups[1].Value)).Replace('\','/')
        "#[path = `"$p`"]"
    })
    foreach ($handoffModule in @('v051','session_access')) {
        $handoffPath = (Join-Path $handoffSrc "broker/$handoffModule.rs").Replace('\','/')
        $handoffBrokerText = $handoffBrokerText.Replace("mod $handoffModule;", "#[path = `"$handoffPath`"] mod $handoffModule;")
    }
    $handoffAdapterPath = (Join-Path $handoffSrc 'broker_handoff.rs').Replace('\','/')
    if (!$handoffBrokerText.Contains('broker_handoff.rs')) { $handoffBrokerText += "`n#[path = `"$handoffAdapterPath`"] mod broker_handoff;`n" }
    $handoffBrokerCopy = Join-Path $handoffArtifacts 'broker_fixture.rs'
    [IO.File]::WriteAllText($handoffBrokerCopy,$handoffBrokerText)
    $handoffLibText = Get-Content (Join-Path $handoffSrc 'lib.rs') -Raw
    $handoffLibText = [regex]::Replace($handoffLibText,'#\[path\s*=\s*"([^"]+)"\]', {
        param($m)
        $p=[IO.Path]::GetFullPath((Join-Path $handoffSrc $m.Groups[1].Value)).Replace('\','/')
        "#[path = `"$p`"]"
    })
    $handoffLibText = [regex]::Replace($handoffLibText,'(?m)^(pub\s+)?mod\s+(\w+);', {
        param($m)
        $n=$m.Groups[2].Value
        if ($n -eq 'broker') { $p=$handoffBrokerCopy }
        elseif (Test-Path (Join-Path $handoffSrc "$n.rs")) { $p=Join-Path $handoffSrc "$n.rs" }
        elseif (Test-Path (Join-Path $handoffSrc "$n/mod.rs")) { $p=Join-Path $handoffSrc "$n/mod.rs" }
        else { return $m.Value } # Existing explicit #[path] remains authoritative.
        "#[path = `"$($p.Replace('\','/'))`"] $($m.Value)"
    })
    $handoffPath=(Join-Path $handoffSrc 'handoff.rs').Replace('\','/')
    if ($handoffLibText -notmatch '\bmod handoff;') { $handoffLibText += "`n#[path = `"$handoffPath`"] pub mod handoff;`n" }
    $handoffInput=Join-Path $handoffArtifacts 'lib_fixture.rs'
    [IO.File]::WriteAllText($handoffInput,$handoffLibText)
    $handoffOutput=Join-Path $handoffArtifacts "broker_handoff_tests-$([Guid]::NewGuid().ToString('N')).exe"
    & rustc @handoffArgs --test $handoffInput --crate-name pocket_agent_handoff_fixture -o $handoffOutput
    if ($LASTEXITCODE -ne 0) { throw 'Broker fixture compilation failed' }
    & $handoffOutput 'broker_handoff::tests::' --nocapture
} else {
    $handoffOutput=Join-Path $handoffArtifacts "agent_handoff_tests-$([Guid]::NewGuid().ToString('N')).exe"
    & rustc @handoffArgs --test (Join-Path $PSScriptRoot 'agent_handoff.rs') --crate-name agent_handoff_directed -o $handoffOutput
    if ($LASTEXITCODE -ne 0) { throw 'Handoff compilation failed' }
    if ($TestFilter) { & $handoffOutput $TestFilter --nocapture } else { & $handoffOutput --nocapture }
}
if ($LASTEXITCODE -ne 0) { throw 'Directed handoff tests failed' }
