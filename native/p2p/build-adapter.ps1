#requires -Version 7.0
[CmdletBinding()]
param([ValidateSet('windows','android-arm64','android-x86_64')][string]$Platform='windows', [string]$BuildDir,
      [switch]$Jni, [switch]$ConfigureOnly, [switch]$TlsFixtures,
      [string]$RustToolchain='1.93.1', [string]$ReportDir,
      [ValidateRange(60,3600)][int]$BuildTimeoutSeconds=1200)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$deps=Join-Path $root '.local/p2p'
if($TlsFixtures -and ($Platform -ne 'windows' -or $ConfigureOnly)){throw 'TLS fixtures require a complete Windows build'}
if(-not $BuildDir){$BuildDir=Join-Path $PSScriptRoot ('.build-'+$Platform+'-'+[Guid]::NewGuid().ToString('N'))}
$BuildDir=[IO.Path]::GetFullPath($BuildDir)
if(-not $ReportDir){$ReportDir=Join-Path $BuildDir 'reports'}
$ReportDir=[IO.Path]::GetFullPath($ReportDir)
[void][IO.Directory]::CreateDirectory($ReportDir)
$steps=[Collections.Generic.List[object]]::new()
$started=[DateTime]::UtcNow; $status='failed'
$fixtureFailures=[Collections.Generic.List[string]]::new()

