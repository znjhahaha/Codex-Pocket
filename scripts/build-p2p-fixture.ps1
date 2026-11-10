#requires -Version 7.0
[CmdletBinding()]
param([ValidateSet('windows','android-arm64')][string]$Platform='windows',[string]$CMakePath,[string]$SdkPath)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$deps=Join-Path $root '.local/p2p'
$lock=Get-Content -LiteralPath (Join-Path $root 'native/p2p/dependencies.lock.json') -Raw | ConvertFrom-Json
foreach($package in $lock.packages){
    $path=Join-Path $deps $package.path
    if(-not(Test-Path -LiteralPath $path)){throw "Fetch the pinned sources documented in native/p2p/README.md: $($package.path)"}
    $commit=& git -C $path rev-parse HEAD
    if($LASTEXITCODE -ne 0 -or $commit -cne $package.commit){throw "Pinned revision mismatch: $($package.path)"}
    $changes=& git -C $path status --porcelain --untracked-files=no
    if($LASTEXITCODE -ne 0 -or $changes){throw "Dependency sources changed: $($package.path)"}
}
$build=Join-Path $deps ('build-'+$Platform)
if($Platform -eq 'windows'){
    if(-not $CMakePath){
        $vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
        $install=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if($LASTEXITCODE -ne 0 -or -not $install){throw 'MSVC Build Tools are required.'}
        $CMakePath=Join-Path $install 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
    }
    & $CMakePath -S (Join-Path $root 'native/p2p') -B $build -G 'Visual Studio 17 2022' -A x64 "-DPOCKET_P2P_DEPS=$deps"
    if($LASTEXITCODE -ne 0){throw 'P2P configure failed'}
    & $CMakePath --build $build --config Release --target pocket-p2p-probe --parallel 4
    if($LASTEXITCODE -ne 0){throw 'P2P build failed'}
    $result=& (Join-Path $build 'Release/pocket-p2p-probe.exe')
    if($LASTEXITCODE -ne 0){throw 'Local DataChannel fixture failed'}
    $result | Set-Content -LiteralPath (Join-Path $build 'probe-result.json') -Encoding utf8
    $result
}else{
    if(-not $SdkPath){$SdkPath=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
    if(-not $CMakePath){$CMakePath=Join-Path $SdkPath 'cmake/3.22.1/bin/cmake.exe'}
    $ninja=Join-Path (Split-Path $CMakePath) 'ninja.exe'
    $toolchain=Join-Path $SdkPath 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'
    & $CMakePath -S (Join-Path $root 'native/p2p') -B $build -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release "-DCMAKE_MAKE_PROGRAM=$ninja" "-DPOCKET_P2P_DEPS=$deps"
    if($LASTEXITCODE -ne 0){throw 'Android P2P configure failed'}
    & $CMakePath --build $build --target datachannel --parallel 4
    if($LASTEXITCODE -ne 0){throw 'Android P2P build failed'}
}
