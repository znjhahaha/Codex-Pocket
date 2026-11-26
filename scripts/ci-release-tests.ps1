#requires -Version 7.0
# Execute the real orchestration against synthetic build/package boundaries.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('pocket-ci-release-' + [guid]::NewGuid().ToString('N'))
$repo = Join-Path $fixture 'repo'
$scripts = Join-Path $repo 'scripts'
[void][IO.Directory]::CreateDirectory($scripts)
[void][IO.Directory]::CreateDirectory((Join-Path $repo 'android'))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'ci-release.ps1') -Destination $scripts
function Put($Path, $Text) { [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false)) }
Put (Join-Path $repo 'android/gradlew.bat') '@exit /b 0'
Put (Join-Path $scripts 'build-release.ps1') @'
param($SigningDirectory,$OutputDirectory,$UpdateTrustPath,$OnDeviceRuntime,[string[]]$OnDeviceAbis)
if($env:POCKET_SIGNING_KEY_BASE64 -or $env:POCKET_UPDATE_PRIVATE_KEY_PEM -or $env:POCKET_SIGNING_PASSWORD){throw 'Secrets inherited by build boundary'}
if(!(Test-Path -LiteralPath (Join-Path $SigningDirectory 'release.jks'))){throw 'Signing material not supplied'}
if($global:failBuild){throw 'fixture build failure'}
[void][IO.Directory]::CreateDirectory($OutputDirectory)
$scriptResult=@{runtime=$OnDeviceRuntime;abis=$OnDeviceAbis;runtimeBound=$PSBoundParameters.ContainsKey('OnDeviceRuntime');abisBound=$PSBoundParameters.ContainsKey('OnDeviceAbis');signing=$SigningDirectory;feed=(Get-Content $UpdateTrustPath -Raw|ConvertFrom-Json).feed}
$scriptResult|ConvertTo-Json -Depth 4|Set-Content (Join-Path $OutputDirectory 'call.json')
@{build=@{apkSignerSha256=$env:POCKET_EXPECTED_SIGNER}}|ConvertTo-Json -Depth 3|Set-Content (Join-Path $OutputDirectory 'release-manifest.json')
$global:LASTEXITCODE=0
'@
Put (Join-Path $scripts 'package-windows-release.ps1') @'
param($ReleaseDirectory,$PrivateKeyPath,$TrustPath,$AssetBase)
if(!(Test-Path -LiteralPath $PrivateKeyPath)){throw 'Update signing material missing'}
if($global:failPackage){$global:LASTEXITCODE=7;return}
[IO.File]::WriteAllText((Join-Path $ReleaseDirectory 'CodexPocketManager-win-Setup.exe'),'synthetic setup; never execute')
$global:LASTEXITCODE=0
'@
Put (Join-Path $scripts 'package-source.ps1') @'
param($OutputDirectory)
[void][IO.Directory]::CreateDirectory($OutputDirectory)
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'source.zip'),'synthetic source')
'@
function cargo { $global:LASTEXITCODE = 0 }
function Assert($Condition,$Message) { if(!$Condition){throw $Message} }
$passed=0
function Test($Name,[scriptblock]$Body) { & $Body; $script:passed++; Write-Output "PASS $Name" }
$names=@('POCKET_SIGNING_KEY_BASE64','POCKET_SIGNING_PASSWORD','POCKET_EXPECTED_SIGNER','POCKET_UPDATE_PRIVATE_KEY_PEM','POCKET_UPDATE_TRUST_JSON','POCKET_UPDATE_ASSET_BASE','MANAGED_RUNTIME_TEST_HOST')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Inputs {
    $env:POCKET_SIGNING_KEY_BASE64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('synthetic keystore'))
    $env:POCKET_SIGNING_PASSWORD='fixture password'
    $env:POCKET_EXPECTED_SIGNER='a'*64
    $env:POCKET_UPDATE_PRIVATE_KEY_PEM='synthetic update signing input'
    $env:POCKET_UPDATE_TRUST_JSON='{"publicKey":"synthetic","allowedHosts":["github.com"],"feed":"https://github.com/old"}'
    $env:POCKET_UPDATE_ASSET_BASE='https://github.com/fixture/project/releases/download/v1.2.3-rc.1'
    $env:MANAGED_RUNTIME_TEST_HOST='original-host-sentinel'
    $global:failBuild=$false; $global:failPackage=$false
}
try {
    Test 'legacy CI entry completes without on-device requirements and removes temporary signing inputs' {
        Inputs
        $out=Join-Path $fixture 'legacy'
        & (Join-Path $scripts 'ci-release.ps1') -OutputDirectory $out
        $call=Get-Content (Join-Path $out 'call.json') -Raw|ConvertFrom-Json
        Assert (!$call.runtimeBound -and !$call.abisBound) 'Legacy call gained runtime requirements'
        Assert ($call.feed -eq ($env:POCKET_UPDATE_ASSET_BASE+'/windows-update.json')) 'Feed tag was not propagated'
        Assert (!(Test-Path -LiteralPath $call.signing)) 'Temporary signing material retained'
        Assert ($env:MANAGED_RUNTIME_TEST_HOST -eq 'original-host-sentinel') 'Test host was not restored'
        Assert (Test-Path -LiteralPath (Join-Path $out 'SHA256SUMS.txt')) 'Final asset inventory missing'
    }
    Test 'optional runtime path and ABI selection reach the release builder intact' {
        Inputs
        Push-Location $fixture
        try { & (Join-Path $scripts 'ci-release.ps1') -OutputDirectory 'runtime-output' -OnDeviceRuntime 'ARM64 stage with spaces' -OnDeviceAbis @('arm64-v8a') }
        finally { Pop-Location }
        $call=Get-Content (Join-Path $fixture 'runtime-output/call.json') -Raw|ConvertFrom-Json
        Assert ($call.runtime -eq (Join-Path $fixture 'ARM64 stage with spaces')) 'Runtime path resolved against the wrong directory'
        Assert (@($call.abis).Count -eq 1 -and $call.abis[0] -eq 'arm64-v8a') 'ABI selection changed'
    }
    Test 'build failure cannot reach packaging or change the caller test host' {
        Inputs; $global:failBuild=$true
        $out=Join-Path $fixture 'failed-build'; $rejected=$false
        try { & (Join-Path $scripts 'ci-release.ps1') -OutputDirectory $out } catch { $rejected=$_.Exception.Message -eq 'fixture build failure' }
        Assert $rejected 'Build failure did not propagate'
        Assert (!(Test-Path -LiteralPath $out)) 'Failed build published output'
        Assert ($env:MANAGED_RUNTIME_TEST_HOST -eq 'original-host-sentinel') 'Failure lost test-host setting'
    }
    Test 'nonzero packaging exit cannot produce a final release inventory' {
        Inputs; $global:failPackage=$true
        $out=Join-Path $fixture 'failed-package'; $rejected=$false
        try { & (Join-Path $scripts 'ci-release.ps1') -OutputDirectory $out } catch { $rejected=$_.Exception.Message -eq 'Windows installer creation failed.' }
        Assert $rejected 'Packager failure did not propagate'
        Assert (!(Test-Path -LiteralPath (Join-Path $out 'SHA256SUMS.txt'))) 'Failed packaging published final inventory'
    }
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    Remove-Variable failBuild,failPackage -Scope Global -ErrorAction SilentlyContinue
}
Write-Output "$passed CI release orchestration fixtures passed; retained at $fixture"
$global:LASTEXITCODE=0
