$ErrorActionPreference='Stop'
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$sid=$identity.User.Value
$name=$env:POCKET_TASK_NAME
$exe=$env:POCKET_TASK_EXE
$arguments=$env:POCKET_TASK_ARGS
$directory=$env:POCKET_TASK_DIRECTORY
$scheduler=New-Object -ComObject 'Schedule.Service'
$scheduler.Connect()
$folder=$scheduler.GetFolder('\')
$existing=$null
try { $existing=$folder.GetTask($name) } catch {
    if($_.Exception.HResult -ne -2147024894){throw}
}
function Assert-Owned($Task) {
    $definition=$Task.Definition
    $owner=$definition.Principal.UserId
    if($owner -ne $sid){$owner=([Security.Principal.NTAccount]::new($owner)).Translate([Security.Principal.SecurityIdentifier]).Value}
    if($owner -ne $sid -or $definition.Principal.RunLevel -ne 0 -or $definition.Principal.LogonType -ne 3 -or $definition.Actions.Count -ne 1){throw 'Update task ownership changed'}
    $action=$definition.Actions.Item(1)
    if($action.Type -ne 0 -or $action.Path -ne $exe -or $action.Arguments -ne $arguments -or $action.WorkingDirectory -ne $directory){throw 'Update task action changed'}
}
if($existing){Assert-Owned $existing}
if($env:POCKET_TASK_REMOVE -eq '1'){
    if($existing){$folder.DeleteTask($name,0)}
    exit 0
}
if(!$existing){
    $definition=$scheduler.NewTask(0)
    $definition.RegistrationInfo.Author=$sid
    $definition.RegistrationInfo.Description='Codex Pocket update transaction'
    $definition.Principal.UserId=$sid
    $definition.Principal.LogonType=3
    $definition.Principal.RunLevel=0
    $definition.Settings.Enabled=$true
    $definition.Settings.Hidden=$true
    $definition.Settings.AllowDemandStart=$true
    $definition.Settings.DisallowStartIfOnBatteries=$false
    $definition.Settings.StopIfGoingOnBatteries=$false
    $definition.Settings.ExecutionTimeLimit='PT0S'
    $definition.Settings.MultipleInstances=2
    $action=$definition.Actions.Create(0)
    $action.Path=$exe
    $action.Arguments=$arguments
    $action.WorkingDirectory=$directory
    # Create only: a foreign task must never be overwritten after our check.
    $existing=$folder.RegisterTaskDefinition($name,$definition,2,$sid,$null,3,$null)
    Assert-Owned $existing
}
[void]$existing.Run($null)
