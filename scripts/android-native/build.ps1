#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('arm64-v8a', 'x86_64')][string[]]$Abi = @('arm64-v8a', 'x86_64'),
    [ValidateSet('Check', 'Build')][string]$Action = 'Check',
    [ValidateSet('app-server', 'code-mode-host', 'v8', 'all')][string]$Component = 'all',
    [ValidateSet('debug', 'release')][string]$Profile = 'release',
    [string]$SourceRoot = 'E:\pocket-build\codex-0.171.0',
    [string]$CargoHome = 'E:\cn-cargo',
    [string]$TargetDir = 'E:\pocket-build\codex-target',
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [string]$V8ArtifactRoot,
    [string]$OpenSslArtifactRoot,
    [switch]$Offline,
    [int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$inputs = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'inputs.lock.json') -Raw | ConvertFrom-Json
& (Join-Path $PSScriptRoot 'prepare.ps1') -SourceRoot $SourceRoot -CargoHome $CargoHome
$TargetDir = [IO.Path]::GetFullPath($TargetDir)
if (-not $TargetDir.StartsWith('E:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep build output on E:.' }
$ndk = Join-Path $SdkRoot "ndk/$($inputs.ndk_version)"
$bin = Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$clang = Join-Path $bin 'clang.exe'
if (-not (Test-Path -LiteralPath $clang)) { throw "Missing pinned NDK $($inputs.ndk_version)." }
$installed = & rustup target list --installed --toolchain $inputs.rust_toolchain
if ($LASTEXITCODE -ne 0) { throw "Installed Rust $($inputs.rust_toolchain) is required; this script never installs it." }
$saved = @{}
function Set-BuildEnv([string]$Name, [AllowNull()][string]$Value) {
    if (-not $saved.ContainsKey($Name)) { $saved[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
$logs = Join-Path $TargetDir 'android-native-logs'
[void][IO.Directory]::CreateDirectory($logs)
Push-Location (Join-Path $SourceRoot 'codex-rs')
try {
    Set-BuildEnv CARGO_HOME $CargoHome
    Set-BuildEnv CARGO_TARGET_DIR $TargetDir
    Set-BuildEnv CARGO_NET_GIT_FETCH_WITH_CLI 'true'
    $gitCount = if ($env:GIT_CONFIG_COUNT) { [int]$env:GIT_CONFIG_COUNT } else { 0 }
    Set-BuildEnv "GIT_CONFIG_KEY_$gitCount" 'core.longpaths'
    Set-BuildEnv "GIT_CONFIG_VALUE_$gitCount" 'true'
    Set-BuildEnv GIT_CONFIG_COUNT ([string]($gitCount + 1))
    Set-BuildEnv RUSTFLAGS $null
    Set-BuildEnv CARGO_ENCODED_RUSTFLAGS $null
    $temp = Join-Path $TargetDir 'tmp'
    [void][IO.Directory]::CreateDirectory($temp)
    Set-BuildEnv TEMP $temp
    Set-BuildEnv TMP $temp
    $cmake = Join-Path $SdkRoot 'cmake/3.22.1/bin'
    Set-BuildEnv PATH "$cmake;$bin;$env:PATH"
    $perl = Join-Path $env:USERPROFILE 'scoop/apps/git/current/usr/bin/perl.exe'
    if (Test-Path -LiteralPath $perl) { Set-BuildEnv PERL $perl }
    foreach ($name in $Abi) {
        $target = $inputs.targets.$name.rust
        if ($target -notin $installed) { throw "Rust target $target is not installed." }
        $suffix = $target.Replace('-', '_')
        $upper = $suffix.ToUpperInvariant()
        $clangTarget = "$($inputs.targets.$name.clang)$($inputs.min_sdk)"
        Set-BuildEnv "CC_$suffix" (Join-Path $bin "$clangTarget-clang.cmd")
        Set-BuildEnv "CXX_$suffix" (Join-Path $bin "$clangTarget-clang++.cmd")
        Set-BuildEnv "AR_$suffix" (Join-Path $bin 'llvm-ar.exe')
        Set-BuildEnv "CARGO_TARGET_${upper}_LINKER" $clang
        Set-BuildEnv "CARGO_TARGET_${upper}_RUSTFLAGS" "-C link-arg=--target=$clangTarget -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
        if ($OpenSslArtifactRoot) {
            $openssl = Join-Path $OpenSslArtifactRoot $target
            foreach ($file in @('lib/libssl.a', 'lib/libcrypto.a', 'include/openssl/opensslv.h', 'openssl-build.json')) {
                if (-not (Test-Path -LiteralPath (Join-Path $openssl $file))) { throw "Missing target OpenSSL input: $openssl/$file" }
            }
            $sslReceipt = Get-Content -LiteralPath (Join-Path $openssl 'openssl-build.json') -Raw | ConvertFrom-Json
            if ($sslReceipt.status -cne 'built' -or $sslReceipt.target -cne $target -or
                $sslReceipt.ndk_version -cne $inputs.ndk_version -or $sslReceipt.min_sdk -ne $inputs.min_sdk -or
                $sslReceipt.openssl.crate_checksum -cne $inputs.openssl.crate_checksum) {
                throw "OpenSSL build receipt differs from the pinned Android inputs: $openssl"
            }
            Set-BuildEnv "${upper}_OPENSSL_DIR" $openssl
            Set-BuildEnv "${upper}_OPENSSL_STATIC" '1'
            Set-BuildEnv "${upper}_OPENSSL_NO_VENDOR" '1'
        }
        # Only use target V8 inputs built from the pinned fork with sandbox enabled.
        # Unset inherited shortcuts: a check that omits V8 is not acceptance.
        foreach ($key in @('DOCS_RS', 'DENO_TRYBUILD', 'RUSTY_V8_SKIP_DOWNLOAD', 'V8_FROM_SOURCE', 'RUSTY_V8_ARCHIVE', 'RUSTY_V8_SRC_BINDING_PATH')) {
            Set-BuildEnv $key $null
        }
        if ($V8ArtifactRoot) {
            $v8 = Join-Path $V8ArtifactRoot $target
            foreach ($file in @('librusty_v8.a', 'src_binding.rs', 'v8-build.json')) {
                if (-not (Test-Path -LiteralPath (Join-Path $v8 $file))) { throw "Missing matching V8 artifact: $v8/$file" }
            }
            $v8Receipt = Get-Content -LiteralPath (Join-Path $v8 'v8-build.json') -Raw | ConvertFrom-Json
            if ($v8Receipt.revision -cne $inputs.v8.commit -or $v8Receipt.target -cne $target -or
                $v8Receipt.ndk_version -cne $inputs.ndk_version -or $v8Receipt.min_sdk -ne $inputs.min_sdk -or
                'v8_enable_sandbox' -notin $v8Receipt.features) {
                throw "V8 build receipt does not match the pinned Android inputs: $v8"
            }
            Set-BuildEnv RUSTY_V8_ARCHIVE (Join-Path $v8 'librusty_v8.a')
            Set-BuildEnv RUSTY_V8_SRC_BINDING_PATH (Join-Path $v8 'src_binding.rs')
        }
        $cargoArgs = @("+$($inputs.rust_toolchain)", $Action.ToLowerInvariant(), '--locked', '--target', $target, '--jobs', [string]$Jobs)
        if ($Profile -eq 'release') { $cargoArgs += '--release' }
        if ($Offline) { $cargoArgs += '--offline' }
        if ($Component -in @('all', 'app-server')) { $cargoArgs += @('-p', 'codex-app-server', '--bin', 'codex-app-server') }
        if ($Component -in @('all', 'code-mode-host')) { $cargoArgs += @('-p', 'codex-code-mode-host', '--bin', 'codex-code-mode-host') }
        if ($Component -eq 'v8') { $cargoArgs += @('-p', 'v8', '--lib') }
        $log = Join-Path $logs "$name-$($Action.ToLowerInvariant())-$Component.log"
        "Codex $($inputs.codex.commit); ABI=$name; NDK=$($inputs.ndk_version); API=$($inputs.min_sdk)" | Set-Content -LiteralPath $log
        if ($Action -eq 'Build') {
            $receipts = Join-Path $TargetDir 'android-native-builds'
            [void][IO.Directory]::CreateDirectory($receipts)
            $receipt = [ordered]@{
                schema = 1
                codex_commit = $inputs.codex.commit
                source_patches = $inputs.source_patches
                target = $target
                ndk_version = $inputs.ndk_version
                rust_toolchain = $inputs.rust_toolchain
                min_sdk = $inputs.min_sdk
                profile = $Profile
                component = $Component
                status = 'building'
                built_at = [DateTime]::UtcNow.ToString('o')
                log = $log
            }
            $receipt | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $receipts "$name-$Profile.json") -Encoding utf8NoBOM
        }
        & cargo @cargoArgs 2>&1 | Tee-Object -FilePath $log -Append
        if ($LASTEXITCODE -ne 0) { throw "Native $Action failed for $name. Evidence: $log" }
        if ($Action -eq 'Build') {
            $receipt.status = 'built'
            $receipt | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $receipts "$name-$Profile.json") -Encoding utf8NoBOM
        }
    }
} finally {
    Pop-Location
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
}
