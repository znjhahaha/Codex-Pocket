param([string]$Root = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))), [string]$BridgePath)
$ErrorActionPreference = 'Stop'
. (Join-Path $Root 'scripts\windows-common.ps1')
$package = Get-AppxPackage -Name OpenAI.Codex
if (-not $package) { throw 'Original Codex desktop package is not installed.' }
$original = Join-Path $package.InstallLocation 'app\resources\codex.exe'
$bridge = if ($BridgePath) { $BridgePath } else { Join-Path $Root 'target\debug\pocket-bridge.exe' }
$data = Join-Path $Root '.local\desktop-ui-probe'
New-Item -ItemType Directory -Force -Path $data | Out-Null
@{ binary = $original; windowsSandboxPackageFamily = $package.PackageFamilyName } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $bridge) 'pocket-original.json') -Encoding utf8NoBOM
$application = Join-Path $package.InstallLocation 'app\ChatGPT.exe'
if (-not (Test-Path -LiteralPath $application)) { $application = Join-Path $package.InstallLocation 'app\Codex.exe' }
$overrides = @{
    CODEX_CLI_PATH=$bridge
    CODEX_ELECTRON_USER_DATA_PATH=(Join-Path $data 'user-data')
    POCKET_RUNTIME_DIR=(Join-Path $data 'runtimes')
    CODEX_HOME=(Join-Path $env:USERPROFILE '.codex')
}
$previous = @{}
$logName = 'desktop-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss')
$stdout = Join-Path $data ($logName + '.stdout.log')
$stderr = Join-Path $data ($logName + '.stderr.log')
try {
    foreach ($name in $overrides.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        [Environment]::SetEnvironmentVariable($name,$overrides[$name],'Process')
    }
    # This is the explicitly requested independent interactive test window.
    $process = Start-Process -FilePath $application -ArgumentList (ConvertTo-PocketArgument ('--user-data-dir=' + (Join-Path $data 'user-data'))) -WorkingDirectory $Root -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
@{ pid=$process.Id; startedAt=$process.StartTime.ToUniversalTime().ToString('o'); profile=$data; original=$original; wrapper=$bridge; stdout=$stdout; stderr=$stderr } | ConvertTo-Json | Set-Content (Join-Path $data 'launch.json')
Write-Output "Started isolated desktop test profile, PID $($process.Id). Original app profile was not changed."
