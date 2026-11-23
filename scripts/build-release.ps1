#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '..\.local\signing'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist'),
    [string]$ApkSignerPath,
    [string]$UpdateTrustPath,
    [string]$TargetDirectory,
    [string]$DesktopTargetDirectory,
    [switch]$SkipTests,
    [string]$OnDeviceRuntime,
    [string[]]$OnDeviceAbis
)
. (Join-Path $PSScriptRoot 'windows-common.ps1')

function New-PocketReleasePackage {
    [CmdletBinding()]
    param([string]$Root,[string]$BinaryDirectory,[string]$DesktopBinaryDirectory,[string]$ApkPath,[string]$OutputDirectory,[string]$Version,[hashtable]$BuildEvidence,[string]$UpdateTrustPath)
    $ErrorActionPreference = 'Stop'
    if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Invalid release version.' }
    if (-not $BuildEvidence['builtThisInvocation'] -and -not $BuildEvidence['fixture']) { throw 'Release packaging requires a fresh build; only isolated tests may use fixture artifacts.' }
    $OutputDirectory = Get-PocketDirectoryPath $OutputDirectory
    Assert-PocketPlainPath $OutputDirectory
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory already exists; select a new -OutputDirectory to preserve the previous release.' }
    $files = [ordered]@{}
    if (-not $DesktopBinaryDirectory) { $DesktopBinaryDirectory=Join-Path $Root 'desktop\target\release' }
    $windowsFiles = @(Get-PocketWindowsFiles -IncludeUpdateTrust:([bool]$UpdateTrustPath))
    foreach ($name in $windowsFiles) {
        $source = if ($name -eq 'windows-update-trust.json') { $UpdateTrustPath }
            elseif ($name -eq 'pocket-native-proxy.exe') { Join-Path $BinaryDirectory 'pocket-bridge.exe' }
            elseif ($name -in @('cloudflared.exe','cloudflared-LICENSE.txt')) { Join-Path $Root ('.local\tools\' + $name) }
            elseif ($name -in @('pocket-desktop.exe','pocket-supervisor.exe','pocket-launcher.exe','pocket-upgrade.exe')) { Join-Path $DesktopBinaryDirectory $name }
            elseif ($name -like '*.exe' -or $name -like '*.dll') { Join-Path $BinaryDirectory $name }
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
    Write-PocketJson (Join-Path $windows 'windows-manifest.json') ([ordered]@{schema=1;product='Codex Pocket';version=$Version;buildId=$BuildEvidence['buildId'];files=(Get-PocketInventory $windows $windowsFiles)})
    Test-PocketWindowsDirectory $windows | Out-Null
    $apkName = 'codex-pocket-' + $Version + '.apk'
    [IO.File]::Copy($ApkPath,(Join-Path $stage $apkName),$false)
    if ($BuildEvidence['apkSha256'] -and (Get-PocketFileHash (Join-Path $stage $apkName)) -ne $BuildEvidence['apkSha256']) { throw 'APK changed after signature verification; staged package retained, release not published.' }
    $zipName = 'codex-pocket-' + $Version + '-windows.zip'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($windows,(Join-Path $stage $zipName))
    $names = @($windowsFiles | ForEach-Object {'windows/' + $_}) + @('windows/windows-manifest.json',$apkName,$zipName)
    $migrationNotes = Join-Path $Root ('docs\v' + $Version + '-candidate.md')
    if ([IO.File]::Exists($migrationNotes)) {
        [IO.File]::Copy($migrationNotes,(Join-Path $stage 'MIGRATION-ROLLBACK.md'),$false)
        $names += 'MIGRATION-ROLLBACK.md'
    }
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
function Get-PocketSourceDigest([string]$Root) {
    $paths = @(& git -C $Root ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0 -or $paths.Count -eq 0) { throw 'Cannot establish release source inventory.' }
    $entries = foreach ($relative in ($paths | Sort-Object -Unique)) {
        $path = Join-Path $Root $relative
        $hash = if ([IO.File]::Exists($path)) { Get-PocketFileHash $path } else { 'deleted' }
        $relative.Replace('\','/') + ':' + $hash
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($entries -join "`n"))))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Assert-PocketApkSignerIdentity([string[]]$Verification,[string]$ExpectedSigner) {
    $output = $Verification -join "`n"
    $counts = [regex]::Matches($output,'(?m)^Number of signers: ([0-9]+)\s*$')
    # Build-tools 37 prints one certificate per verified scheme instead of Signer #1.
    # Every reported signing certificate must be the approved original identity.
    $signers = [regex]::Matches($output,'(?m)^(?:Signer #1|V[1-4](?:\.[0-9]+)? Signer:) certificate SHA-256 digest:[ \t]*([a-fA-F0-9]{64})\s*$')
    $certificateLines = @($Verification | Where-Object { $_ -match 'certificate SHA-256 digest:' -and $_ -notmatch '^Source Stamp Signer ' })
    $mismatch = @($signers | Where-Object { $_.Groups[1].Value -ne $ExpectedSigner })
    if ($ExpectedSigner -notmatch '^[a-fA-F0-9]{64}$' -or $counts.Count -ne 1 -or $counts[0].Groups[1].Value -ne '1' -or
        $signers.Count -eq 0 -or $certificateLines.Count -ne $signers.Count -or $mismatch.Count -gt 0) {
        $actual = @($signers | ForEach-Object { $_.Groups[1].Value }) -join ','
        throw "Release APK signer does not match the existing release.jks identity. Expected: $ExpectedSigner; matched: $($signers.Count); actual: $actual"
    }
}
function Get-PocketOnDeviceRuntime([string]$RuntimeDirectory,[string[]]$Abis) {
    if (-not $RuntimeDirectory) {
        if ($Abis.Count -gt 0) { throw '-OnDeviceAbis requires -OnDeviceRuntime.' }
        return $null
    }
    if (-not (Test-Path -LiteralPath $RuntimeDirectory -PathType Container)) { throw "On-device runtime directory missing: $RuntimeDirectory" }
    $directory = (Get-Item -LiteralPath $RuntimeDirectory).FullName
    $libraries = Join-Path $directory 'jniLibs'
    if (-not [IO.Directory]::Exists($libraries)) { throw 'On-device runtime has no jniLibs directory.' }
    $selected = if ($Abis.Count -gt 0) {
        @($Abis | ForEach-Object { $_.Split(',') } | ForEach-Object { $_.Trim() } | Select-Object -Unique)
    } else {
        @(Get-ChildItem -LiteralPath $libraries -Directory | ForEach-Object { $_.Name })
    }
    if ($selected.Count -eq 0) { throw 'On-device runtime has no staged ABIs.' }
    $required = @('libpocket-agent.so','libpocket-runtime-host.so','libpocket-runtime-manifest.so','libcodex.so')
    $files = foreach ($abi in $selected) {
        if ($abi -cnotin @('arm64-v8a','x86_64')) { throw "Unsupported on-device runtime ABI: $abi" }
        $abiDirectory = Join-Path $libraries $abi
        foreach ($name in $required) {
            $path = Join-Path $abiDirectory $name
            if (-not [IO.File]::Exists($path) -or (Get-Item -LiteralPath $path).Length -eq 0) {
                throw "On-device runtime staging missing or empty: $abi/$name"
            }
        }
        foreach ($file in Get-ChildItem -LiteralPath $abiDirectory -Filter '*.so' -File) {
            [pscustomobject]@{Entry="lib/$abi/$($file.Name)";Path=$file.FullName;Length=$file.Length}
        }
    }
    return [pscustomobject]@{Directory=$directory;Abis=@($selected);Files=@($files)}
}
function Assert-PocketOnDeviceApk([string]$ApkPath,[string]$RuntimeDirectory,[string[]]$Abis) {
    $runtime = Get-PocketOnDeviceRuntime $RuntimeDirectory $Abis
    if (-not $runtime) { throw 'APK runtime inspection requires a runtime directory.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        foreach ($file in $runtime.Files) {
            $entries = @($zip.Entries | Where-Object { $_.FullName -ceq $file.Entry })
            if ($entries.Count -ne 1) { throw "APK runtime entry missing or duplicated: $($file.Entry)" }
            $entry = $entries[0]
            if ($entry.Length -ne $file.Length) { throw "APK runtime bytes differ from staging: $($file.Entry)" }
            # JNI stripping or stale build outputs must not silently replace the
            # staged executables. Stream the ZIP entry; large runtimes stay off heap.
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $actual = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
            finally { $sha.Dispose(); $stream.Dispose() }
            if ($actual -ne (Get-PocketFileHash $file.Path)) { throw "APK runtime bytes differ from staging: $($file.Entry)" }
        }
    } finally { $zip.Dispose() }
}
function Invoke-PocketDesktopBuild([string]$Root,[string]$Target,[string]$DesktopTargetDirectory,[switch]$SkipTests) {
    if (-not $DesktopTargetDirectory) { $DesktopTargetDirectory=Join-Path $Root 'desktop\target' }
    Push-Location (Join-Path $Root 'desktop')
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Desktop locked dependency installation failed.' }
        if (-not $SkipTests) {
            & npm.cmd test
            if ($LASTEXITCODE -ne 0) { throw 'Desktop frontend tests failed.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Desktop frontend build failed.' }
        # custom-protocol embeds the bundle; no development server or remote origin.
        & cargo build --manifest-path (Join-Path $Root 'desktop\Cargo.toml') --workspace --release --locked --features pocket-desktop/custom-protocol --target-dir $DesktopTargetDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Desktop Rust Release build failed.' }
    } finally { Pop-Location }
}
function Invoke-PocketReleaseBuild {
    [CmdletBinding()]
    param([string]$Root,[string]$SigningDirectory,[string]$OutputDirectory,[string]$ApkSignerPath,[string]$UpdateTrustPath,[string]$TargetDirectory,[string]$DesktopTargetDirectory,[switch]$SkipTests,[string]$OnDeviceRuntime,[string[]]$OnDeviceAbis)
    $ErrorActionPreference = 'Stop'
    $onDevice = Get-PocketOnDeviceRuntime $OnDeviceRuntime $OnDeviceAbis
    if (-not $TargetDirectory) { $TargetDirectory = Join-Path $Root 'target' }
    if (-not $DesktopTargetDirectory) { $DesktopTargetDirectory = Join-Path $Root 'desktop\target' }
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
    $names = @('POCKET_KEYSTORE','POCKET_STORE_PASSWORD','POCKET_KEY_PASSWORD','POCKET_KEY_ALIAS','POCKET_BUILD_ID')
    $previous = @{}
    foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    if (-not $env:POCKET_BUILD_ID) { $env:POCKET_BUILD_ID = $version + '-rc.' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '.' + [guid]::NewGuid().ToString('N').Substring(0,8) }
    if ($env:POCKET_BUILD_ID -notmatch '^[A-Za-z0-9._-]{1,96}$') { throw 'Invalid build identity.' }
    Push-Location $Root
    try {
        $sourceDigest = Get-PocketSourceDigest $Root
        # Public entry always builds current sources. No debug/probe/package-only fallback.
        & (Join-Path $Root 'scripts\fetch-cloudflared.ps1')
        & (Join-Path $Root 'scripts\build-p2p-product.ps1')
        $target = 'x86_64-pc-windows-msvc'
        & cargo build --release --locked --target $target --target-dir $TargetDirectory -p pocket-agent -p pocket-bridge
        if ($LASTEXITCODE -ne 0) { throw 'Windows Release build failed; no package published.' }
        & cargo build --release --locked --manifest-path (Join-Path $Root 'crates/runtime-host/Cargo.toml') --target $target --target-dir $TargetDirectory --bin pocket-runtime-host
        if ($LASTEXITCODE -ne 0) { throw 'Runtime Host Release build failed; no package published.' }
        foreach($library in @('pocket-p2p.dll','datachannel.dll')) {
            Copy-Item -LiteralPath (Join-Path $Root ('.local/p2p/product-windows/Release/'+$library)) -Destination (Join-Path $TargetDirectory ($target+'/release/'+$library)) -Force
        }
        Invoke-PocketDesktopBuild $Root $target $DesktopTargetDirectory -SkipTests:$SkipTests
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
        if ($env:POCKET_EXPECTED_SIGNER -and $expectedSigner -ne $env:POCKET_EXPECTED_SIGNER) {
            throw "Configured public signing certificate differs from the approved identity: $expectedSigner"
        }
        $gradleArguments = @('-p',(Join-Path $Root 'android'),':app:assembleRelease','-Pkotlin.compiler.execution.strategy=in-process','--no-daemon','--max-workers=2','--console=plain')
        if ($onDevice) {
            $gradleArguments += '-PpocketOnDeviceRuntime=' + $onDevice.Directory
            $gradleArguments += '-PpocketOnDeviceAbis=' + ($onDevice.Abis -join ',')
        }
        & (Join-Path $Root 'android\gradlew.bat') @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw 'Android Release build failed; no package published.' }
        $apk = Join-Path $Root 'android\app\build\outputs\apk\release\app-release.apk'
        $apkHash = Get-PocketFileHash $apk
        if (-not $apkHash) { throw 'Release APK missing after build.' }
        $verification = @(& $ApkSignerPath verify --verbose --print-certs $apk 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed (output omitted).' }
        Write-Host "APK verifier: $ApkSignerPath"
        Assert-PocketApkSignerIdentity -Verification $verification -ExpectedSigner $expectedSigner
        if ($onDevice) { Assert-PocketOnDeviceApk -ApkPath $apk -RuntimeDirectory $onDevice.Directory -Abis $onDevice.Abis }
        if ((Get-PocketFileHash $apk) -ne $apkHash) { throw 'Release APK changed during signature verification.' }
        $evidence = @{builtThisInvocation=$true;windowsTarget=$target;windowsProfile='release';apkSignatureVerified=$true;apkSha256=$apkHash;apkSignerSha256=$expectedSigner;cargoLockSha256=(Get-PocketFileHash (Join-Path $Root 'Cargo.lock'));nativeLiveAcceptance='separate-parent-validation'}
        $evidence.desktopCargoLockSha256=Get-PocketFileHash (Join-Path $Root 'desktop\Cargo.lock')
        $evidence.buildId=$env:POCKET_BUILD_ID
        $evidence.frontendLockSha256=Get-PocketFileHash (Join-Path $Root 'desktop\package-lock.json')
        $evidence.desktopProfile='release'; $evidence.frontendTestsPassed=(-not $SkipTests)
        if ($onDevice) { $evidence.onDeviceRuntime=@{abis=$onDevice.Abis;apkContentVerified=$true} }
        if ((Get-PocketSourceDigest $Root) -ne $sourceDigest) { throw 'Source files changed while building; no candidate package published. Rebuild after all edits finish.' }
        $evidence.sourceTreeSha256=$sourceDigest
        New-PocketReleasePackage -Root $Root -BinaryDirectory (Join-Path $TargetDirectory ($target + '\release')) -DesktopBinaryDirectory (Join-Path $DesktopTargetDirectory 'release') -ApkPath $apk -OutputDirectory $OutputDirectory -Version $version -BuildEvidence $evidence -UpdateTrustPath $UpdateTrustPath
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
        Pop-Location
    }
}
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-PocketReleaseBuild -Root ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))) -SigningDirectory $SigningDirectory -OutputDirectory $OutputDirectory -ApkSignerPath $ApkSignerPath -UpdateTrustPath $UpdateTrustPath -TargetDirectory $TargetDirectory -DesktopTargetDirectory $DesktopTargetDirectory -SkipTests:$SkipTests -OnDeviceRuntime $OnDeviceRuntime -OnDeviceAbis $OnDeviceAbis
}
