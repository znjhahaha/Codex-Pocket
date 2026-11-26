#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ReleaseDirectory,[Parameter(Mandatory)][string]$OutputDirectory,[string]$MakeNsis,
    [string]$UpdateOfferPath,[string]$TrustedUpdatePublicKey,[string]$VpkDll,[string]$SetupExecutable,[switch]$LegacyNsis)
$ErrorActionPreference='Stop'
if(!$LegacyNsis){
    if(!$VpkDll -or !$SetupExecutable){throw 'Velopack packaging requires -VpkDll and -SetupExecutable. Use -LegacyNsis only for an explicit legacy compatibility build.'}
    if(!$UpdateOfferPath -or !$TrustedUpdatePublicKey){throw 'Velopack packaging requires a signed offer and independently pinned public key.'}
    foreach($path in @($VpkDll,$SetupExecutable,$UpdateOfferPath)) {
        if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Velopack packaging input is missing.'}
    }
    & (Join-Path $PSScriptRoot 'build-windows-velopack.ps1') -ReleaseDirectory $ReleaseDirectory -OutputDirectory $OutputDirectory -VpkDll $VpkDll -SetupExecutable $SetupExecutable -UpdateOfferPath $UpdateOfferPath -TrustedUpdatePublicKey $TrustedUpdatePublicKey
    return
}
. (Join-Path $PSScriptRoot 'windows-common.ps1')
$release=Get-PocketDirectoryPath $ReleaseDirectory
$source=Join-Path $release 'windows'
$manifest=Test-PocketWindowsDirectory $source
$manifestHash=Get-PocketFileHash (Join-Path $source 'windows-manifest.json')
$setupEntry=Join-Path $PSScriptRoot 'windows-setup-entry.ps1'
$entryHash=Get-PocketFileHash $setupEntry
if([bool]$UpdateOfferPath -ne [bool]$TrustedUpdatePublicKey){throw 'Supply UpdateOfferPath and independently pinned TrustedUpdatePublicKey together'}
$offerHash=$null
if($UpdateOfferPath){
    $UpdateOfferPath=[IO.Path]::GetFullPath($UpdateOfferPath);Assert-PocketPlainPath $UpdateOfferPath
    if(([Convert]::FromBase64String($TrustedUpdatePublicKey)).Length -ne 32){throw 'Invalid pinned Ed25519 public key'}
    $verifyArgs=@('--verify-local-source','--source',('"'+$source+'"'),'--offer',('"'+$UpdateOfferPath+'"'),'--trusted-key',$TrustedUpdatePublicKey)
    $verification=Start-Process -FilePath (Join-Path $source 'pocket-upgrade.exe') -ArgumentList $verifyArgs -WindowStyle Hidden -Wait -PassThru
    if($verification.ExitCode -ne 0){throw 'Signed Setup source verification failed'}
    $offerHash=Get-PocketFileHash $UpdateOfferPath
}
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
$entry=Quote-Nsis $setupEntry
$signedFiles=if($UpdateOfferPath){'File /oname=windows-update.json "'+(Quote-Nsis $UpdateOfferPath)+'"'}else{''}
$signedArgs=if($UpdateOfferPath){'-UpdateOfferPath "$PLUGINSDIR\windows-update.json" -TrustedUpdatePublicKey "'+$TrustedUpdatePublicKey+'"'}else{''}
$script=@'
Unicode true
RequestExecutionLevel user
SetCompressor /SOLID lzma
Name "Codex Pocket"
InstallDir "$LOCALAPPDATA\CodexPocket"
OutFile "__OUTPUT__"
!include "MUI2.nsh"
!include "x64.nsh"
Var PocketFinishTitle
!define MUI_FINISHPAGE_TITLE "$PocketFinishTitle"
!define MUI_FINISHPAGE_TEXT "请以“显示详情”中的最终结果为准。签名更新排队成功后，Pocket 会在任务空闲、进程身份和启动屏障均可核验时切换并恢复桌面；候选未通过就绪核验时尝试回滚。旧版缺少屏障时会明确延后。配对、历史和未确认操作记录会保留。"
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "打开 Pocket"
!define MUI_FINISHPAGE_RUN_FUNCTION OpenPocket
!define MUI_WELCOMEPAGE_TEXT "将为当前 Windows 用户安装 Codex Pocket。$\r$\n$\r$\n已有安装通过本安装包固定的公钥验证签名后排队更新。正在运行、等待回答或结果未确认的任务不会被关闭。支持安全启动屏障的新版桌面会等待空闲后自动重启并核验；旧版 0.6.6 缺少屏障时会延后，不能自动关闭。$\r$\n$\r$\n首次安装会检查必要组件。已有配对、历史与配置会保留。“显示详情”将区分已安装、已排队和需要恢复。"
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE CheckWebView
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "SimpChinese"
Function .onInit
  StrCpy $PocketFinishTitle "安装流程已结束"
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "Codex Pocket 需要 64 位 Windows。"
    SetErrorLevel 1
    Quit
  ${EndIf}
