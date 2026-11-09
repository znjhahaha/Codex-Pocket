#requires -Version 5.1
# Shared file operations. Dot-sourcing this file performs no integration actions.
function Get-PocketBytesHash([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Get-PocketFullPath([string]$Path) {
    # Containment checks use ordinary Win32 paths. Device/extended/NT namespace
    # aliases can name the same directory with a different root, so refuse them
    # before filesystem access. Check both input and normalization, including
    # forward/mixed separators, instead of attempting to interpret device paths.
    $namespacePattern = '^(?:\\{2,}[?.]\\|\\{1,2}\?\?\\)'
    if ($Path.Replace('/','\') -match $namespacePattern) { throw 'Unsupported Windows path namespace; use an ordinary Win32 path.' }
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.Replace('/','\') -match $namespacePattern) { throw 'Unsupported Windows path namespace; use an ordinary Win32 path.' }
    return $full
}
function Get-PocketDirectoryPath([string]$Path) {
    $full = Get-PocketFullPath $Path
    if ($full -eq [IO.Path]::GetPathRoot($full)) { throw 'Use a dedicated directory, not a filesystem root.' }
    return $full.TrimEnd('\','/')
}
function Assert-PocketLocalPath([string]$Path) {
    if($Path -match '^[A-Za-z]:.*:') { throw 'Alternate data streams are refused.' }
    $full=Get-PocketFullPath $Path
    if ($full -notmatch '^[A-Za-z]:[\\/]') { throw 'Native installation requires an ordinary local drive path.' }
    if ($full.Substring(2).Contains(':')) { throw 'Alternate data streams are refused.' }
    Assert-PocketPlainPath $full
}
function Get-PocketPhysicalPath([string]$Path) {
    Assert-PocketLocalPath $Path
    if (-not ('PocketPhysicalPath' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
public static class PocketPhysicalPath {
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
    static extern SafeFileHandle CreateFile(string path,uint access,uint share,IntPtr security,uint creation,uint flags,IntPtr template);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
    static extern uint GetFinalPathNameByHandle(SafeFileHandle handle,StringBuilder path,uint size,uint flags);
    public static string Resolve(string path) {
        using(var handle=CreateFile(path,0,7,IntPtr.Zero,3,0x02000000,IntPtr.Zero)) {
            if(handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            var buffer=new StringBuilder(32768);
            uint length=GetFinalPathNameByHandle(handle,buffer,(uint)buffer.Capacity,0);
            if(length==0) throw new Win32Exception(Marshal.GetLastWin32Error());
            if(length>=buffer.Capacity) throw new InvalidOperationException("Physical path is too long.");
            string result=buffer.ToString();
            return result.StartsWith(@"\\?\") ? result.Substring(4) : result;
        }
    }
}
'@
    }
    return [PocketPhysicalPath]::Resolve((Get-PocketFullPath $Path))
}
function Assert-PocketUnredirectedPath([string]$Path,[switch]$ProbeWrite) {
    # A shell may inherit filesystem virtualization while GetCurrentPackageFullName
    # reports APPMODEL_ERROR_NO_PACKAGE. Verify handles, never just package identity.
    $expected=Get-PocketFullPath $Path
    Assert-PocketLocalPath $expected
    while(-not (Test-Path -LiteralPath $expected)) {
        $expected=[IO.Path]::GetDirectoryName($expected)
        if(-not $expected){throw 'Cannot resolve an existing installation ancestor.'}
    }
    $physical=Get-PocketPhysicalPath $expected
    $message='Pocket installation path is redirected by a packaged process. Open standalone Windows PowerShell from the Windows Start menu (not a Codex terminal), then rerun this command. Preserve the physical LocalCache state and pass it explicitly with -ExistingStateDirectory when migrating.'
    if(-not $physical.Equals($expected,[StringComparison]::OrdinalIgnoreCase)){throw $message}
    if(-not $ProbeWrite){return}
    $directory=if([IO.Directory]::Exists($expected)){$expected}else{[IO.Path]::GetDirectoryName($expected)}
    $probe=Join-Path $directory ('.pocket-path-probe-'+[guid]::NewGuid().ToString('N')+'.tmp')
    $stream=$null
    try {
        $stream=[IO.File]::Open($probe,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
        $physical=Get-PocketPhysicalPath $probe
        if(-not $physical.Equals($probe,[StringComparison]::OrdinalIgnoreCase)){throw $message}
    } finally {
        if($null -ne $stream){$stream.Dispose();[IO.File]::Delete($probe)}
    }
}
function Assert-PocketPlainPath([string]$Path) {
    $cursor = Get-PocketFullPath $Path
    while ($cursor) {
        $item = Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'Refusing a reparse-point path.'
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
}
function Get-PocketFileHash([string]$Path) {
    Assert-PocketPlainPath $Path
    if ([IO.File]::Exists($Path)) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
    if (Test-Path -LiteralPath $Path) { throw 'Expected a regular file.' }
    return $null
}
function Write-PocketAtomic([string]$Path, [byte[]]$Bytes) {
    Assert-PocketPlainPath $Path
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        $deadline=[DateTime]::UtcNow.AddSeconds(2)
        while ($true) {
            try {
                if ([IO.File]::Exists($Path)) { [IO.File]::Replace($temporary, $Path, [NullString]::Value) }
                else { [IO.File]::Move($temporary, $Path) }
                break
            } catch {
                $cause=$_.Exception
                while ($null -ne $cause.InnerException) { $cause=$cause.InnerException }
                # Readers and Explorer can briefly deny replacement. Keep both
                # complete files and retry the same atomic operation, never truncate.
                $code=$cause.HResult -band 65535
                if ($code -notin @(5,32,33,1175,1176,1177) -or [DateTime]::UtcNow -ge $deadline -or -not [IO.File]::Exists($temporary)) { throw }
                Start-Sleep -Milliseconds 40
                Assert-PocketPlainPath $Path
            }
        }
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
}
function Write-PocketJson([string]$Path, $Value) {
    Write-PocketAtomic $Path ([Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 20)))
}
function Move-PocketStagedDirectory([string]$Source, [string]$Destination) {
    $Source = Get-PocketDirectoryPath $Source
    $Destination = Get-PocketDirectoryPath $Destination
    if ([IO.Path]::GetDirectoryName($Source) -ne [IO.Path]::GetDirectoryName($Destination) -or
        (-not $Source.StartsWith($Destination + '.staging-',[StringComparison]::OrdinalIgnoreCase) -and
         -not $Source.StartsWith($Destination + '.migration-',[StringComparison]::OrdinalIgnoreCase))) {
        throw 'Invalid staged directory publication paths.'
    }
    for ($attempt=0; $attempt -le 5; $attempt++) {
        Assert-PocketPlainPath $Source
        Assert-PocketPlainPath $Destination
        if (Test-Path -LiteralPath $Destination) { throw 'Publication destination already exists; staging retained.' }
        try { [IO.Directory]::Move($Source,$Destination); return }
        catch {
            $cause = $_.Exception
            while ($cause.InnerException) { $cause = $cause.InnerException }
            # A reader without FILE_SHARE_DELETE can briefly block a Windows
            # directory rename. Only access/sharing/lock errors get bounded retries.
            if ($attempt -eq 5 -or ($cause.HResult -band 65535) -notin @(5,32,33)) { throw }
            Start-Sleep -Milliseconds (100 * [math]::Pow(2,$attempt))
        }
    }
}
function ConvertTo-PocketArgument([string]$Value) {
    # Windows CommandLineToArgvW/CRT quoting, including a trailing backslash.
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}
function Get-PocketWindowsFiles {
    return @('cloudflared.exe','cloudflared-LICENSE.txt','pocket-agent.exe','pocket-bridge.exe','pocket-desktop.exe','pocket-supervisor.exe','pocket-launcher.exe','windows-startup.ps1','windows-common.ps1','windows-install.ps1',
        'windows-launch-desktop.ps1','windows-tray.ps1','windows-verify-release.ps1',
        'cli-install.ps1','cli-wrapper.mjs','windows-user-guide.md','cli-install-implementation.md',
        'resume-windows-report.md','cli-native-probe-result.md','V02-WINDOWS-INSTALL.md')
}
function Get-PocketInventory([string]$Root, [string[]]$Names) {
    return @($Names | Sort-Object | ForEach-Object {
        $path = Join-Path $Root $_
        $hash = Get-PocketFileHash $path
        if (-not $hash -or (Get-Item -LiteralPath $path).Length -eq 0) { throw ('Missing or empty delivery file: ' + $_) }
        [ordered]@{path=$_.Replace('\','/');sha256=$hash;bytes=(Get-Item -LiteralPath $path).Length}
    })
}
function Test-PocketInventory([string]$Root, $Manifest, [string[]]$ExpectedNames, [string]$ManifestName) {
    Assert-PocketPlainPath $Root
    if ($Manifest.schema -ne 1 -or $Manifest.version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Unsupported release manifest.' }
    $seen = @{}
    foreach ($entry in $Manifest.files) {
        $name = [string]$entry.path
        if ($name -notmatch '^[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*$' -or $name.Contains('..') -or $seen.ContainsKey($name)) { throw 'Invalid or duplicate inventory path.' }
        if ($name -notin $ExpectedNames) { throw 'Unexpected inventory path.' }
        $seen[$name] = $true
        $path = Join-Path $Root $name
        if ($entry.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-PocketFileHash $path) -ne $entry.sha256 -or (Get-Item -LiteralPath $path).Length -ne $entry.bytes) {
            throw ('Delivery integrity/hash check failed: ' + $name)
        }
    }
    if ($seen.Count -ne $ExpectedNames.Count) { throw 'Incomplete delivery inventory.' }
    # Enumerate one level at a time so a junction is rejected before recursion.
    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue([IO.Path]::GetFullPath($Root))
    $prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Dequeue() -Force) {
            Assert-PocketPlainPath $item.FullName
            if ($item.PSIsContainer) { $pending.Enqueue($item.FullName); continue }
            $relative = $item.FullName.Substring($prefix.Length).Replace('\','/')
            if ($relative -ne $ManifestName -and -not $seen.ContainsKey($relative)) { throw ('Unlisted delivery inventory file: ' + $relative) }
        }
    }
}
function Test-PocketWindowsDirectory([string]$Directory) {
    $path = Join-Path $Directory 'windows-manifest.json'
    Assert-PocketPlainPath $path
    $manifest = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    Test-PocketInventory $Directory $manifest (Get-PocketWindowsFiles) 'windows-manifest.json'
    return $manifest
}
function Test-PocketReleaseDirectory([string]$Directory) {
    $path = Join-Path $Directory 'release-manifest.json'
    Assert-PocketPlainPath $path
    $manifest = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Invalid release version.' }
    $names = @((Get-PocketWindowsFiles) | ForEach-Object { 'windows/' + $_ }) + @('windows/windows-manifest.json',('codex-pocket-' + $manifest.version + '.apk'),('codex-pocket-' + $manifest.version + '-windows.zip'))
    Test-PocketInventory $Directory $manifest $names 'release-manifest.json'
    $windows = Test-PocketWindowsDirectory (Join-Path $Directory 'windows')
    if ($windows.version -ne $manifest.version) { throw 'Release version mismatch.' }
    return $manifest
}
function Send-PocketEnvironmentChanged {
    if (-not ('PocketEnvironmentBroadcast' -as [type])) {
        Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class PocketEnvironmentBroadcast { [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr h,uint m,UIntPtr w,string l,uint f,uint t,out UIntPtr r); }'
    }
    $result = [UIntPtr]::Zero
    [void][PocketEnvironmentBroadcast]::SendMessageTimeout([IntPtr]0xffff,0x1A,[UIntPtr]::Zero,'Environment',2,2000,[ref]$result)
}
function Get-PocketDesktopPackage {
    $packages = @(Get-AppxPackage -Name OpenAI.Codex)
    if ($packages.Count -ne 1) { throw 'Expected one installed original Codex desktop package.' }
    return $packages[0]
}
function Test-PocketWebView2 {
    param([scriptblock]$ReadVersion={param($Path) (Get-ItemProperty -LiteralPath $Path -Name pv -ErrorAction Stop).pv})
    # Microsoft's documented Evergreen runtime identity; browser product names are not evidence.
    foreach($path in @(
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',
        'HKCU:\Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',
        'HKLM:\SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}'
    )) {
        try { $version=[string](& $ReadVersion $path); if($version -match '^\d+\.\d+\.\d+\.\d+$' -and [version]$version -gt [version]'0.0.0.0'){return $true} } catch { }
    }
    return $false
}
function Assert-PocketWindowsPrerequisites {
    if (-not [Environment]::Is64BitProcess) { throw 'Use 64-bit PowerShell for the Windows x64 delivery.' }
    if (-not [IO.File]::Exists((Join-Path $env:WINDIR 'System32\vcruntime140.dll'))) {
        throw 'Microsoft Visual C++ x64 runtime (VCRUNTIME140.dll) is required before installation.'
    }
    if (-not (Test-PocketWebView2)) { throw 'Microsoft Edge WebView2 Evergreen runtime is required. Open https://developer.microsoft.com/microsoft-edge/webview2/ to install Evergreen x64, then retry Setup.' }

}

function Get-PocketLocalAppData {
    if (-not ('PocketKnownFolders' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class PocketKnownFolders {
    [DllImport("shell32.dll")] static extern int SHGetKnownFolderPath(ref Guid id,uint flags,IntPtr token,out IntPtr path);
    public static string Local() {
        Guid id=new Guid("F1B32785-6FBA-4FCF-9D55-7B8E7F157091"); IntPtr p;
        int hr=SHGetKnownFolderPath(ref id,0x00010000,IntPtr.Zero,out p);
        Marshal.ThrowExceptionForHR(hr);
        try { return Marshal.PtrToStringUni(p); } finally { Marshal.FreeCoTaskMem(p); }
    }
}
'@
    }
    $path=[PocketKnownFolders]::Local()
    Assert-PocketLocalPath $path
    return $path
}
function Get-PocketUserCliPath {
    $key=[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment',$false)
    if ($null -eq $key) { return $null }
    try { return $key.GetValue('CODEX_CLI_PATH',$null,[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) }
    finally { $key.Dispose() }
}
function Get-PocketUserCliKind {
    $key=[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment',$false)
    if ($null -eq $key) { return 'String' }
    try {
        if ('CODEX_CLI_PATH' -notin $key.GetValueNames()) { return 'String' }
        $kind=$key.GetValueKind('CODEX_CLI_PATH').ToString()
        if ($kind -notin @('String','ExpandString')) { throw 'CODEX_CLI_PATH must be a string registry value.' }
        return $kind
    } finally { $key.Dispose() }
}
function Set-PocketUserCliPath([AllowNull()]$Value,[ValidateSet('String','ExpandString')][string]$Kind='String') {
    $key=if($null -eq $Value){[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment',$true)}else{[Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')}
    if($null -eq $key){return}
    try {
        if($null -eq $Value){$key.DeleteValue('CODEX_CLI_PATH',$false)}
        else{$key.SetValue('CODEX_CLI_PATH',[string]$Value,[Microsoft.Win32.RegistryValueKind]::$Kind)}
    } finally { $key.Dispose() }
}
