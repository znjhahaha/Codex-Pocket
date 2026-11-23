#requires -Version 5.1
# Run directly with PowerShell. All build/signing inputs are synthetic and private
# to a temporary fixture; no Gradle, Cargo, model, or release publisher is run.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'build-release.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$script:passed = 0
$script:failed = 0
$script:requiredRuntime = @('libpocket-agent.so', 'libpocket-runtime-host.so', 'libpocket-runtime-manifest.so', 'libcodex.so')
$script:fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('pocket-release-runtime-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($script:fixtureRoot)

function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Test([string]$Name, [scriptblock]$Body) {
    try { & $Body; $script:passed++; Write-Output "PASS $Name" }
    catch { $script:failed++; Write-Output "FAIL $Name`: $($_.Exception.Message)" }
}
function Put([string]$Path, [string]$Text) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}
function New-RuntimeFixture([string[]]$Abis = @('arm64-v8a', 'x86_64')) {
    $directory = Join-Path $script:fixtureRoot ('runtime with spaces ' + [guid]::NewGuid().ToString('N'))
    foreach ($abi in $Abis) {
        foreach ($name in $script:requiredRuntime) {
            Put (Join-Path $directory ("jniLibs/$abi/$name")) "synthetic $abi $name"
        }
    }
    return $directory
}
function New-ApkFixture([string]$Runtime, [string[]]$Abis = @('arm64-v8a', 'x86_64'),
    [string]$Omit, [string]$Replace, [string]$Duplicate) {
    $path = Join-Path $script:fixtureRoot ([guid]::NewGuid().ToString('N') + '.apk')
    $zip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($abi in $Abis) {
            foreach ($file in Get-ChildItem -LiteralPath (Join-Path $Runtime "jniLibs/$abi") -File) {
                $entryName = "lib/$abi/$($file.Name)"
                if ($entryName -eq $Omit) { continue }
                $bytes = [IO.File]::ReadAllBytes($file.FullName)
                if ($entryName -eq $Replace) { $bytes[0] = $bytes[0] -bxor 1 }
                $copies = if ($entryName -eq $Duplicate) { 2 } else { 1 }
                for ($copy = 0; $copy -lt $copies; $copy++) {
                    $stream = $zip.CreateEntry($entryName).Open()
                    try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
                }
            }
        }
    } finally { $zip.Dispose() }
    return $path
}
function Require-BuildParameters {
    $parameters = (Get-Command Invoke-PocketReleaseBuild).Parameters
    Assert ($parameters.ContainsKey('OnDeviceRuntime')) 'Release build has no -OnDeviceRuntime parameter.'
    Assert ($parameters.ContainsKey('OnDeviceAbis')) 'Release build has no -OnDeviceAbis parameter.'
}
function Invoke-ContentGate([string]$Apk, [string]$Runtime, [string[]]$Abis) {
    Assert ([bool](Get-Command Assert-PocketOnDeviceApk -ErrorAction SilentlyContinue)) 'APK runtime content gate is missing.'
    Assert-PocketOnDeviceApk -ApkPath $Apk -RuntimeDirectory $Runtime -Abis $Abis | Out-Null
}
function Expect-ContentRejection([scriptblock]$Body, [string]$Reason) {
    Assert ([bool](Get-Command Assert-PocketOnDeviceApk -ErrorAction SilentlyContinue)) 'APK runtime content gate is missing.'
    $caught = $null
    try { & $Body } catch { $caught = $_ }
    Assert ($null -ne $caught) 'Unusable runtime APK was accepted.'
    Assert ($caught.Exception.Message -match $Reason) ("Unexpected failure: " + $caught.Exception.Message)
}

# Only external build/signing/publication boundaries are replaced. The release
# orchestrator, argument assembly and APK inspection remain production code.
function Get-PocketSourceDigest([string]$Root) { return 'unchanged synthetic source' }
function Get-PocketKeytoolPath { return $script:fakeKeytool }
function cargo { $script:cargoCalls++; $global:LASTEXITCODE = 0 }
function Invoke-PocketDesktopBuild { }
function New-PocketReleasePackage { $script:published++; return $null }