# Read both pipes concurrently; a deadline terminates the complete process tree.
function Invoke-Checked([string]$Name,[string]$Program,[string[]]$Arguments,[int]$Seconds){
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Program; $info.WorkingDirectory=$root; $info.UseShellExecute=$false
    $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach($argument in $Arguments){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$info
    $clock=[Diagnostics.Stopwatch]::StartNew(); $code=-1; $timedOut=$false; $output=''; $errors=''; $failure=''
    Write-Output "P2P: $Name (deadline ${Seconds}s)"
    try {
        if(-not $process.Start()){throw "Could not start $Program"}
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit($Seconds*1000)){
            $timedOut=$true; $process.Kill($true)
            if(-not $process.WaitForExit(15000)){throw "$Name process tree did not terminate"}
        }
        $output=$stdout.GetAwaiter().GetResult(); $errors=$stderr.GetAwaiter().GetResult(); $code=$process.ExitCode
        if($timedOut -or $code -ne 0){throw "$Name failed (exit=$code, timeout=$timedOut); see $ReportDir/$Name.log"}
        if($Name -eq 'product-tls' -and $output -notmatch 'test result: ok\. [1-9]\d* passed; 0 failed; 0 ignored'){
            throw 'Product TLS fixture did not execute any passing, non-ignored test'
        }
    } catch { $failure=$_.Exception.Message; throw } finally {
        $clock.Stop()
        [IO.File]::WriteAllText((Join-Path $ReportDir "$Name.log"),"Command: $Program $($Arguments -join ' ')`nDeadline: ${Seconds}s`nExit: $code; timedOut: $timedOut`n$output`n$errors")
        $result=[ordered]@{step=$Name;exitCode=$code;timedOut=$timedOut;seconds=[Math]::Round($clock.Elapsed.TotalSeconds,3);failure=$failure}
        if($output -match 'test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored'){
            $result.passed=[int]$Matches[1]; $result.failed=[int]$Matches[2]; $result.ignored=[int]$Matches[3]
        }
        $steps.Add([pscustomobject]$result); $process.Dispose()
    }
}
try {
    $sourceFiles=@('native/p2p/CMakeLists.txt','native/p2p/dependencies.lock.json','native/p2p/prepare.py',
        'native/p2p/build-adapter.ps1','native/p2p/ci-android.sh','native/p2p/probe.cpp','native/p2p/stream_fixture.cpp',
        'android/wire/src/main/cpp/DataChannelJni.cpp','crates/agent/src/p2p.rs','crates/agent/src/session.rs',
        'crates/agent/tests/p2p_product.rs','crates/p2p/Cargo.toml','crates/p2p/Cargo.lock','Cargo.lock',
        'scripts/build-p2p-product.ps1','.github/workflows/checks.yml')
    foreach($directory in @('native/p2p/src','native/p2p/include','crates/p2p/src','crates/p2p/tests','android/wire/src/main/kotlin')){
        $sourceFiles+=Get-ChildItem -LiteralPath (Join-Path $root $directory) -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/') }
    }
    $sourceFiles | Sort-Object -Unique | ForEach-Object {
        $hash=Get-FileHash -LiteralPath (Join-Path $root $_) -Algorithm SHA256
        "$($hash.Hash.ToLowerInvariant())  $_"
    } | Set-Content -LiteralPath (Join-Path $ReportDir 'sources.sha256') -Encoding utf8NoBOM
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'dependencies.lock.json') -Destination (Join-Path $ReportDir 'dependencies.lock.json') -Force
    Invoke-Checked 'prepare' (Get-Command python -ErrorAction Stop).Source @((Join-Path $PSScriptRoot 'prepare.py')) 300
    if($Platform -eq 'windows'){
        $vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
        $install=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if($LASTEXITCODE -ne 0 -or -not $install){throw 'MSVC required'}
        $cmake=Join-Path $install 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
        if($Jni -and -not $env:JAVA_HOME){$env:JAVA_HOME=Split-Path (Split-Path (Get-Command java).Source)}
        $configure=@('-S',$PSScriptRoot,'-B',$BuildDir,'-G','Visual Studio 17 2022','-A','x64',"-DPOCKET_P2P_DEPS=$deps","-DPOCKET_P2P_JNI=$($Jni.IsPresent)")
    }else{
        $sdk=if($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}elseif($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
        $cmake=Join-Path $sdk 'cmake/3.22.1/bin/cmake.exe'
        $ninja=Join-Path (Split-Path $cmake) 'ninja.exe'
        $toolchain=Join-Path $sdk 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'
        $abi=if($Platform -eq 'android-x86_64'){'x86_64'}else{'arm64-v8a'}
        $configure=@('-S',$PSScriptRoot,'-B',$BuildDir,'-G','Ninja',"-DCMAKE_TOOLCHAIN_FILE=$toolchain","-DANDROID_ABI=$abi",'-DANDROID_PLATFORM=android-26','-DCMAKE_BUILD_TYPE=Release',"-DCMAKE_MAKE_PROGRAM=$ninja","-DPOCKET_P2P_DEPS=$deps")
    }
    Invoke-Checked 'configure' $cmake $configure 300
    if($ConfigureOnly){$status='configured'; return}
    $targets=@('pocket-p2p')
    if($Platform -eq 'windows'){$targets+=@('pocket-p2p-probe','pocket-p2p-stream-fixture')}
    if($Jni -or $Platform -like 'android-*'){$targets+='pocket-p2p-jni'}
    Invoke-Checked 'build' $cmake (@('--build',$BuildDir,'--config','Release','--target')+$targets+@('--parallel','4')) $BuildTimeoutSeconds
    if($Platform -eq 'windows'){
        try { Invoke-Checked 'stream-fixture' (Join-Path $BuildDir 'Release/pocket-p2p-stream-fixture.exe') @() 90 }
        catch { $fixtureFailures.Add($_.Exception.Message); Write-Warning $_.Exception.Message }
        try { Invoke-Checked 'loopback-probe' (Join-Path $BuildDir 'Release/pocket-p2p-probe.exe') @() 90 }
        catch { $fixtureFailures.Add($_.Exception.Message); Write-Warning $_.Exception.Message }
        if($TlsFixtures){
            $previousNative=$env:P2P_NATIVE_DIR
            try {
                $env:P2P_NATIVE_DIR=Join-Path $BuildDir 'Release'
                $cargo=(Get-Command cargo -ErrorAction Stop).Source
                $cargoTarget=Join-Path $BuildDir 'rust-target'
                # This product fixture uses the maintained workspace lock and the real pinned TLS/device hello.
                $product=@("+$RustToolchain",'test','--locked','-p','pocket-agent','--target-dir',$cargoTarget,'--test','p2p_product')
                try {
                    Invoke-Checked 'product-tls-build' $cargo ($product+@('--no-run')) $BuildTimeoutSeconds
                    Invoke-Checked 'product-tls' $cargo ($product+@('--','--ignored','--nocapture','--test-threads=1')) 120
                } catch { $fixtureFailures.Add($_.Exception.Message); Write-Warning $_.Exception.Message }
            } finally { $env:P2P_NATIVE_DIR=$previousNative }
        }
    }
    if($fixtureFailures.Count){throw ("$($fixtureFailures.Count) P2P fixture(s) failed: " + ($fixtureFailures -join '; '))}
    $status='passed'
    Write-Output "Adapter build: $BuildDir"
} finally {
    $binaries=@()
    $binaryDirs=if($Platform -eq 'windows'){@((Join-Path $BuildDir 'Release'))}else{@($BuildDir,(Join-Path $BuildDir 'libdatachannel'))}
    foreach($directory in $binaryDirs){
        if(Test-Path -LiteralPath $directory){
            $binaries+=Get-ChildItem -LiteralPath $directory -File | Where-Object Extension -In '.dll','.exe','.so' | ForEach-Object {
                [pscustomobject]@{path=[IO.Path]::GetRelativePath($BuildDir,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
            }
        }
    }
    [ordered]@{platform=$Platform;status=$status;startedUtc=$started.ToString('o');finishedUtc=[DateTime]::UtcNow.ToString('o');tlsFixtures=$TlsFixtures.IsPresent;steps=@($steps.ToArray());binaries=$binaries} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $ReportDir 'report.json') -Encoding utf8NoBOM
    $lines=@("# P2P $Platform",'',"Result: $status. TLS fixtures requested: $($TlsFixtures.IsPresent).",'',
        'Sources: sources.sha256; locked upstream commits and declared tree digests: dependencies.lock.json.',
        'Windows fixtures use local peers and temporary identity/state. Android builds do not run on a device. No model calls.',
        '', '| Step | Exit | Timeout | Seconds | Passed / failed / ignored |','|---|---:|---|---:|---|')
    foreach($step in $steps){
        $counts=if($step.PSObject.Properties['passed']){"$($step.passed) / $($step.failed) / $($step.ignored)"}else{'-'}
        $lines+="| $($step.step) | $($step.exitCode) | $($step.timedOut) | $($step.seconds) | $counts |"
    }
    $lines | Set-Content -LiteralPath (Join-Path $ReportDir 'report.md') -Encoding utf8NoBOM
}