FunctionEnd
Function OpenPocket
  SetShellVarContext current
  Exec '"$INSTDIR\pocket-launcher.exe" --pocket-ui'
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
  SetOutPath "$PLUGINSDIR"
  File /oname=windows-setup-entry.ps1 "__ENTRY__"
  __SIGNED_FILES__
  SetOutPath "$PLUGINSDIR\payload"
  File /r "__PAYLOAD__\*"
  DetailPrint "正在为当前 Windows 用户安装并核验 Pocket…"
  nsExec::ExecToStack /TIMEOUT=180000 '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\windows-setup-entry.ps1" -SourceDirectory "$PLUGINSDIR\payload" -InstallRoot "$INSTDIR" __SIGNED_ARGS__'
  Pop $0
  Pop $1
  StrCmp $0 "0" installed
  StrCmp $0 "2" installed_incomplete
  StrCmp $0 "4" install_deferred
  StrCmp $0 "10" update_queued
  DetailPrint "$1"
  MessageBox MB_ICONSTOP "安装未完成。请展开“显示详情”查看失败阶段、调用与原始错误码。诊断已保存在用户的 CodexPocketDiagnostics\\install 目录中，配对和回滚记录会保留。修复具体原因后可重试本安装包。"
  SetErrorLevel 1
  Abort
install_deferred:
  DetailPrint "$1"
  MessageBox MB_ICONINFORMATION "更新已延后，现有进程和安装版本保持原状。$\r$\n$\r$\n请完成当前任务和待处理问题。优先使用签名后台更新；需要完整安装时，关闭原生会话并暂停远程连接后再重试。“显示详情”中保留了延后原因。"
  SetErrorLevel 4
  Abort
installed:
  DetailPrint "$1"
  DetailPrint "安装完成，后台已自动重启并通过新代次与本机认证核验。"
  Goto installation_done
update_queued:
  StrCpy $PocketFinishTitle "更新已排队，尚未完成"
  DetailPrint "$1"
  DetailPrint "本地签名已验证，更新已排队。请在 Pocket 更新页查看等待空闲、切换或恢复状态。排队成功不代表新版本已运行。"
  Goto installation_done
installed_incomplete:
  DetailPrint "$1"
  DetailPrint "核心文件已安装并验证，开始菜单或登录启动集成待补全。"
  MessageBox MB_ICONINFORMATION "核心文件与后台已核验，可选集成尚未完成。$\r$\n$\r$\n请展开“显示详情”查看具体阶段与错误码。修复后重新运行本安装包重试未完成步骤。若存在待恢复事务，必须先恢复其已知结果。"
  SetErrorLevel 2
installation_done:
SectionEnd
'@
$script=$script.Replace('__PAYLOAD__',$payload).Replace('__OUTPUT__',$destination).Replace('__ENTRY__',$entry).Replace('__SIGNED_FILES__',$signedFiles).Replace('__SIGNED_ARGS__',$signedArgs)
$temporary=Join-Path $output ('setup-'+[guid]::NewGuid().ToString('N')+'.nsi')
try {
    # NSIS otherwise guesses the active Windows codepage and rejects Chinese text.
    [IO.File]::WriteAllText($temporary,$script,[Text.UTF8Encoding]::new($true))
    & $MakeNsis /V2 $temporary
    if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $setup)){throw 'NSIS Setup compilation failed.'}
    # Recheck the exact input after packaging to detect concurrent release changes.
    Test-PocketWindowsDirectory $source | Out-Null
    if((Get-PocketFileHash (Join-Path $source 'windows-manifest.json')) -ne $manifestHash){throw 'Exact candidate manifest changed during Setup packaging'}
    if((Get-PocketFileHash $setupEntry) -ne $entryHash){throw 'Trusted Setup entry changed during packaging'}
    if($UpdateOfferPath -and (Get-PocketFileHash $UpdateOfferPath) -ne $offerHash){throw 'Signed offer changed during Setup packaging'}
    [ordered]@{setup=$setup;version=$manifest.version;sha256=(Get-PocketFileHash $setup);manifestSha256=$manifestHash;setupEntrySha256=$entryHash;signedOfferSha256=$offerHash} | ConvertTo-Json
} finally { if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary} }
