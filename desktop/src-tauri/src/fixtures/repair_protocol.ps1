# Deterministic protocol fixture. No OS integration, native process, or state mutation.
param([string]$RepairStep,[string]$OperationId,[string]$ExpectedGeneration,[switch]$CheckRepair)
$state=if($CheckRepair){'unknown'}else{'failed'}
$code=if($CheckRepair){'operation_unknown'}else{'integration_repair_failed'}
$exitCode=if($CheckRepair){3}else{1}
@{schemaVersion=1;action='repair_integration';operationId=$OperationId;step=$RepairStep;expectedGeneration=$ExpectedGeneration;
 state=$state;code=$code;stage='startup_register';call='Set-PocketTaskXml';hresult='0x80070005';recoveryState='clear';retryable=$false;
 coreInstalled=$true;runtimeVerification='pending';processesStarted=0;processesStopped=0;exitCode=$exitCode;privateExtra='fixture-private-token'} | ConvertTo-Json -Compress
exit $exitCode
