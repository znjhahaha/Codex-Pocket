#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Release directory must not already exist.' }
if (-not $env:POCKET_SIGNING_KEY_BASE64 -or -not $env:POCKET_SIGNING_PASSWORD -or $env:POCKET_EXPECTED_SIGNER -notmatch '^[A-Fa-f0-9]{64}$') {
    throw 'Configure the original Android signing key/password secrets and POCKET_EXPECTED_SIGNER repository variable. No replacement key will be created.'
}
$signing=Join-Path ([IO.Path]::GetTempPath()) ('pocket-signing-'+[guid]::NewGuid().ToString('N'))
$previousRuntimeTestHost=$env:MANAGED_RUNTIME_TEST_HOST
[void][IO.Directory]::CreateDirectory($signing)
try {
    [IO.File]::WriteAllBytes((Join-Path $signing 'release.jks'),[Convert]::FromBase64String($env:POCKET_SIGNING_KEY_BASE64))
    [IO.File]::WriteAllText((Join-Path $signing 'password.txt'),$env:POCKET_SIGNING_PASSWORD,[Text.UTF8Encoding]::new($false))
    Remove-Item Env:POCKET_SIGNING_KEY_BASE64
    Remove-Item Env:POCKET_SIGNING_PASSWORD
    Push-Location $root
    try {
        # Managed-runtime tests copy a real isolated host and run fake native peers.
        # Match the ordinary Checks job instead of depending on a runner-local host.
        & cargo fetch --locked --manifest-path crates/runtime-host/Cargo.toml
        if ($LASTEXITCODE -ne 0) { throw 'Runtime Host locked dependency fetch failed.' }
        & cargo fetch --locked --manifest-path crates/runtime-ipc/Cargo.toml
        if ($LASTEXITCODE -ne 0) { throw 'Runtime IPC locked dependency fetch failed.' }
        & cargo build --locked --manifest-path crates/runtime-host/Cargo.toml --all-features --bins --target-dir crates/runtime-host/target
        if ($LASTEXITCODE -ne 0) { throw 'Runtime Host fixture build failed.' }
        $env:MANAGED_RUNTIME_TEST_HOST=Join-Path $root 'crates/runtime-host/target/debug/pocket-runtime-host.exe'
        & cargo test --locked --workspace
        if ($LASTEXITCODE -ne 0) { throw 'Native regression tests failed.' }
        & cargo test --locked --manifest-path desktop/Cargo.toml -p pocket-desktop-runtime
        if ($LASTEXITCODE -ne 0) { throw 'Desktop runtime tests failed.' }
        & ./android/gradlew.bat -p android :wire:test :app:testDebugUnitTest :app:lintDebug --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Android host checks failed.' }
        & ./scripts/build-release.ps1 -SigningDirectory $signing -OutputDirectory $OutputDirectory
        & cargo test --locked --manifest-path desktop/Cargo.toml -p pocket-desktop
        if ($LASTEXITCODE -ne 0) { throw 'Desktop native command checks failed.' }
        $manifest=Get-Content -LiteralPath (Join-Path $OutputDirectory 'release-manifest.json') -Raw | ConvertFrom-Json
        if ($manifest.build.apkSignerSha256 -ne $env:POCKET_EXPECTED_SIGNER.ToLowerInvariant()) { throw 'APK signer differs from the approved original certificate.' }
        & ./scripts/build-windows-installer.ps1 -ReleaseDirectory $OutputDirectory -OutputDirectory $OutputDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Windows installer creation failed.' }
        $source=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($OutputDirectory))) ('pocket-source-'+[guid]::NewGuid().ToString('N'))
        & ./scripts/package-source.ps1 -OutputDirectory $source
        Get-ChildItem -LiteralPath $source -File | Copy-Item -Destination $OutputDirectory
        $files=Get-ChildItem -LiteralPath $OutputDirectory -File | Sort-Object Name
        if (-not ($files | Where-Object Name -Like '*setup*.exe')) { throw 'Setup.exe is required for a release.' }
        $hashes=$files|ForEach-Object { '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(),$_.Name }
        [IO.File]::WriteAllLines((Join-Path $OutputDirectory 'SHA256SUMS.txt'),[string[]]$hashes,[Text.UTF8Encoding]::new($false))
    } finally { Pop-Location }
} finally {
    $env:MANAGED_RUNTIME_TEST_HOST=$previousRuntimeTestHost
    # This exact temporary directory was created above; remove only its two known secret files.
    foreach($name in @('release.jks','password.txt')) {
        $path=Join-Path $signing $name
        if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path -Force}
    }
    if(Test-Path -LiteralPath $signing){Remove-Item -LiteralPath $signing}
}
