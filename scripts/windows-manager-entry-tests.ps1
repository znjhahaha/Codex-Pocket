#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-startup.ps1')
# No scheduler writes; exercise the conversion against the actual task schema.
$root=Join-Path $env:TEMP ('pocket-entry-'+[guid]::NewGuid().ToString('N'))
$launcher=Join-Path $root 'pocket-launcher.exe'
$src=Join-Path $root 'fixture';[void][IO.Directory]::CreateDirectory($src)
[IO.File]::WriteAllText((Join-Path $src 'pocket-launcher.exe'),'fixture never executed')
$manifest=Join-Path $src 'windows-manifest.json'
[IO.File]::WriteAllText($manifest,(@{files=@(@{path='pocket-launcher.exe';sha256=Get-PocketFileHash (Join-Path $src 'pocket-launcher.exe')})}|ConvertTo-Json))
$bin=Join-Path (Join-Path $root 'bin') (Get-PocketFileHash $manifest);[void][IO.Directory]::CreateDirectory($bin)
Copy-Item -LiteralPath $manifest,(Join-Path $src 'pocket-launcher.exe') -Destination $bin
$manager=Join-Path $bin 'pocket-launcher.exe'
foreach($logon in @($false,$true)) {
    $old=New-PocketTaskXml $launcher $logon
    $converted=ConvertTo-PocketManagerTask $old $launcher $manager
    $document=[xml]$converted
    $taskExec=$document.SelectSingleNode('//*[local-name()="Exec"]')
    if($taskExec.Command -ne $manager -or $taskExec.Arguments -ne '--pocket-supervise'){throw 'Manager boot entry missing'}
    if((Test-PocketTaskLogon $converted) -ne $logon){throw 'Login preference lost'}
    Assert-PocketTaskOwned $converted $launcher
    if((ConvertTo-PocketManagerTask $converted $launcher $manager) -cne $converted){throw 'Non-idempotent migration'}
    $foreign=$old.Replace($launcher,'C:\foreign.exe');$refused=$false
    try{ConvertTo-PocketManagerTask $foreign $launcher $manager|Out-Null}catch{$refused=$true}
    if(!$refused){throw 'Foreign task migrated'}
}
Write-Output 'PASS manager login migration preserves enabled and disabled triggers and rejects a foreign action'