$script:buildRoot = Join-Path $script:fixtureRoot 'fake repository'
$script:fakeKeytool = Join-Path $script:buildRoot 'keytool.ps1'
$fakeSigner = Join-Path $script:buildRoot 'apksigner.ps1'
$fakeSigning = Join-Path $script:buildRoot 'signing'
$script:apkOutput = Join-Path $script:buildRoot 'android/app/build/outputs/apk/release/app-release.apk'
$script:argumentLog = Join-Path $script:buildRoot 'android/arguments.txt'
$script:buildArgs = @{ Root = $script:buildRoot; SigningDirectory = $fakeSigning;
    OutputDirectory = (Join-Path $script:buildRoot 'unpublished'); ApkSignerPath = $fakeSigner; SkipTests = $true }
Put (Join-Path $script:buildRoot 'Cargo.toml') "[workspace.package]`nversion = `"0.1.0`""
Put (Join-Path $script:buildRoot 'desktop/Cargo.toml') "[workspace.package]`nversion = `"0.1.0`""
Put (Join-Path $script:buildRoot 'desktop/package.json') '{"version":"0.1.0"}'
Put (Join-Path $script:buildRoot 'android/app/build.gradle.kts') 'versionName = "0.1.0"'
Put (Join-Path $fakeSigning 'release.jks') 'synthetic keystore'
Put (Join-Path $fakeSigning 'password.txt') 'synthetic password'
Put $script:fakeKeytool @'
Write-Output '-----BEGIN CERTIFICATE-----'
Write-Output 'Zml4dHVyZS1wdWJsaWMtY2VydGlmaWNhdGU='
Write-Output '-----END CERTIFICATE-----'
$global:LASTEXITCODE = 0
'@
$certificateDigest = Get-PocketBytesHash ([Text.Encoding]::UTF8.GetBytes('fixture-public-certificate'))
Put $fakeSigner ("Write-Output 'Number of signers: 1'`nWrite-Output 'Signer #1 certificate SHA-256 digest: $certificateDigest'`n" + '$global:LASTEXITCODE = 0')
foreach ($name in @('fetch-cloudflared.ps1', 'build-p2p-product.ps1')) {
    Put (Join-Path $script:buildRoot "scripts/$name") '$global:LASTEXITCODE = 0'
}
foreach ($name in @('pocket-p2p.dll', 'datachannel.dll')) {
    Put (Join-Path $script:buildRoot ".local/p2p/product-windows/Release/$name") 'synthetic transport'
}
[void][IO.Directory]::CreateDirectory((Join-Path $script:buildRoot 'target/x86_64-pc-windows-msvc/release'))
Put (Join-Path $script:buildRoot 'android/gradlew.bat') @'
@echo off
setlocal DisableDelayedExpansion
> "%~dp0arguments.txt" echo %*
exit /b 0
'@

function Read-GradleArguments {
    # cmd.exe treats '=' as a positional separator; preserve the raw command
    # line as Gradle's real batch wrapper does, then inspect quoted arguments.
    return @([regex]::Matches([IO.File]::ReadAllText($script:argumentLog), '(?:"[^"]*"|[^\s"]+)+') |
        ForEach-Object { $_.Value.Replace('"', '') })
}

function Invoke-FixtureBuild([string]$Apk, [hashtable]$Options = @{}) {
    $script:published = 0
    $script:cargoCalls = 0
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($script:apkOutput))
    [IO.File]::Copy($Apk, $script:apkOutput, $true)
    Invoke-PocketReleaseBuild @script:buildArgs @Options | Out-Null
}

