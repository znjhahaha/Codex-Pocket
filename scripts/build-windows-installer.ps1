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
!define MUI_FINISHPAGE_TITLE "安装文件已就绪"
!define MUI_FINISHPAGE_TEXT "安装文件已经验证，已有配对和配置会保留。$\r$\n$\r$\n打开 Pocket 查看后台是否已生效。若旧版本不支持安全交接，请先保存工作，再退出并重新登录 Windows；只重开 Codex 不一定会更新常驻后台。$\r$\n$\r$\n后台未启动时，Pocket 会显示自动重试进度，也可以手动重试。"
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "打开 Pocket 检查后台状态"
!define MUI_FINISHPAGE_RUN_FUNCTION OpenPocket
!define MUI_WELCOMEPAGE_TEXT "将为当前 Windows 用户安装 Codex Pocket。$\r$\n$\r$\n安装前会检查 Codex 桌面、WebView2 与 Visual C++ x64 运行库，并验证现有配对和文件。$\r$\n$\r$\n已有配对和配置会保留。安装后从开始菜单打开唯一的 Codex Pocket 入口，在界面中打开 Codex。$\r$\n$\r$\n若发生错误，“显示详情”会给出阶段和持久诊断文件位置。"
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE CheckWebView
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "SimpChinese"
Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "Codex Pocket 需要 64 位 Windows。"
    SetErrorLevel 1
    Quit
  ${EndIf}
FunctionEnd
Function OpenPocket
  SetShellVarContext current
  Exec '"$LOCALAPPDATA\CodexPocket\pocket-launcher.exe" --pocket-ui'
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
  MessageBox MB_ICONSTOP "无法完成 WebView2 检测，请重试。尚未更改安装或配对。"
  Abort
webview_missing:
  MessageBox MB_YESNO|MB_ICONINFORMATION "检测未找到可用的 WebView2 运行库，Pocket 界面需要此组件。$\r$\n$\r$\n是否打开微软官方下载页？请选择 Evergreen Standalone Installer（x64），安装后返回并再次点击“下一步”。$\r$\n$\r$\n已有配对和安装尚未更改。" IDNO webview_wait
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
  DetailPrint "正在为当前 Windows 用户安装并核验 Pocket…"
  nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\payload\windows-install.ps1" -SourceDirectory "$PLUGINSDIR\payload" -Initialize -Apply'
  Pop $0
  Pop $1
  StrCmp $0 "0" installed
  StrCmp $0 "2" installed_incomplete
  DetailPrint "$1"
  MessageBox MB_ICONSTOP "安装未完成。请展开“显示详情”查看失败阶段、调用与原始错误码。诊断已保存在用户的 CodexPocketDiagnostics\\install 目录中，配对和回滚记录会保留。修复具体原因后可重试本安装包。"
  SetErrorLevel 1
  Abort
installed:
  DetailPrint "安装文件已完成。请从开始菜单打开 Codex Pocket，核验后台生效状态后配置连接。"
  Goto installation_done
installed_incomplete:
  DetailPrint "$1"
  DetailPrint "核心文件已安装并验证，开始菜单或登录启动集成待补全。"
  MessageBox MB_ICONINFORMATION "核心文件已安装并验证，可选集成尚未完成。$\r$\n$\r$\n请展开“显示详情”查看具体阶段与错误码。修复后重新运行本安装包，仅补全未完成步骤。后台生效仍需核验。"
  SetErrorLevel 2
installation_done:
SectionEnd
'@
$script=$script.Replace('__PAYLOAD__',$payload).Replace('__OUTPUT__',$destination)
$temporary=Join-Path $output ('setup-'+[guid]::NewGuid().ToString('N')+'.nsi')
try {
    # NSIS otherwise guesses the active Windows codepage and rejects Chinese text.
    [IO.File]::WriteAllText($temporary,$script,[Text.UTF8Encoding]::new($true))
    & $MakeNsis /V2 $temporary
    if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $setup)){throw 'NSIS Setup compilation failed.'}
    # Recheck the exact input after packaging to detect concurrent release changes.
    Test-PocketWindowsDirectory $source | Out-Null
    [ordered]@{setup=$setup;version=$manifest.version;sha256=(Get-PocketFileHash $setup)} | ConvertTo-Json
} finally { if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary} }
