#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-install.ps1')
$script:runtimeAttached=$false;$script:prepare=0;$script:ready=$false;$script:assistantCode='assistant_runtime_start_failed'
function Invoke-PocketInstallControl([string]$StateDirectory,[string]$Method,$Params=@{}) {
    if($Method -eq 'assistant/status'){return @{ready=$script:ready;runtimeReady=($script:ready -or $script:runtimeAttached);error=$script:assistantCode;reconciliationRequired=$false}}
    if($Method -eq 'assistant/prepare'){$script:prepare++;if(-not $Params.commandId){throw 'Missing durable retry identity'};return @{ready=$false}}
    throw 'Unexpected fixture control method'
}
function Assert($Value,[string]$Message){if(-not $Value){throw $Message}}
$lifecycle=@{};$script:reserved=$false
Assert (-not (Invoke-PocketInstallerAssistantRecovery 'fixture-state' $lifecycle {$script:reserved=$lifecycle.assistantRecoveryAttempts -eq 1})) 'Dispatch claimed readiness'
Assert $script:reserved 'Retry identity not reserved before dispatch'
Assert ($script:prepare -eq 1 -and $lifecycle.assistantRecoveryAttempts -eq 1) 'First helper never retried transient preparation'
$script:ready=$true;$script:assistantCode=$null
Assert (Invoke-PocketInstallerAssistantRecovery 'fixture-state' $lifecycle) 'Fresh authenticated readiness not accepted'
Assert ($script:prepare -eq 1) 'Ready assistant was prepared again'
$script:ready=$false
foreach($script:assistantCode in @('assistant_preparing','assistant_ownership_unconfirmed','assistant_profile_invalid','assistant_restore_failed')) {
    Assert (-not (Invoke-PocketInstallerAssistantRecovery 'fixture-state' @{})) 'Permanent or in-flight error accepted'
}
Assert ($script:prepare -eq 1) 'Ownership/profile/in-flight failure triggered another native host'
$script:assistantCode='assistant_runtime_start_failed';$lifecycle=@{}
for($i=0;$i -lt 8;$i++) {Invoke-PocketInstallerAssistantRecovery 'fixture-state' $lifecycle | Out-Null}
Assert ($lifecycle.assistantRecoveryAttempts -eq 3 -and $script:prepare -eq 4) 'Retry budget exceeded'
$script:ready=$true
Assert (Invoke-PocketInstallerAssistantRecovery 'fixture-state' $lifecycle) 'Last attempt got no verification opportunity'
Write-Output 'PASS first helper recovery, dispatch/readiness distinction, permanent/in-flight blockers, three-attempt budget and final readback'

$script:ready=$false;$script:runtimeAttached=$true;$script:assistantCode='assistant_needs_login';$lifecycle=@{}
Assert (Invoke-PocketInstallerAssistantRecovery 'fixture-state' $lifecycle) 'Clean install incorrectly required an account login'
Assert ($lifecycle.assistantAccess -eq 'needs_login') 'Assistant login requirement was hidden'
Assert ($script:prepare -eq 4) 'Installer tried to recover an already running assistant'