$environment = @{}
foreach ($name in @('POCKET_EXPECTED_SIGNER', 'POCKET_BUILD_ID')) {
    $environment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
try {
    Test 'legacy release call keeps runtime opt-in and existing Gradle options' {
        $apk = Join-Path $script:fixtureRoot 'legacy.apk'
        Put $apk 'legacy synthetic APK without local runtime'
        Invoke-FixtureBuild $apk
        $arguments = Read-GradleArguments
        Assert ($script:published -eq 1) 'Legacy release did not reach packaging.'
        Assert ($arguments -contains ':app:assembleRelease') 'Release task changed.'
        Assert ($arguments -contains '-Pkotlin.compiler.execution.strategy=in-process') 'Compiler option changed.'
        Assert (@($arguments | Where-Object { $_ -like '-PpocketOnDevice*' }).Count -eq 0) 'Legacy build acquired runtime requirements.'
    }
    Test 'public script forwards optional runtime parameters to release orchestrator' {
        $tokens = $null; $errors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'build-release.ps1'), [ref]$tokens, [ref]$errors)
        Assert ($errors.Count -eq 0) 'Release script has parse errors.'
        $names = @($ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
        Assert ($names -contains 'OnDeviceRuntime' -and $names -contains 'OnDeviceAbis') 'Public script does not expose runtime path and ABIs.'
        $entry = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Invoke-PocketReleaseBuild' }, $true) | Select-Object -Last 1
        $OnDeviceRuntime = 'fixture runtime path'; $OnDeviceAbis = @('arm64-v8a', 'x86_64')
        $SigningDirectory = $fakeSigning; $OutputDirectory = $script:buildArgs.OutputDirectory
        $ApkSignerPath = $fakeSigner; $UpdateTrustPath = ''; $TargetDirectory = ''; $DesktopTargetDirectory = ''; $SkipTests = $true
        function Invoke-PocketReleaseBuild { param($OnDeviceRuntime, $OnDeviceAbis) [pscustomobject]@{Runtime=$OnDeviceRuntime; Abis=$OnDeviceAbis} }
        $captured = & ([scriptblock]::Create('param($PSScriptRoot)' + "`n" + $entry.Extent.Text)) $PSScriptRoot
        Assert ($captured.Runtime -eq $OnDeviceRuntime) 'Public entry dropped the runtime path.'
        Assert (($captured.Abis -join ',') -eq 'arm64-v8a,x86_64') 'Public entry dropped the ABI list.'
    }
    Test 'runtime path with spaces and selected ABIs reach Gradle as intact properties' {
        Require-BuildParameters
        $runtime = New-RuntimeFixture
        Invoke-FixtureBuild (New-ApkFixture $runtime) @{OnDeviceRuntime=$runtime; OnDeviceAbis=@('arm64-v8a','x86_64')}
        $arguments = Read-GradleArguments
        Assert (@($arguments | Where-Object { $_ -ceq "-PpocketOnDeviceRuntime=$runtime" }).Count -eq 1) 'Runtime property was split or omitted.'
        Assert (@($arguments | Where-Object { $_ -ceq '-PpocketOnDeviceAbis=arm64-v8a,x86_64' }).Count -eq 1) 'ABI property was omitted or changed.'
        Assert ($script:published -eq 1) 'Valid runtime APK was rejected.'
    }
    Test 'runtime without ABI selection packages all staged ABIs' {
        Require-BuildParameters
        $runtime = New-RuntimeFixture
        Invoke-FixtureBuild (New-ApkFixture $runtime) @{OnDeviceRuntime=$runtime}
        Assert ($script:published -eq 1) 'Complete staging without explicit ABIs was rejected.'
    }
    Test 'relative runtime path resolves before changing to the build repository' {
        Require-BuildParameters
        $runtime = New-RuntimeFixture @('x86_64')
        $apk = New-ApkFixture $runtime @('x86_64')
        Push-Location $script:fixtureRoot
        try { Invoke-FixtureBuild $apk @{OnDeviceRuntime=[IO.Path]::GetFileName($runtime); OnDeviceAbis=@('x86_64')} }
        finally { Pop-Location }
        $arguments = Read-GradleArguments
        Assert ($arguments -contains "-PpocketOnDeviceRuntime=$runtime") 'Relative runtime path did not resolve from the caller location.'
    }
    Test 'selected x86_64 ABI does not require arm64 entries in APK' {
        Require-BuildParameters
        $runtime = New-RuntimeFixture
        Invoke-FixtureBuild (New-ApkFixture $runtime @('x86_64')) @{OnDeviceRuntime=$runtime; OnDeviceAbis=@('x86_64')}
        Assert ($script:published -eq 1) 'Single-ABI package was rejected.'
    }
    Test 'APK content gate is wired before publication' {
        Require-BuildParameters
        $runtime = New-RuntimeFixture
        $apk = New-ApkFixture $runtime -Omit 'lib/arm64-v8a/libcodex.so'
        $caught = $null
        try { Invoke-FixtureBuild $apk @{OnDeviceRuntime=$runtime; OnDeviceAbis=@('arm64-v8a','x86_64')} } catch { $caught=$_ }
        Assert ($null -ne $caught) 'Release pipeline accepted an APK missing its runtime.'
        Assert ($caught.Exception.Message -match 'libcodex|runtime') 'Failure did not identify missing runtime content.'
        Assert ($script:published -eq 0) 'Invalid runtime APK reached publication.'
    }
    Test 'ABI selection without a runtime path fails before external builds' {
        Require-BuildParameters
        $apk = Join-Path $script:fixtureRoot 'unused.apk'; Put $apk 'synthetic'
        $caught = $null
        try { Invoke-FixtureBuild $apk @{OnDeviceAbis=@('x86_64')} } catch { $caught=$_ }
        Assert ($null -ne $caught) 'ABI selection without staging was accepted.'
        Assert ($caught.Exception.Message -match 'runtime|OnDeviceRuntime') 'Failure did not explain the missing runtime path.'
        Assert ($script:cargoCalls -eq 0 -and $script:published -eq 0) 'Invalid runtime input started external builds.'
    }
    Test 'unsupported ABI fails before external builds' {
        Require-BuildParameters
        $runtime = New-RuntimeFixture
        $caught = $null
        try { Invoke-FixtureBuild (New-ApkFixture $runtime) @{OnDeviceRuntime=$runtime; OnDeviceAbis=@('mips')} } catch { $caught=$_ }
        Assert ($null -ne $caught) 'Unsupported ABI was accepted.'
        Assert ($caught.Exception.Message -match 'ABI|mips') 'Failure did not identify unsupported ABI.'
        Assert ($script:cargoCalls -eq 0 -and $script:published -eq 0) 'Invalid ABI started external builds.'
    }
    Test 'incomplete selected staging fails before external builds' {
        Require-BuildParameters
        $runtime = New-RuntimeFixture @('x86_64')
        Put (Join-Path $runtime 'jniLibs/arm64-v8a/libcodex.so') 'synthetic arm64 codex only'
        $caught = $null
        try { Invoke-FixtureBuild (New-ApkFixture $runtime @('x86_64')) @{OnDeviceRuntime=$runtime; OnDeviceAbis=@('arm64-v8a','x86_64')} } catch { $caught=$_ }
        Assert ($null -ne $caught) 'Partially staged arm64 runtime was accepted.'
        Assert ($caught.Exception.Message -match 'arm64|libpocket') 'Failure did not identify incomplete staging.'
        Assert ($script:cargoCalls -eq 0 -and $script:published -eq 0) 'Incomplete staging started external builds.'
    }
    Test 'content gate accepts matching staged bytes for every selected ABI' {
        $runtime = New-RuntimeFixture
        Invoke-ContentGate (New-ApkFixture $runtime) $runtime @('arm64-v8a','x86_64')
    }
    Test 'content gate rejects an APK missing one selected ABI' {
        $runtime = New-RuntimeFixture
        $apk = New-ApkFixture $runtime @('x86_64')
        Expect-ContentRejection { Invoke-ContentGate $apk $runtime @('arm64-v8a','x86_64') } 'arm64|runtime'
    }
    foreach ($runtimeFile in $script:requiredRuntime) {
        Test "content gate rejects missing $runtimeFile" {
            $runtime = New-RuntimeFixture @('x86_64')
            $apk = New-ApkFixture $runtime @('x86_64') -Omit "lib/x86_64/$runtimeFile"
            Expect-ContentRejection { Invoke-ContentGate $apk $runtime @('x86_64') } ([regex]::Escape($runtimeFile))
        }
    }
    Test 'content gate rejects same-length runtime byte substitution' {
        $runtime = New-RuntimeFixture @('x86_64')
        $apk = New-ApkFixture $runtime @('x86_64') -Replace 'lib/x86_64/libpocket-agent.so'
        Expect-ContentRejection { Invoke-ContentGate $apk $runtime @('x86_64') } 'libpocket-agent|mismatch|differ'
    }
    Test 'content gate rejects ambiguous duplicate runtime entries' {
        $runtime = New-RuntimeFixture @('x86_64')
        $apk = New-ApkFixture $runtime @('x86_64') -Duplicate 'lib/x86_64/libcodex.so'
        Expect-ContentRejection { Invoke-ContentGate $apk $runtime @('x86_64') } 'duplicate|ambiguous|libcodex'
    }
    Test 'content gate includes additional staged runtime helpers' {
        $runtime = New-RuntimeFixture @('x86_64')
        Put (Join-Path $runtime 'jniLibs/x86_64/libcodex-code-mode-host.so') 'synthetic helper'
        $apk = New-ApkFixture $runtime @('x86_64') -Omit 'lib/x86_64/libcodex-code-mode-host.so'
        Expect-ContentRejection { Invoke-ContentGate $apk $runtime @('x86_64') } 'libcodex-code-mode-host'
    }
} finally {
    foreach ($name in $environment.Keys) { [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process') }
    $resolved = [IO.Path]::GetFullPath($script:fixtureRoot)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^pocket-release-runtime-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Output "$script:passed passed; $script:failed failed. Synthetic orchestration and APK content only."
if ($script:failed -gt 0) { exit 1 }
