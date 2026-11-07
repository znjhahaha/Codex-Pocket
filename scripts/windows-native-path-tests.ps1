#requires -Version 5.1
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('..\.local\windows-readiness-tests\native-path-'+[guid]::NewGuid().ToString('N'))))
[void][IO.Directory]::CreateDirectory($root)
$passed=0
function Assert($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Fails([scriptblock]$Action){try{& $Action;throw 'guard did not reject'}catch{Assert ($_.Exception.Message -match 'redirected.*standalone Windows PowerShell') $_.Exception.Message}}
$plain=Join-Path $root 'plain.txt'; [IO.File]::WriteAllText($plain,'fixture')
Assert ((Get-PocketPhysicalPath $plain) -eq $plain) 'Physical file path differs'; $passed++
Assert-PocketUnredirectedPath $root -ProbeWrite
Assert (@(Get-ChildItem -LiteralPath $root -Force).Count -eq 1) 'Probe left a file'; $passed++
$script:physicalResolver=${function:Get-PocketPhysicalPath}
function Get-PocketPhysicalPath([string]$Path){return (Join-Path $root 'Packages\fixture\LocalCache\shadow')}
Fails { Assert-PocketUnredirectedPath $root }; $passed++
${function:Get-PocketPhysicalPath}=$script:physicalResolver
# A missing child under a redirected ancestor must be rejected before creation.
function Get-PocketPhysicalPath([string]$Path){return (Join-Path $root 'shadow')}
Fails { Assert-PocketUnredirectedPath (Join-Path $root 'absent\child') }
Assert (-not (Test-Path -LiteralPath (Join-Path $root 'absent'))) 'Guard created install path'; $passed++
${function:Get-PocketPhysicalPath}=$script:physicalResolver
# Model write-only virtualization: reads of the parent are canonical, new files are not.
function Get-PocketPhysicalPath([string]$Path){if([IO.File]::Exists($Path)){return Join-Path $root 'shadow.txt'}; return & $script:physicalResolver $Path}
Fails { Assert-PocketUnredirectedPath $root -ProbeWrite }
Assert (@(Get-ChildItem -LiteralPath $root -Force).Count -eq 1) 'Rejected probe was not cleaned up'; $passed++
${function:Get-PocketPhysicalPath}=$script:physicalResolver
. (Join-Path $PSScriptRoot 'windows-install.ps1')
function Get-PocketPhysicalPath([string]$Path){return (Join-Path $root 'Packages\fixture\LocalCache\shadow')}
function Get-PocketTaskXml { throw 'Integration read occurred before redirection guard' }
function Get-PocketUserCliPath { throw 'Environment read occurred before redirection guard' }
$context=@{InstallRoot=(Join-Path $root 'install');Programs=(Join-Path $root 'programs');Startup=(Join-Path $root 'startup')}
Fails { Invoke-PocketWindowsInstall -Context $context -Apply }
Assert (-not (Test-Path -LiteralPath $context.InstallRoot)) 'Installer wrote before rejecting redirection'; $passed++
Fails { Invoke-PocketStartup -Action Start -InstallRoot $context.InstallRoot }; $passed++
Write-Output ("$passed native path tests passed; $root")
