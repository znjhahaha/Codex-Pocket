#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ReleaseDirectory,[Parameter(Mandatory)][string]$OutputDirectory,[string]$MakeNsis)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$release=Get-PocketDirectoryPath $ReleaseDirectory
$source=Join-Path $release 'windows'
$manifest=Test-PocketWindowsDirectory $source
foreach($name in @('cloudflared.exe','cloudflared-LICENSE.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $source $name) -PathType Leaf)) { throw "Release payload missing $name" }
}
if (-not $MakeNsis) {
    $command=Get-Command makensis -ErrorAction SilentlyContinue
    if($command){$MakeNsis=$command.Source}
    else {
        $MakeNsis=@((Join-Path $env:LOCALAPPDATA 'tauri/NSIS/makensis.exe'),(Join-Path ${env:ProgramFiles(x86)} 'NSIS/makensis.exe')) | Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
    }
}
if (-not $MakeNsis -or -not (Test-Path -LiteralPath $MakeNsis -PathType Leaf)) { throw 'NSIS makensis is required. Install NSIS before building Setup.' }
$output=Get-PocketDirectoryPath $OutputDirectory
Assert-PocketPlainPath $output
[void][IO.Directory]::CreateDirectory($output)
$setup=Join-Path $output ('codex-pocket-'+$manifest.version+'-windows-setup.exe')
if(Test-Path -LiteralPath $setup){throw 'Setup output already exists.'}
# NSIS substitution escaping; source is the verified release payload, never target/release.
function Quote-Nsis([string]$Value) { return $Value.Replace('$','$$').Replace('"','$\"') }
$payload=Quote-Nsis $source
$destination=Quote-Nsis $setup
$script=@'
Unicode true
RequestExecutionLevel user
SetCompressor /SOLID lzma
Name "Codex Pocket"
OutFile "__OUTPUT__"
!include "MUI2.nsh"
!include "x64.nsh"
!define MUI_WELCOMEPAGE_TEXT "This installs Codex Pocket for your current Windows account.$\r$\n$\r$\nBefore continuing, install Codex Desktop, Microsoft Edge WebView2 Evergreen Runtime and Microsoft Visual C++ x64 Runtime.$\r$\n$\r$\nWebView2: https://developer.microsoft.com/microsoft-edge/webview2/$\r$\nVisual C++: https://aka.ms/vs/17/release/vc_redist.x64.exe$\r$\n$\r$\nSetup preserves existing pairing. After installation, open Codex Pocket from the Start menu."
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE CheckWebView
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "English"
Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "Codex Pocket requires 64-bit Windows."
    SetErrorLevel 1
    Quit
  ${EndIf}
FunctionEnd
Function CheckWebView
  InitPluginsDir
  SetOutPath "$PLUGINSDIR\prerequisites"
  File "__PAYLOAD__\windows-common.ps1"
  File "__PAYLOAD__\windows-install.ps1"
  nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\prerequisites\windows-install.ps1" -CheckWebViewOnly'
  Pop $0
  Pop $1
  StrCmp $0 "0" webview_ready
  StrCmp $0 "42" webview_missing
  MessageBox MB_ICONSTOP "Setup could not check WebView2. Please retry. No installation changes were made."
  Abort
webview_missing:
  MessageBox MB_YESNO|MB_ICONINFORMATION "Microsoft Edge WebView2 Runtime is required to show Codex Pocket.$\r$\n$\r$\nOpen Microsoft's official download page now? Choose Evergreen Standalone Installer (x64), install it, return here and click Next again.$\r$\n$\r$\nYour existing pairing and installation have not been changed." IDNO webview_wait
  ExecShell "open" "https://developer.microsoft.com/microsoft-edge/webview2/"
webview_wait:
  Abort
webview_ready:
FunctionEnd
Section "Codex Pocket"
  SetShellVarContext current
  InitPluginsDir
  SetOutPath "$PLUGINSDIR\payload"
  File /r "__PAYLOAD__\*"
  DetailPrint "Installing for the current Windows user..."
  nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\payload\windows-install.ps1" -SourceDirectory "$PLUGINSDIR\payload" -Initialize -Apply'
  Pop $0
  Pop $1
  StrCmp $0 "0" installed
  DetailPrint "$1"
  MessageBox MB_ICONSTOP "Installation could not complete. Existing pairing and rollback state are retained. Open Show Details to read the failure. If WebView2 is missing, install the Evergreen Standalone Installer from developer.microsoft.com/microsoft-edge/webview2, then run Setup again."
  SetErrorLevel 1
  Abort
installed:
  DetailPrint "Installed. Open Codex Pocket from the Start menu to choose a connection and pair your phone."
SectionEnd
'@
$script=$script.Replace('__PAYLOAD__',$payload).Replace('__OUTPUT__',$destination)
$temporary=Join-Path $output ('setup-'+[guid]::NewGuid().ToString('N')+'.nsi')
try {
    [IO.File]::WriteAllText($temporary,$script,[Text.UTF8Encoding]::new($false))
    & $MakeNsis /V2 $temporary
    if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $setup)){throw 'NSIS Setup compilation failed.'}
    # Recheck the exact input after packaging to detect concurrent release changes.
    Test-PocketWindowsDirectory $source | Out-Null
    [ordered]@{setup=$setup;version=$manifest.version;sha256=(Get-PocketFileHash $setup)} | ConvertTo-Json
} finally { if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary} }
