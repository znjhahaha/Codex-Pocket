#requires -Version 7.0
# Synthetic repositories only; no signing identity or repository publication.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('pocket-source-fixtures-' + [guid]::NewGuid().ToString('N'))
$repo = Join-Path $fixture 'repo'
[void][IO.Directory]::CreateDirectory((Join-Path $repo 'scripts'))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'package-source.ps1') -Destination (Join-Path $repo 'scripts/package-source.ps1')
& git -C $repo init --quiet
if ($LASTEXITCODE) { throw 'Synthetic repository initialization failed' }
[IO.File]::WriteAllText((Join-Path $repo 'Cargo.toml'), "[workspace.package]`nversion = `"0.7.1-rc.20261126.56`"")
$sample = Join-Path $repo 'fixture.txt'
$passed = 0; $failed = 0
function Assert($Value, $Message) { if (!$Value) { throw $Message } }
function Test($Name, [scriptblock]$Action) {
    try { & $Action; $script:passed++; Write-Output "PASS $Name" }
    catch { $script:failed++; Write-Output "FAIL $Name`: $($_.Exception.Message)" }
}
function Package([string]$Version) {
    $destination = Join-Path $fixture ([guid]::NewGuid().ToString('N'))
    try { & (Join-Path $repo 'scripts/package-source.ps1') -OutputDirectory $destination -Version $Version }
    catch {
        Assert (@(Get-ChildItem -LiteralPath $destination -Filter '*.zip' -ErrorAction SilentlyContinue).Count -eq 0) 'Rejected source left an archive'
        throw
    }
}
Test 'stable and prerelease source versions preserve their complete identity' {
    [IO.File]::WriteAllText($sample, 'ordinary source')
    foreach ($version in @('1.2.3', '0.7.1-rc.20261126.56')) {
        $result = Package $version
        Assert ([IO.Path]::GetFileName($result.sourceZip) -eq "codex-pocket-$version-source.zip") 'Archive version changed'
        $manifest = Get-Content -LiteralPath (Join-Path ([IO.Path]::GetDirectoryName($result.sourceZip)) 'source-manifest.json') -Raw | ConvertFrom-Json
        Assert ($manifest.version -eq $version) 'Manifest version changed'
    }
}
Test 'invalid or path-like versions cannot publish an archive' {
    foreach ($version in @('1.2', '../1.2.3', '1.2.3/extra', '1.2.3-')) {
        $rejected = $false
        try { Package $version | Out-Null } catch { $rejected = $_.Exception.Message -eq 'Invalid source version.' }
        Assert $rejected 'Invalid source version was not rejected'
    }
}
$begin = '-----BEGIN ' + 'OPENSSH PRIVATE KEY-----'
$end = '-----END ' + 'OPENSSH PRIVATE KEY-----'
Test 'PEM header assertions and non-key UI fixture text remain publishable' {
    [IO.File]::WriteAllText($sample, "assertTrue(key.startsWith(`"$begin`"))`nval key = `"$begin\nprivate-material\n$end\n`"")
    Assert ((Package '1.2.3').sourceFiles -gt 0) 'Harmless source fixture was rejected'
}
Test 'encoded private material is rejected in raw and escaped source strings' {
    foreach ($kind in @('', 'RSA ', 'EC ', 'OPENSSH ', 'ENCRYPTED ')) {
        $header = '-----BEGIN ' + $kind + 'PRIVATE KEY-----'
        foreach ($separator in @("`n", '\n', '\r\n')) {
            [IO.File]::WriteAllText($sample, $header + $separator + ('A' * 64))
            $rejected = $false
            try { Package '1.2.3' | Out-Null } catch { $rejected = $_.Exception.Message -match 'Credential pattern' }
            Assert $rejected 'Private key content was not rejected'
        }
    }
}
Test 'API and AWS credential patterns remain rejected' {
    foreach ($value in @(('sk-' + ('a' * 40)), ('AKIA' + ('A' * 16)))) {
        [IO.File]::WriteAllText($sample, $value)
        $rejected = $false
        try { Package '1.2.3' | Out-Null } catch { $rejected = $_.Exception.Message -match 'Credential pattern' }
        Assert $rejected 'Credential token was not rejected'
    }
}
Test 'encrypted PEM metadata cannot conceal encoded key bytes' {
    $header = '-----BEGIN ' + 'RSA PRIVATE KEY-----'
    $footer = '-----END ' + 'RSA PRIVATE KEY-----'
    [IO.File]::WriteAllText($sample, "$header`nProc-Type: 4,ENCRYPTED`nDEK-Info: AES-256-CBC,fixture`n`n" + ('A' * 64) + "`n$footer")
    $rejected = $false
    try { Package '1.2.3' | Out-Null } catch { $rejected = $_.Exception.Message -match 'Credential pattern' }
    Assert $rejected 'Encrypted PEM content was not rejected'
}
Write-Output "$passed passed; $failed failed; synthetic repositories retained at $fixture"
if ($failed) { exit 1 }
