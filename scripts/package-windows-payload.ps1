#requires -Version 7.0
<# Package freshly built Windows components independently of Android signing.
   This creates a payload, not an installer and not installation acceptance. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$BuildId,
    [Parameter(Mandatory)][string]$UpdateTrustPath,
    [string]$BinaryDirectory=(Join-Path $PSScriptRoot '../target/release'),
    [string]$RuntimeHostDirectory=(Join-Path $PSScriptRoot '../crates/runtime-host/target/release'),
    [string]$DesktopBinaryDirectory=(Join-Path $PSScriptRoot '../desktop/target/release')
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination=Get-PocketDirectoryPath $OutputDirectory
Assert-PocketPlainPath $destination
if(Test-Path -LiteralPath $destination){throw 'Output exists; preserve the earlier package'}
if($BuildId -notmatch '^[A-Za-z0-9._-]{1,96}$'){throw 'Invalid build identity'}
$version=[regex]::Match([IO.File]::ReadAllText((Join-Path $root 'Cargo.toml')),'(?m)^version\s*=\s*"([^"]+)"').Groups[1].Value
$desktop=Get-Content -LiteralPath (Join-Path $root 'desktop/package.json') -Raw | ConvertFrom-Json
if(!$version -or $desktop.version -ne $version){throw 'Windows component versions differ'}
# Query the binaries, not the build shell's environment or the manifest we are about to write.
foreach($binary in @((Join-Path $BinaryDirectory 'pocket-agent.exe'),(Join-Path $DesktopBinaryDirectory 'pocket-supervisor.exe'))){
    # The supervisor is a Windows-subsystem executable; PowerShell's direct
    # invocation can return without capturing its stdout. Read its process pipe.
    $info=[Diagnostics.ProcessStartInfo]::new([IO.Path]::GetFullPath($binary))
    $info.ArgumentList.Add('--build-info')
    $info.UseShellExecute=$false
    $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true
    $info.RedirectStandardError=$true
    $probe=[Diagnostics.Process]::Start($info)
    try {
        if(!$probe.WaitForExit(10000)){ $probe.Kill();throw "Build identity probe timed out: $binary" }
        if($probe.ExitCode -ne 0){throw "Cannot verify embedded build identity: $binary"}
        $stamp=$probe.StandardOutput.ReadToEnd()|ConvertFrom-Json
    } finally { $probe.Dispose() }
    if($stamp.version -ne $version -or $stamp.buildId -ne $BuildId){throw "Embedded build identity does not match release: $binary"}
}
$names=Get-PocketWindowsFiles -IncludeUpdateTrust
$files=[ordered]@{}
foreach($name in $names){
    $source=if($name -eq 'windows-update-trust.json'){$UpdateTrustPath}
        elseif($name -eq 'pocket-native-proxy.exe'){Join-Path $BinaryDirectory 'pocket-bridge.exe'}
        elseif($name -in @('cloudflared.exe','cloudflared-LICENSE.txt')){Join-Path $root ('.local/tools/'+$name)}
        elseif($name -in @('pocket-desktop.exe','pocket-supervisor.exe','pocket-launcher.exe','pocket-upgrade.exe')){Join-Path $DesktopBinaryDirectory $name}
        elseif($name -eq 'pocket-runtime-host.exe'){Join-Path $RuntimeHostDirectory $name}
        elseif($name -like '*.exe' -or $name -like '*.dll'){Join-Path $BinaryDirectory $name}
        elseif($name -like '*.md'){Join-Path $root ('docs/'+$name)}
        else{Join-Path $PSScriptRoot $name}
    if(!(Test-Path -LiteralPath $source -PathType Leaf)){throw "Missing Windows payload component: $name"}
    $files[$name]=$source
}
$stage=$destination+'.staging-'+[guid]::NewGuid().ToString('N')
$windows=Join-Path $stage 'windows'
[void][IO.Directory]::CreateDirectory($windows)
foreach($name in $files.Keys){[IO.File]::Copy($files[$name],(Join-Path $windows $name),$false)}
Write-PocketJson (Join-Path $windows 'windows-manifest.json') ([ordered]@{schema=1;product='Codex Pocket';version=$version;buildId=$BuildId;files=(Get-PocketInventory $windows $names)})
Test-PocketWindowsDirectory $windows | Out-Null
Write-PocketJson (Join-Path $stage 'release-manifest.json') ([ordered]@{
    schema=1;product='Codex Pocket';version=$version;createdAt=[DateTimeOffset]::UtcNow.ToString('o')
    build=@{buildId=$BuildId;windowsProfile='release';windowsOnly=$true;installationAcceptance='pending-user-installation'}
    files=(Get-PocketInventory $stage (@($names|ForEach-Object {'windows/'+$_})+@('windows/windows-manifest.json')))
})
Move-PocketStagedDirectory $stage $destination
Write-Output "Windows payload packaged: $destination"
