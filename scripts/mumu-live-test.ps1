[CmdletBinding()]
param(
    [string]$Serial = '127.0.0.1:16416',
    [string]$StateDirectory = (Join-Path $PSScriptRoot '..\.local\agent'),
    [string]$AgentPath = (Join-Path $PSScriptRoot '..\target\debug\pocket-agent.exe'),
    [switch]$Pair,
    [switch]$Workflow,
    [switch]$Security,
    [switch]$SkipDesktopMutation,
    [string]$DesktopThreadId,
    [string]$FixtureDirectory
)
$ErrorActionPreference = 'Stop'
if ($Pair -and $Security) { throw 'Pair and Security consume separate invitations; run them separately.' }
if ($Workflow -and ([string]::IsNullOrWhiteSpace($DesktopThreadId) -or [string]::IsNullOrWhiteSpace($FixtureDirectory))) { throw 'Workflow requires an explicit -DesktopThreadId and -FixtureDirectory for dedicated test chats.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
function Invoke-Adb([string[]]$Arguments) {
    $output = & $adb -s $Serial @Arguments 2>&1
    $output | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'ADB operation failed' }
    if ($Arguments -contains 'instrument' -and (($output -join "`n") -notmatch 'OK \([1-9][0-9]* tests?\)')) { throw 'Instrumentation did not confirm passing tests' }
}
Invoke-Adb -Arguments @('get-state')
Invoke-Adb -Arguments @('install','-r',(Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk'))
Invoke-Adb -Arguments @('install','-r',(Join-Path $root 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'))
if ($Pair -or $Security) {
    & $AgentPath pair --state $StateDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Pairing generation failed' }
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $adb
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($arg in @('-s',$Serial,'shell',"run-as dev.codexpocket.app sh -c 'mkdir -p files/integration && cat > files/integration/pairing.json'")) { $psi.ArgumentList.Add($arg) }
    $process = [Diagnostics.Process]::Start($psi)
    $inputPath = Join-Path $StateDirectory 'pairing-qr.json'
    $stream = [IO.File]::OpenRead($inputPath)
    try { $stream.CopyTo($process.StandardInput.BaseStream) } finally { $stream.Dispose(); $process.StandardInput.Close() }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(15000)) { $process.Kill(); throw 'Private pairing input timed out' }
    $null = $stdout.GetAwaiter().GetResult()
    $null = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw 'Private pairing input failed; sensitive diagnostics omitted' }
    if ($Pair) { Invoke-Adb -Arguments @('shell','am','instrument','-w','-e','class','dev.codexpocket.app.LivePairingTest','-e','livePairing','true','dev.codexpocket.app.test/androidx.test.runner.AndroidJUnitRunner') }
    if ($Security) { Invoke-Adb -Arguments @('shell','am','instrument','-w','-e','class','dev.codexpocket.app.LiveSecurityTest','-e','liveSecurity','true','dev.codexpocket.app.test/androidx.test.runner.AndroidJUnitRunner') }
}
if ($Workflow) {
    $arguments = @('shell','am','instrument','-w','-e','class','dev.codexpocket.app.LiveWorkflowTest','-e','liveWorkflow','true','-e','desktopThreadId',$DesktopThreadId,'-e','fixtureCwd',$FixtureDirectory)
    if ($SkipDesktopMutation) { $arguments += @('-e','skipDesktopMutation','true') }
    Invoke-Adb -Arguments ($arguments + 'dev.codexpocket.app.test/androidx.test.runner.AndroidJUnitRunner')
}
