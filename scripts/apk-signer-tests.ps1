#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'build-release.ps1')
$digest='2d53a8ae9d77804fdcb1174cfcd81548ea97ae6a664ce6f1407fd6c1f5a57a8a'
$foreign='f' * 64
$passed=0
function Verify([string[]]$Lines,[bool]$Accept) {
    $accepted=$true
    try { Assert-PocketApkSignerIdentity $Lines $digest } catch { $accepted=$false }
    if($accepted -ne $Accept){throw "Signer parsing fixture failed: $($Lines -join '; ')"}
    $script:passed++
}
# Exact public certificate line captured from Android build-tools 37.0.0 in CI.
Verify @('Number of signers: 1',"V2 Signer: certificate SHA-256 digest: $digest") $true
Verify @('Number of signers: 1',"Signer #1 certificate SHA-256 digest: $digest") $true
Verify @('Number of signers: 1',"V2 Signer: certificate SHA-256 digest: $digest", "V3 Signer: certificate SHA-256 digest: $digest") $true
Verify @('Number of signers: 1',"V2 Signer: certificate SHA-256 digest: $digest", "V3 Signer: certificate SHA-256 digest: $foreign") $false
Verify @('Number of signers: 2',"Signer #1 certificate SHA-256 digest: $digest", "Signer #2 certificate SHA-256 digest: $foreign") $false
Verify @('Number of signers: 1',"Signer #1 public key SHA-256 digest: $digest") $false
Verify @('Number of signers: 1',"Source Stamp Signer certificate SHA-256 digest: $digest") $false
Verify @('Number of signers: 1',"V2 Signer: certificate SHA-256 digest: $foreign") $false
Verify @('Number of signers: 1',"Future Signer: certificate SHA-256 digest: $digest") $false
Verify @("V2 Signer: certificate SHA-256 digest: $digest") $false
Write-Output "$passed APK signer fixtures passed."
