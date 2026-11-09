#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$passed=0
function Check([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:passed++;Write-Output ('PASS '+$Message)}
Check (-not (Test-PocketWebView2 -ReadVersion {throw 'missing registry key'})) 'missing runtime is rejected'
foreach($value in @($null,'','0.0.0.0','not-a-version','140.0')) {
    Check (-not (Test-PocketWebView2 -ReadVersion {$value})) ('invalid runtime version rejected: '+[string]$value)
}
Check (Test-PocketWebView2 -ReadVersion {param($Path) if($Path.StartsWith('HKCU:')){'140.0.1.2'}else{throw 'missing'}}) 'current-user Evergreen runtime is accepted'
Check (Test-PocketWebView2 -ReadVersion {param($Path) if($Path.Contains('WOW6432Node')){'140.0.1.2'}else{throw 'missing'}}) 'machine Evergreen runtime is accepted'
$script:paths=@()
[void](Test-PocketWebView2 -ReadVersion {param($Path) $script:paths+=$Path;throw 'missing'})
Check (@($script:paths | Where-Object {$_ -notlike '*{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}'}).Count -eq 0) 'browser registrations cannot satisfy the runtime check'
$installer=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -Raw
Check ($installer.Contains('MUI_PAGE_CUSTOMFUNCTION_LEAVE CheckWebView') -and $installer.Contains('-CheckWebViewOnly') -and $installer.Contains('ExecShell "open" "https://developer.microsoft.com/microsoft-edge/webview2/"')) 'graphical Setup checks before installation and offers the official download page'
Check ($installer.Contains('$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe') -and $installer.Contains('${RunningX64}')) '32-bit NSIS invokes the native 64-bit prerequisite and install host'
Write-Output "$passed prerequisite checks passed; registry, browser and installation were not changed"
