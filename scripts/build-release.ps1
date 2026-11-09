#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '..\.local\signing'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist'),
    [string]$ApkSignerPath
)
. (Join-Path $PSScriptRoot 'windows-common.ps1')

function New-PocketReleasePackage {
    [CmdletBinding()]
    param([string]$Root,[string]$BinaryDirectory,[string]$DesktopBinaryDirectory,[string]$ApkPath,[string]$OutputDirectory,[string]$Version,[hashtable]$BuildEvidence)
    $ErrorActionPreference = 'Stop'
    if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Invalid release version.' }
    if (-not $BuildEvidence['builtThisInvocation'] -and -not $BuildEvidence['fixture']) { throw 'Release packaging requires a fresh build; only isolated tests may use fixture artifacts.' }
    $OutputDirectory = Get-PocketDirectoryPath $OutputDirectory
    Assert-PocketPlainPath $OutputDirectory
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory already exists; select a new -OutputDirectory to preserve the previous release.' }
    $files = [ordered]@{}
    if (-not $DesktopBinaryDirectory) { $DesktopBinaryDirectory=Join-Path $Root 'desktop\target\release' }
    foreach ($name in Get-PocketWindowsFiles) {
        $source = if ($name -in @('cloudflared.exe','cloudflared-LICENSE.txt')) { Join-Path $Root ('.local\tools\' + $name) }
            elseif ($name -in @('pocket-desktop.exe','pocket-supervisor.exe','pocket-launcher.exe')) { Join-Path $DesktopBinaryDirectory $name }
            elseif ($name -like '*.exe') { Join-Path $BinaryDirectory $name }
            elseif ($name -like '*.md') { Join-Path $Root ('docs\' + $name) }
            else { Join-Path $Root ('scripts\' + $name) }
        if (-not (Get-PocketFileHash $source)) { throw ('Missing delivery input: ' + $name) }
        $files[$name] = $source
    }
    if (-not (Get-PocketFileHash $ApkPath)) { throw 'Signed release APK missing.' }
    $stage = $OutputDirectory + '.staging-' + [guid]::NewGuid().ToString('N')
    Assert-PocketPlainPath $stage
    [void][IO.Directory]::CreateDirectory((Join-Path $stage 'windows'))
    foreach ($name in $files.Keys) { [IO.File]::Copy($files[$name],(Join-Path $stage ('windows\' + $name)),$false) }
    $windows = Join-Path $stage 'windows'
    Write-PocketJson (Join-Path $windows 'windows-manifest.json') ([ordered]@{schema=1;product='Codex Pocket';version=$Version;files=(Get-PocketInventory $windows (Get-PocketWindowsFiles))})
    Test-PocketWindowsDirectory $windows | Out-Null
    $apkName = 'codex-pocket-' + $Version + '.apk'
    [IO.File]::Copy($ApkPath,(Join-Path $stage $apkName),$false)
    if ($BuildEvidence['apkSha256'] -and (Get-PocketFileHash (Join-Path $stage $apkName)) -ne $BuildEvidence['apkSha256']) { throw 'APK changed after signature verification; staged package retained, release not published.' }
    $zipName = 'codex-pocket-' + $Version + '-windows.zip'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($windows,(Join-Path $stage $zipName))
    $names = @((Get-PocketWindowsFiles) | ForEach-Object {'windows/' + $_}) + @('windows/windows-manifest.json',$apkName,$zipName)
    Write-PocketJson (Join-Path $stage 'release-manifest.json') ([ordered]@{
        schema=1;product='Codex Pocket';version=$Version;createdAt=[DateTimeOffset]::UtcNow.ToString('o')
        build=$BuildEvidence;files=(Get-PocketInventory $stage $names)
    })
    Test-PocketReleaseDirectory $stage | Out-Null
    Assert-PocketPlainPath $OutputDirectory
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory appeared during packaging; staged package retained.' }
    if ([IO.Path]::GetDirectoryName($stage) -ne [IO.Path]::GetDirectoryName($OutputDirectory)) { throw 'Invalid publication target.' }
    Move-PocketStagedDirectory $stage $OutputDirectory
    return [pscustomobject]@{directory=$OutputDirectory;manifest=(Join-Path $OutputDirectory 'release-manifest.json');files=$names.Count}
}
function Find-PocketApkSigner([string]$Root) {
    $command = Get-Command apksigner.bat -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    $properties = Join-Path $Root 'android\local.properties'
    if (-not $sdk -and [IO.File]::Exists($properties)) {
        $line = Get-Content -LiteralPath $properties | Where-Object {$_ -match '^sdk\.dir='} | Select-Object -First 1
        if ($line) { $sdk = $line.Substring(8).Replace('\:',':').Replace('\\','\') }
    }
    if ($sdk) {
        $candidates = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
            Where-Object {$_.Name -match '^\d+\.\d+\.\d+$'} | Sort-Object {[version]$_.Name} -Descending)
        foreach ($candidate in $candidates) {
            $path = Join-Path $candidate.FullName 'apksigner.bat'
            if ([IO.File]::Exists($path)) { return $path }
        }
    }
    throw 'apksigner.bat is required; set -ApkSignerPath or the Android SDK path.'
}
function Get-PocketKeytoolPath { return (Get-Command keytool.exe -ErrorAction Stop).Source }
function Invoke-PocketDesktopBuild([string]$Root,[string]$Target) {
    Push-Location (Join-Path $Root 'desktop')
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Desktop locked dependency installation failed.' }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'Desktop frontend tests failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Desktop frontend build failed.' }
        # custom-protocol embeds the bundle; no development server or remote origin.
        & cargo build --manifest-path (Join-Path $Root 'desktop\Cargo.toml') --workspace --release --locked --features pocket-desktop/custom-protocol --target-dir (Join-Path $Root 'desktop\target')
        if ($LASTEXITCODE -ne 0) { throw 'Desktop Rust Release build failed.' }
    } finally { Pop-Location }
}
function Invoke-PocketReleaseBuild {
    [CmdletBinding()]
    param([string]$Root,[string]$SigningDirectory,[string]$OutputDirectory,[string]$ApkSignerPath)
    $ErrorActionPreference = 'Stop'
    $OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
    Assert-PocketPlainPath $OutputDirectory
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory already exists; select a new -OutputDirectory.' }
    $SigningDirectory = [IO.Path]::GetFullPath($SigningDirectory)
    $keystore = Join-Path $SigningDirectory 'release.jks'
    $passwordFile = Join-Path $SigningDirectory 'password.txt'
    foreach ($path in @($keystore,$passwordFile)) {
        Assert-PocketPlainPath $path
        if (-not [IO.File]::Exists($path)) { throw 'Existing release signing identity/password file missing. No key will be generated.' }
    }
    if (-not $ApkSignerPath) { $ApkSignerPath = Find-PocketApkSigner $Root }
    if (-not [IO.File]::Exists($ApkSignerPath)) { throw 'APK signature verifier is missing.' }
    $keytool = Get-PocketKeytoolPath
    Get-Command cargo -ErrorAction Stop | Out-Null
    $versionMatch = [regex]::Match([IO.File]::ReadAllText((Join-Path $Root 'Cargo.toml')),'(?m)^version\s*=\s*"([^"]+)"')
    $androidVersion = [regex]::Match([IO.File]::ReadAllText((Join-Path $Root 'android\app\build.gradle.kts')),'versionName\s*=\s*"([^"]+)"')
    $version = $versionMatch.Groups[1].Value
    if (-not $versionMatch.Success -or $androidVersion.Groups[1].Value -ne $version) { throw 'Windows and Android release versions differ.' }
    $desktopVersion=(Get-Content -LiteralPath (Join-Path $Root 'desktop\package.json') -Raw | ConvertFrom-Json).version
    if ($desktopVersion -ne $version) { throw 'Desktop frontend and release versions differ.' }
    $desktopRustVersion=[regex]::Match([IO.File]::ReadAllText((Join-Path $Root 'desktop\Cargo.toml')),'(?m)^version\s*=\s*"([^"]+)"')
    if (-not $desktopRustVersion.Success -or $desktopRustVersion.Groups[1].Value -ne $version) { throw 'Desktop Rust and release versions differ.' }
    $names = @('POCKET_KEYSTORE','POCKET_STORE_PASSWORD','POCKET_KEY_PASSWORD','POCKET_KEY_ALIAS')
    $previous = @{}
    foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    Push-Location $Root
    try {
        # Public entry always builds current sources. No debug/probe/package-only fallback.
        & (Join-Path $Root 'scripts\fetch-cloudflared.ps1')
        $target = 'x86_64-pc-windows-msvc'
        & cargo build --release --locked --target $target --target-dir (Join-Path $Root 'target') -p pocket-agent -p pocket-bridge
        if ($LASTEXITCODE -ne 0) { throw 'Windows Release build failed; no package published.' }
        Invoke-PocketDesktopBuild $Root $target
        $env:POCKET_KEYSTORE = $keystore
        $env:POCKET_STORE_PASSWORD = [IO.File]::ReadAllText($passwordFile)
        $env:POCKET_KEY_PASSWORD = $env:POCKET_STORE_PASSWORD
        $env:POCKET_KEY_ALIAS = 'codex-pocket'
        # Export only the public certificate in memory; passwords are never command arguments.
        # keytool prints a JKS-format advisory on stderr while succeeding; only its exit code is authoritative.
        $keytoolPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $certOutput = @(& $keytool -exportcert -rfc -keystore $keystore -storepass:env POCKET_STORE_PASSWORD -alias codex-pocket 2>&1 | ForEach-Object { "$_" }) }
        finally { $ErrorActionPreference = $keytoolPreference }
        if ($LASTEXITCODE -ne 0) { throw 'Cannot verify the existing release signing certificate (output omitted).' }
        $certificate = [regex]::Match(($certOutput -join [Environment]::NewLine),'(?s)-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----')
        if (-not $certificate.Success) { throw 'Existing signing certificate could not be decoded.' }
        $expectedSigner = Get-PocketBytesHash ([Convert]::FromBase64String(($certificate.Groups[1].Value -replace '\s','')))
        & (Join-Path $Root 'android\gradlew.bat') -p (Join-Path $Root 'android') :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Android Release build failed; no package published.' }
        $apk = Join-Path $Root 'android\app\build\outputs\apk\release\app-release.apk'
        $apkHash = Get-PocketFileHash $apk
        if (-not $apkHash) { throw 'Release APK missing after build.' }
        $verification = @(& $ApkSignerPath verify --verbose --print-certs $apk 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed (output omitted).' }
        $signers = [regex]::Matches(($verification -join [Environment]::NewLine),'(?m)^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$')
        if ($signers.Count -ne 1 -or $signers[0].Groups[1].Value -ne $expectedSigner) { throw 'Release APK signer does not match the existing release.jks identity.' }
        if ((Get-PocketFileHash $apk) -ne $apkHash) { throw 'Release APK changed during signature verification.' }
        $evidence = @{builtThisInvocation=$true;windowsTarget=$target;windowsProfile='release';apkSignatureVerified=$true;apkSha256=$apkHash;apkSignerSha256=$expectedSigner;cargoLockSha256=(Get-PocketFileHash (Join-Path $Root 'Cargo.lock'));nativeLiveAcceptance='separate-parent-validation'}
        $evidence.desktopCargoLockSha256=Get-PocketFileHash (Join-Path $Root 'desktop\Cargo.lock')
        $evidence.frontendLockSha256=Get-PocketFileHash (Join-Path $Root 'desktop\package-lock.json')
        $evidence.desktopProfile='release'; $evidence.frontendTestsPassed=$true
        New-PocketReleasePackage -Root $Root -BinaryDirectory (Join-Path $Root ('target\' + $target + '\release')) -DesktopBinaryDirectory (Join-Path $Root 'desktop\target\release') -ApkPath $apk -OutputDirectory $OutputDirectory -Version $version -BuildEvidence $evidence
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
        Pop-Location
    }
}
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-PocketReleaseBuild -Root ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))) -SigningDirectory $SigningDirectory -OutputDirectory $OutputDirectory -ApkSignerPath $ApkSignerPath
}
