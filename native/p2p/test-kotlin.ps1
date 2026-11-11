#requires -Version 7.0
param([Parameter(Mandatory)][string]$NativeDir, [string]$RustToolchain='1.93.1', [string]$RustTargetDir)
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
$classpath=@($stdlib,(CachedJar 'org.jetbrains/annotations' '13.0'),
    (CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm' '1.10.2'),
    (CachedJar 'org.jetbrains.kotlinx/kotlinx-serialization-json-jvm' '1.9.0'),
    (CachedJar 'org.jetbrains.kotlinx/kotlinx-serialization-core-jvm' '1.9.0'),
    (CachedJar 'com.squareup.okhttp3/okhttp' '4.12.0'),
    (CachedJar 'com.squareup.okio/okio-jvm' '3.6.0')) -join ';'
# Compile the actual wire sources: copied interface lines missed the writable contract
# and the authenticated relay metadata type after product integration.
$sources=@(Get-ChildItem -LiteralPath (Join-Path $root 'android/wire/src/main/kotlin') -Recurse -File -Filter '*.kt' | ForEach-Object FullName)
$sources+=(Join-Path $PSScriptRoot 'kotlin/DataChannelFixture.kt')
$jar=Join-Path $out 'fixture.jar'
& java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $jar @sources
if($LASTEXITCODE -ne 0){throw 'Kotlin adapter compile failed'}
$classpath=$jar+';'+$classpath
& java "-XX:ErrorFile=$out/hs_err_pid%p.log" "-Djava.library.path=$NativeDir" -cp $classpath dev.codexpocket.wire.DataChannelFixtureKt
if($LASTEXITCODE -ne 0){throw "Kotlin host JNI fixture failed (exit $LASTEXITCODE)"}
$env:P2P_NATIVE_DIR=$NativeDir
$env:P2P_KOTLIN_CLASSPATH=$classpath
$env:PATH=$NativeDir+';'+$env:PATH
if(-not $RustTargetDir){$RustTargetDir=Join-Path $out 'rust-target'}
& cargo "+$RustToolchain" test --locked --offline --manifest-path (Join-Path $root 'crates/p2p/Cargo.toml') --target-dir $RustTargetDir --test mixed_host -- --ignored --nocapture --test-threads=1
if($LASTEXITCODE -ne 0){throw 'Rust/Kotlin host interop failed'}
