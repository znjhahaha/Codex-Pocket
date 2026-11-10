#requires -Version 7.0
param([Parameter(Mandatory)][string]$NativeDir)
$ErrorActionPreference='Stop'
$NativeDir=[IO.Path]::GetFullPath($NativeDir)
$env:PATH=$NativeDir+';'+$env:PATH
$out=Join-Path (Split-Path $NativeDir) 'kotlin'
New-Item -ItemType Directory -Force $out | Out-Null
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function CachedJar([string]$package,[string]$version='') {
    $path=Join-Path $cache $package
    if($version){$path=Join-Path $path $version}
    $jar=Get-ChildItem -LiteralPath $path -Recurse -File -Filter '*.jar' | Select-Object -First 1
    if(-not $jar){throw "Missing cached compiler jar: $package $version"}
    $jar.FullName
}
$stdlib=CachedJar 'org.jetbrains.kotlin/kotlin-stdlib' '2.2.21'
$compiler=@((CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable' '2.2.21'),$stdlib,
    (CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime' '2.2.21'),
    (CachedJar 'org.jetbrains.kotlin/kotlin-reflect' '2.2.21'),
    (CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm'),
    (CachedJar 'org.jetbrains/annotations')) -join ';'
# Extract the actual BinaryTransport/TransportListener declarations without compiling unrelated
# production code or invoking Gradle. The only stand-in is their source file's other dependencies.
$outer=Get-Content (Join-Path $root 'android/wire/src/main/kotlin/dev/codexpocket/wire/OuterTransport.kt')
$contracts=@('package dev.codexpocket.wire')+@($outer | Where-Object {$_ -match '^internal interface (BinaryTransport|TransportListener) '})
if($contracts.Count -ne 3){throw 'Wire transport declarations changed; inspect fixture compatibility'}
$contracts | Set-Content -LiteralPath (Join-Path $out 'Contracts.kt') -Encoding utf8
$jar=Join-Path $out 'fixture.jar'
& java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $stdlib -d $jar `
    (Join-Path $out 'Contracts.kt') (Join-Path $PSScriptRoot 'kotlin/DataChannelFixture.kt') `
    (Join-Path $root 'android/wire/src/main/kotlin/dev/codexpocket/wire/DataChannelTransport.kt')
if($LASTEXITCODE -ne 0){throw 'Kotlin adapter compile failed'}
$classpath=$jar+';'+$stdlib
& java "-XX:ErrorFile=$out/hs_err_pid%p.log" "-Djava.library.path=$NativeDir" -cp $classpath dev.codexpocket.wire.DataChannelFixtureKt
if($LASTEXITCODE -ne 0){throw "Kotlin host JNI fixture failed (exit $LASTEXITCODE)"}
$env:P2P_NATIVE_DIR=$NativeDir
$env:P2P_KOTLIN_CLASSPATH=$classpath
$env:PATH=$NativeDir+';'+$env:PATH
& cargo test --offline --manifest-path (Join-Path $root 'crates/p2p/Cargo.toml') --target-dir (Join-Path $root 'crates/p2p/target-step1') --test mixed_host -- --ignored
if($LASTEXITCODE -ne 0){throw 'Rust/Kotlin host interop failed'}
