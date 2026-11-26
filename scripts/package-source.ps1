#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory, [string]$Version)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose an absent output directory to preserve existing delivery.' }
$files = @(& git -C $root ls-files --cached --others --exclude-standard | Sort-Object -Unique)
if ($LASTEXITCODE -ne 0) { throw 'Source inventory failed.' }
$forbidden = '(^|/)(\.git|\.local|\.research|dist|target|build|\.gradle|vendor|node_modules|\.playwright-mcp)(/|$)|\.(jks|keystore)$|(^|/)(password\.txt|local\.properties|signing\.properties)$'
if ($files | Where-Object { $_ -match $forbidden -or $_.Contains('..') }) { throw 'Private/generated path in source inventory.' }
Add-Type -AssemblyName System.IO.Compression
$versionMatch = [regex]::Match([IO.File]::ReadAllText((Join-Path $root 'Cargo.toml')),'(?m)^version\s*=\s*"([^"]+)"')
if (-not $Version) { $Version = $versionMatch.Groups[1].Value }
if ($version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Invalid source version.' }
[void][IO.Directory]::CreateDirectory($OutputDirectory)
$zipPath = Join-Path $OutputDirectory ('codex-pocket-' + $version + '-source.zip')
$stream = [IO.File]::Open($zipPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$archive = [IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
$inventory = [Collections.Generic.List[object]]::new()
$complete = $false
try {
    foreach ($relative in $files) {
        $path = Join-Path $root $relative
        if (-not [IO.File]::Exists($path)) { continue }
        $bytes = [IO.File]::ReadAllBytes($path)
        $text = [Text.Encoding]::UTF8.GetString($bytes)
        # Header-only assertions and "private-material" UI placeholders contain
        # no key. Require encoded key bytes, including escaped source newlines;
        # token checks still apply to every file, including tests.
        $privateKey = '-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----(?:\s|\\r|\\n)+[A-Za-z0-9+/=]{32,}'
        # Traditional encrypted PEM may put Proc-Type/DEK-Info before the bytes.
        $keyBlocks = [regex]::Matches($text, '(?s)-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----(.*?)-----END (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----')
        $keyMaterial = @($keyBlocks | Where-Object { $_.Groups[1].Value -match '[A-Za-z0-9+/=]{32,}' })
        if ($text -match '\bsk-[A-Za-z0-9_-]{35,}|\bAKIA[A-Z0-9]{16}\b' -or $text -match $privateKey -or $keyMaterial.Count) { throw 'Credential pattern found; source publication stopped.' }
        $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
        $entry = $archive.CreateEntry('codex-pocket/' + $relative.Replace('\','/'),[IO.Compression.CompressionLevel]::Optimal)
        $out = $entry.Open()
        try { $out.Write($bytes,0,$bytes.Length) } finally { $out.Dispose() }
        $inventory.Add([ordered]@{path=$relative.Replace('\','/');bytes=$bytes.Length;sha256=$hash})
    }
    $complete = $true
} finally {
    $archive.Dispose(); $stream.Dispose()
    # A rejected source must not leave a partially publishable archive.
    if (!$complete) { Remove-Item -LiteralPath $zipPath -Force }
}
[ordered]@{version=$version;createdAt=[DateTimeOffset]::UtcNow.ToString('o');files=$inventory.ToArray()} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'source-manifest.json') -Encoding utf8
[pscustomobject]@{sourceZip=$zipPath;sourceFiles=$inventory.Count;sha256=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()}
