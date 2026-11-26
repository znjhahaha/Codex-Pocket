#requires -Version 7.0
# Real package orchestration and installer dispatch; synthetic signer/tool only.
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('pocket-package-chain-'+[guid]::NewGuid().ToString('N'))
$scripts=Join-Path $fixture 'scripts'
[void][IO.Directory]::CreateDirectory($scripts)
foreach($name in @('package-windows-release.ps1','build-windows-installer.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $scripts
}
[IO.File]::WriteAllText((Join-Path $scripts 'build-windows-velopack.ps1'), @'
param($ReleaseDirectory,$OutputDirectory,$VpkDll,$SetupExecutable,$UpdateOfferPath,$TrustedUpdatePublicKey)
if(!(Test-Path -LiteralPath $UpdateOfferPath) -or !$TrustedUpdatePublicKey){throw 'Signed installer authority missing'}
if($global:installerExit){$global:LASTEXITCODE=$global:installerExit;return}
[void][IO.Directory]::CreateDirectory($OutputDirectory)
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'CodexPocketManager-1.2.3-rc.1-full.nupkg'),'synthetic full package')
if(!$global:omitSetup){[IO.File]::WriteAllText((Join-Path $OutputDirectory 'CodexPocketManager-win-Setup.exe'),'synthetic setup; never launch')}
@{key=$TrustedUpdatePublicKey;offer=$UpdateOfferPath;setup=$SetupExecutable}|ConvertTo-Json|Set-Content (Join-Path $OutputDirectory 'dispatch.json')
$global:LASTEXITCODE=0
'@)
$setup=Join-Path $fixture 'pocket-setup.exe'
$key=Join-Path $fixture 'fixture-key.pem'
$trust=Join-Path $fixture 'trust.json'
[IO.File]::WriteAllText($setup,'synthetic setup; never launch')
[IO.File]::WriteAllText($key,'synthetic signing input')
$public=[Convert]::ToBase64String([byte[]]::new(32))
@{publicKey=$public;allowedHosts=@('github.com')}|ConvertTo-Json|Set-Content $trust
$vpk=Join-Path $fixture 'vpk.dll'; [IO.File]::WriteAllText($vpk,'synthetic tool; never execute')
$global:pocketPackageFixture=@{signCalls=[Collections.Generic.List[object]]::new();toolDirectories=[Collections.Generic.List[string]]::new()}
function dotnet {
    if(($args -join ' ') -notmatch 'tool install vpk --version 1\.2\.161 --framework net8\.0 --tool-path'){throw 'Tool framework/version is not pinned'}
    $path=$args[[array]::IndexOf($args,'--tool-path')+1]
    $global:pocketPackageFixture.toolDirectories.Add($path)
    $entry=Join-Path $path '.store/vpk/1.2.161/vpk/1.2.161/tools/net8.0/any/vpk.dll'
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($entry))
    [IO.File]::WriteAllText($entry,'synthetic .NET 8 tool')
    $global:LASTEXITCODE=0
}
function python {
    $global:pocketPackageFixture.signCalls.Add(@($args))
    if($global:signerExit){$global:LASTEXITCODE=$global:signerExit;return}
    $output=$args[[array]::IndexOf($args,'--output')+1]
    [IO.File]::WriteAllText($output,'synthetic signed offer')
    $global:LASTEXITCODE=0
}
function Assert($Value,$Message){if(!$Value){throw $Message}}
function Run([string]$Name,[hashtable]$Extra=@{}) {
    $out=Join-Path $fixture $Name; [void][IO.Directory]::CreateDirectory($out)
    & (Join-Path $scripts 'package-windows-release.ps1') -ReleaseDirectory $out -PrivateKeyPath $key -TrustPath $trust -AssetBase 'https://github.com/fixture/release' -SetupExecutable $setup @Extra | Out-Null
    return $out
}
function Reset { $global:omitSetup=$false; $global:signerExit=0; $global:installerExit=0; $global:pocketPackageFixture.signCalls.Clear() }
$passed=0
function Test($Name,[scriptblock]$Body){Reset; & $Body; $script:passed++; Write-Output "PASS $Name"}
try {
    Test 'pinned tool and signed offers reach one Setup and one full package' {
        $out=Run 'complete'
        Assert ($global:pocketPackageFixture.signCalls.Count -eq 2) 'Payload and package signatures were not both requested'
        Assert ($global:pocketPackageFixture.signCalls[1] -contains '--package') 'Update feed lacks full-package identity'
        $dispatch=Get-Content (Join-Path $out 'dispatch.json') -Raw|ConvertFrom-Json
        Assert ($dispatch.key -eq $public -and $dispatch.setup -eq $setup) 'Pinned key or built setup was replaced'
        Assert (Test-Path (Join-Path $out 'windows-update.json')) 'Full update offer missing'
        Assert (Test-Path (Join-Path $out 'CodexPocketManager-win-Setup.exe')) 'Setup missing from release assets'
    }
    Test 'signing failure stops before installer dispatch' {
        $global:signerExit=9; $rejected=$false
        try { Run 'bad-signature' @{VpkDll=$vpk} } catch { $rejected=$_.Exception.Message -eq 'Payload signature failed' }
        Assert $rejected 'Signer failure was ignored'
        Assert (!(Test-Path (Join-Path $fixture 'bad-signature/velopack'))) 'Installer ran after signature failure'
    }
    Test 'nonzero installer result cannot sign a package' {
        $global:installerExit=5; $rejected=$false
        try { Run 'bad-installer' @{VpkDll=$vpk} } catch { $rejected=$_.Exception.Message -eq 'Windows installer creation failed' }
        Assert $rejected 'Installer failure was ignored'
        Assert ($global:pocketPackageFixture.signCalls.Count -eq 1) 'Failed installer reached full-package signing'
    }
    Test 'a full package without Setup cannot be reported as a release' {
        $global:omitSetup=$true; $rejected=$false
        try { Run 'missing-setup' @{VpkDll=$vpk} } catch { $rejected=$_.Exception.Message -eq 'Expected one Velopack Setup executable' }
        Assert $rejected 'Missing setup was accepted'
    }
    Test 'Velopack dispatch requires the signed offer and existing inputs' {
        $rejected=$false
        try { & (Join-Path $scripts 'build-windows-installer.ps1') -ReleaseDirectory $fixture -OutputDirectory (Join-Path $fixture 'unsigned') -VpkDll $vpk -SetupExecutable $setup } catch { $rejected=$_.Exception.Message -match 'signed offer' }
        Assert $rejected 'Unsigned default installer path was accepted'
    }
} finally { Remove-Variable omitSetup,signerExit,installerExit -Scope Global -ErrorAction SilentlyContinue }
Write-Output "$passed package-chain fixtures passed; retained at $fixture"
foreach($path in $global:pocketPackageFixture.toolDirectories){Write-Output "Synthetic tool directory: $path"}
Remove-Variable pocketPackageFixture -Scope Global
$global:LASTEXITCODE=0
