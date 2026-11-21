#requires -Version 5.1
[CmdletBinding()]
param([string]$SourceDirectory,[string]$InstallRoot,[string]$UpdateOfferPath,[string]$TrustedUpdatePublicKey)

# Embedded in the locally authorized Setup alongside its pinned public key.
# A downloaded offer cannot select this key or the verifier executable.
function Get-PocketSetupRoute([string]$Source,[string]$Root,[string]$Offer,[string]$Key) {
    if([bool]$Offer -ne [bool]$Key){throw 'Setup requires both a signed offer and its independently pinned key'}
    if($Key -and ([Convert]::FromBase64String($Key)).Length -ne 32){throw 'Invalid pinned Ed25519 public key'}
    $record=Join-Path $Root 'desktop-install.json'
    Assert-PocketPlainPath $record
    if(Test-Path -LiteralPath $record){
        if(-not $Offer){throw 'Existing installations require the signed local update Setup; no processes were stopped'}
        return @{mode='signed-update';arguments=@('-SourceDirectory',$Source,'-InstallRoot',$Root,'-UpdateOfferPath',$Offer,'-TrustedUpdatePublicKey',$Key,'-Apply')}
    }
    if($Root -ne (Join-Path (Get-PocketLocalAppData) 'CodexPocket')){throw 'Fresh Setup requires the default current-user root'}
    return @{mode='fresh-install';arguments=@('-SourceDirectory',$Source,'-Initialize','-Apply')}
}
if($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference='Stop'
    . (Join-Path $SourceDirectory 'windows-common.ps1')
    $SourceDirectory=Get-PocketDirectoryPath $SourceDirectory
    $InstallRoot=if($InstallRoot){Get-PocketDirectoryPath $InstallRoot}else{Join-Path (Get-PocketLocalAppData) 'CodexPocket'}
    $route=Get-PocketSetupRoute $SourceDirectory $InstallRoot $UpdateOfferPath $TrustedUpdatePublicKey
    Test-PocketWindowsDirectory $SourceDirectory | Out-Null
    if($UpdateOfferPath){
        Assert-PocketPlainPath $UpdateOfferPath
        $verifyArgs=@('--verify-local-source','--source',('"'+$SourceDirectory+'"'),'--offer',('"'+$UpdateOfferPath+'"'),'--trusted-key',$TrustedUpdatePublicKey)
        $verification=Start-Process -FilePath (Join-Path $SourceDirectory 'pocket-upgrade.exe') -ArgumentList $verifyArgs -WindowStyle Hidden -Wait -PassThru
        if($verification.ExitCode -ne 0){throw 'Setup signature or payload verification failed; no installation was changed'}
    }
    $arguments=$route.arguments
    # Separate process: windows-install.ps1 owns its exit code and durable errors.
    & (Join-Path $PSHOME 'powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $SourceDirectory 'windows-install.ps1') @arguments
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    if($route.mode -eq 'signed-update'){exit 10}
    exit 0
}
