#requires -Version 7.0
[CmdletBinding()]
param([switch]$WindowsOnly)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& python (Join-Path $root 'native/p2p/prepare.py')
if($LASTEXITCODE -ne 0){throw 'Pinned P2P source verification failed'}
& (Join-Path $root 'native/p2p/build-adapter.ps1') -Platform windows -BuildDir (Join-Path $root '.local/p2p/product-windows')
if($LASTEXITCODE -ne 0){throw 'Windows P2P build failed'}
if($WindowsOnly){return}
$sdk=if($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}elseif($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$strip=Join-Path $sdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
if(-not [IO.File]::Exists($strip)){throw 'Pinned Android NDK llvm-strip is required'}
foreach($entry in @(@('android-arm64','arm64-v8a'),@('android-x86_64','x86_64'))){
    $build=Join-Path $root ('.local/p2p/product-'+$entry[0])
    & (Join-Path $root 'native/p2p/build-adapter.ps1') -Platform $entry[0] -BuildDir $build
    if($LASTEXITCODE -ne 0){throw 'Android P2P build failed'}
    $destination=Join-Path $root ('.local/p2p/product-libs/'+$entry[1])
    [void][IO.Directory]::CreateDirectory($destination)
    foreach($library in @('libpocket-p2p.so','libpocket-p2p-jni.so','libdatachannel.so')){
        $source=if($library -eq 'libdatachannel.so'){Join-Path $build ('libdatachannel/'+$library)}else{Join-Path $build $library}
        Copy-Item -LiteralPath $source -Destination (Join-Path $destination $library) -Force
        & $strip --strip-unneeded (Join-Path $destination $library)
        if($LASTEXITCODE -ne 0){throw ('Android library stripping failed: '+$library)}
    }
}
