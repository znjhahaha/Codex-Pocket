#requires -Version 5.1
[CmdletBinding()]
param([ValidateSet('Status','Enable','Disable','Start')][string]$Action='Status',[string]$InstallRoot)
. (Join-Path $PSScriptRoot 'windows-common.ps1')

function Get-PocketTaskName { return 'CodexPocket-' + [Security.Principal.WindowsIdentity]::GetCurrent().User.Value }
function Test-PocketTaskMissing($Failure) {
    $exception=$Failure.Exception
    while ($null -ne $exception.InnerException) { $exception=$exception.InnerException }
    # Only GetTask's exact Win32 not-found HRESULTs prove absence. A wrapper
    # exception, access failure or another facility with low word 2/3 does not.
    return ('0x'+$exception.HResult.ToString('X8')) -in @('0x80070002','0x80070003')
}
function Get-PocketTaskXml {
    $service = Invoke-PocketInstallStep 'startup_read' 'CoCreateInstance(Schedule.Service)' { New-Object -ComObject Schedule.Service }
    $folder=$null; $task=$null
    try {
        Invoke-PocketInstallStep 'startup_read' 'ITaskService.Connect' { $service.Connect() } | Out-Null
        $folder=Invoke-PocketInstallStep 'startup_read' 'ITaskService.GetFolder' { $service.GetFolder('\') }
        try { $task=Invoke-PocketInstallStep 'startup_read' 'ITaskFolder.GetTask' { $folder.GetTask((Get-PocketTaskName)) } }
        catch { if (Test-PocketTaskMissing $_) { return $null }; throw }
        return Invoke-PocketInstallStep 'startup_read' 'IRegisteredTask.Xml' { $task.Xml }
    } finally { foreach($value in @($task,$folder,$service)){if($null -ne $value){[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($value)}} }
}
function Set-PocketTaskXml([AllowNull()]$Xml) {
    $service = Invoke-PocketInstallStep 'startup_register' 'CoCreateInstance(Schedule.Service)' { New-Object -ComObject Schedule.Service }
    $folder=$null
    try {
        Invoke-PocketInstallStep 'startup_register' 'ITaskService.Connect' { $service.Connect() } | Out-Null
        $folder = Invoke-PocketInstallStep 'startup_register' 'ITaskService.GetFolder' { $service.GetFolder('\') }
        if ($null -eq $Xml) {
            if ($null -ne (Get-PocketTaskXml)) { Invoke-PocketInstallStep 'startup_register' 'ITaskFolder.DeleteTask' { $folder.DeleteTask((Get-PocketTaskName),0) } | Out-Null }
        } else {
            # TASK_CREATE_OR_UPDATE + TASK_LOGON_INTERACTIVE_TOKEN; never store a password.
            Invoke-PocketInstallStep 'startup_register' 'ITaskFolder.RegisterTask' { [void]$folder.RegisterTask((Get-PocketTaskName),$Xml,6,[Security.Principal.WindowsIdentity]::GetCurrent().User.Value,$null,3,$null) } | Out-Null
        }
    } finally { foreach($value in @($folder,$service)){if($null -ne $value){[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($value)}} }
}
function New-PocketTaskXml([string]$Launcher,[bool]$Logon=$true) {
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $command = [Security.SecurityElement]::Escape($Launcher)
    $trigger = if ($Logon) { '<LogonTrigger><Enabled>true</Enabled><UserId>'+ $user +'</UserId><Delay>PT10S</Delay></LogonTrigger>' } else { '' }
    return @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
 <RegistrationInfo><Description>Codex Pocket current-user supervisor v3</Description></RegistrationInfo>
 <Triggers>$trigger</Triggers>
 <Principals><Principal id="Owner"><UserId>$user</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
 <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><AllowHardTerminate>true</AllowHardTerminate><StartWhenAvailable>true</StartWhenAvailable><AllowStartOnDemand>true</AllowStartOnDemand><Enabled>true</Enabled><Hidden>true</Hidden><ExecutionTimeLimit>PT0S</ExecutionTimeLimit><RestartOnFailure><Interval>PT1M</Interval><Count>999</Count></RestartOnFailure></Settings>
 <Actions Context="Owner"><Exec><Command>$command</Command><Arguments>--pocket-supervise</Arguments></Exec></Actions>
</Task>
"@
}
function Assert-PocketTaskOwned([AllowNull()]$Xml,[string]$Launcher) {
    if ($null -eq $Xml) { return }
    try { $document=[xml]$Xml } catch { throw 'Invalid scheduled task XML; refusing drift.' }
    $ns=[Xml.XmlNamespaceManager]::new($document.NameTable)
    $ns.AddNamespace('t','http://schemas.microsoft.com/windows/2004/02/mit/task')
    $task=$document.SelectSingleNode('/t:Task',$ns)
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ($null -eq $task) { throw 'Invalid scheduled task; refusing drift.' }
    $expected=@{
        't:RegistrationInfo/t:Description'='Codex Pocket current-user supervisor v3'
        't:Actions/t:Exec/t:Command'=$Launcher; 't:Actions/t:Exec/t:Arguments'='--pocket-supervise'
        't:Principals/t:Principal/t:UserId'=$user; 't:Principals/t:Principal/t:LogonType'='InteractiveToken'
        't:Principals/t:Principal/t:RunLevel'='LeastPrivilege'
        't:Settings/t:MultipleInstancesPolicy'='IgnoreNew'; 't:Settings/t:Enabled'='true'
    }
    foreach($path in $expected.Keys) {
        $nodes=$task.SelectNodes($path,$ns)
        # Task Scheduler omits these default values from registered XML.
        if ($nodes.Count -eq 0 -and $path -in @('t:Principals/t:Principal/t:RunLevel','t:Settings/t:Enabled')) { continue }
        if($nodes.Count -ne 1 -or $nodes[0].InnerText -ne $expected[$path]) { throw 'Scheduled task changed outside this installer; refusing drift.' }
    }
    if ($task.SelectNodes('t:Actions/*',$ns).Count -ne 1 -or $task.SelectNodes('t:Principals/*',$ns).Count -ne 1 -or
        $task.SelectNodes('t:Actions/t:Exec/*',$ns).Count -ne 2 -or $task.SelectNodes('t:Triggers/*',$ns).Count -gt 1) {
        throw 'Scheduled task changed outside this installer; refusing drift.'
    }
    foreach($trigger in $task.SelectNodes('t:Triggers/*',$ns)) {
        $triggerUser=$trigger.SelectSingleNode('t:UserId',$ns); $enabled=$trigger.SelectSingleNode('t:Enabled',$ns)
        $sameUser=$false
        if($null -ne $triggerUser) {
            $sameUser=$triggerUser.InnerText -eq $user
            if(-not $sameUser){try{$account=[Security.Principal.NTAccount]::new($triggerUser.InnerText);$sameUser=$account.Translate([Security.Principal.SecurityIdentifier]).Value -eq $user}catch{$sameUser=$false}}
        }
        if($trigger.LocalName -ne 'LogonTrigger' -or -not $sameUser -or
            ($null -ne $enabled -and $enabled.InnerText -ne 'true')) { throw 'Scheduled task logon trigger drift.' }
    }
}
function Test-PocketTaskLogon([AllowNull()]$Xml) {
    if ($null -eq $Xml) { return $false }
    $document=[xml]$Xml
    return $null -ne $document.SelectSingleNode('//*[local-name()="LogonTrigger"][not(*[local-name()="Enabled"]) or *[local-name()="Enabled" and text()="true"]]')
}
function Test-PocketTaskEquivalent([AllowNull()]$Left,[AllowNull()]$Right) {
    if($Left -ceq $Right){return $true}
    if($null -eq $Left -or $null -eq $Right){return $false}
    # In-memory definitions apply Scheduler's default fields/order without
    # registering, running or changing a task. Keep the original XML for restore.
    $service=Invoke-PocketInstallStep 'startup_compare' 'CoCreateInstance(Schedule.Service)' { New-Object -ComObject Schedule.Service }
    $first=$null; $second=$null
    try {
        Invoke-PocketInstallStep 'startup_compare' 'ITaskService.Connect' { $service.Connect() } | Out-Null
        $first=Invoke-PocketInstallStep 'startup_compare' 'ITaskService.NewTask' { $service.NewTask(0) }
        Invoke-PocketInstallStep 'startup_compare' 'ITaskDefinition.XmlText(left)' { $first.XmlText=[string]$Left } | Out-Null
        $second=Invoke-PocketInstallStep 'startup_compare' 'ITaskService.NewTask' { $service.NewTask(0) }
        Invoke-PocketInstallStep 'startup_compare' 'ITaskDefinition.XmlText(right)' { $second.XmlText=[string]$Right } | Out-Null
        return $first.XmlText -ceq $second.XmlText
    }
    finally {
        foreach($value in @($first,$second,$service)){if($null -ne $value){[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($value)}}
    }
}
function Start-PocketTask {
    $service = New-Object -ComObject Schedule.Service
    try { $service.Connect(); [void]$service.GetFolder('\').GetTask((Get-PocketTaskName)).Run($null) }
    finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($service) }
}
function Invoke-PocketStartup([ValidateSet('Status','Enable','Disable','Start')][string]$Action,[string]$InstallRoot) {
    $root = if($InstallRoot){Get-PocketDirectoryPath $InstallRoot}else{Join-Path (Get-PocketLocalAppData) 'CodexPocket'}
    Assert-PocketLocalPath $root
    Assert-PocketUnredirectedPath $root
    $launcher = Join-Path $root 'pocket-launcher.exe'
    $recordPath = Join-Path $root 'desktop-install.json'
    Assert-PocketPlainPath $recordPath
    $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($record.version -ne 3 -or $record.installRoot -ne $root -or $record.bridge -ne $launcher -or (Get-PocketFileHash $launcher) -ne $record.launcherHash) { throw 'Stable launcher integrity failed.' }
    $xml = Get-PocketTaskXml
    Assert-PocketTaskOwned $xml $launcher
    if ($Action -in @('Enable','Disable')) { Set-PocketTaskXml (New-PocketTaskXml $launcher ($Action -eq 'Enable')); $xml = Get-PocketTaskXml }
    if ($Action -eq 'Start') {
        if ($null -eq $xml) { Set-PocketTaskXml (New-PocketTaskXml $launcher $false); $xml = Get-PocketTaskXml }
        Start-PocketTask
    }
    return @{enabled=(Test-PocketTaskLogon $xml);registered=($null -ne $xml)}
}
if ($MyInvocation.InvocationName -ne '.') { $ErrorActionPreference='Stop'; Invoke-PocketStartup -Action $Action -InstallRoot $InstallRoot | ConvertTo-Json -Compress }
