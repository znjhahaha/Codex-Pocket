#requires -Version 7.0
[CmdletBinding()]
param([ValidateSet('windows','android-arm64','android-x86_64')][string]$Platform='windows', [string]$BuildDir,
      [switch]$Jni, [switch]$ConfigureOnly)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$deps=Join-Path $root '.local/p2p'
foreach($package in (Get-Content (Join-Path $PSScriptRoot 'dependencies.lock.json') -Raw | ConvertFrom-Json).packages){
    $path=Join-Path $deps $package.path
    $commit=& git -C $path rev-parse HEAD
    if($LASTEXITCODE -ne 0 -or $commit -cne $package.commit){throw "Pinned revision mismatch: $($package.path)"}
    $changes=& git -C $path status --porcelain --untracked-files=no
    if($LASTEXITCODE -ne 0 -or $changes){throw "Dependency modified: $($package.path)"}
}
if(-not $BuildDir){$BuildDir=Join-Path $PSScriptRoot ('.build-'+$Platform+'-'+[Guid]::NewGuid().ToString('N'))}
$BuildDir=[IO.Path]::GetFullPath($BuildDir)
if($Platform -eq 'windows'){
    $vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    $install=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if(-not $install){throw 'MSVC required'}
    $cmake=Join-Path $install 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
    if($Jni -and -not $env:JAVA_HOME){$env:JAVA_HOME=Split-Path (Split-Path (Get-Command java).Source)}
    & $cmake -S $PSScriptRoot -B $BuildDir -G 'Visual Studio 17 2022' -A x64 "-DPOCKET_P2P_DEPS=$deps" "-DPOCKET_P2P_JNI=$($Jni.IsPresent)"
}else{
    $sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    $cmake=Join-Path $sdk 'cmake/3.22.1/bin/cmake.exe'
    $ninja=Join-Path (Split-Path $cmake) 'ninja.exe'
    $toolchain=Join-Path $sdk 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'
    $abi=if($Platform -eq 'android-x86_64'){'x86_64'}else{'arm64-v8a'}
    & $cmake -S $PSScriptRoot -B $BuildDir -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release "-DCMAKE_MAKE_PROGRAM=$ninja" "-DPOCKET_P2P_DEPS=$deps"
}
if($LASTEXITCODE -ne 0){throw 'Adapter configure failed'}
if($ConfigureOnly){return}
$targets=@('pocket-p2p')
if($Platform -eq 'windows'){$targets+=@('pocket-p2p-probe','pocket-p2p-stream-fixture')}
if($Jni -or $Platform -like 'android-*'){$targets+='pocket-p2p-jni'}
& $cmake --build $BuildDir --config Release --target @targets --parallel 4
if($LASTEXITCODE -ne 0){throw 'Adapter build failed'}
if($Platform -eq 'windows'){
    & (Join-Path $BuildDir 'Release/pocket-p2p-stream-fixture.exe')
    if($LASTEXITCODE -ne 0){throw 'Stream fixture failed'}
    & (Join-Path $BuildDir 'Release/pocket-p2p-probe.exe')
    if($LASTEXITCODE -ne 0){throw 'Original probe failed'}
}
Write-Output "Adapter build: $BuildDir"
