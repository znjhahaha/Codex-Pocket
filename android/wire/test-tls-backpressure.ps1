#requires -Version 7.0
param([string]$OutputDir, [string[]]$Tests = @('dev.codexpocket.wire.TlsBackpressureTest'))
$ErrorActionPreference = 'Stop'
$Tests = @($Tests | ForEach-Object { $_ -split ',' })
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$OutputDir) { $OutputDir = Join-Path $root ('.local/tls-backpressure-tests/run-' + [guid]::NewGuid().ToString('N')) }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function CachedJar([string]$Package, [string]$Version) {
    $base = Join-Path (Join-Path $cache $Package) $Version
    $jar = Get-ChildItem -LiteralPath $base -Recurse -File -Filter '*.jar' | Select-Object -First 1
    if (!$jar) { throw "Missing cached jar: $Package $Version" }
    $jar.FullName
}
$stdlib = CachedJar 'org.jetbrains.kotlin/kotlin-stdlib' '2.2.21'
$annotations = CachedJar 'org.jetbrains/annotations' '13.0'
$coroutines = CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm' '1.10.2'
$compiler = @((CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable' '2.2.21'), $stdlib,
    (CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime' '2.2.21'),
    (CachedJar 'org.jetbrains.kotlin/kotlin-reflect' '2.2.21'), $coroutines, $annotations) -join ';'
$classpath = @($stdlib, $annotations, $coroutines,
    (CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm' '1.10.2'),
    (CachedJar 'org.jetbrains.kotlinx/kotlinx-serialization-json-jvm' '1.9.0'),
    (CachedJar 'org.jetbrains.kotlinx/kotlinx-serialization-core-jvm' '1.9.0'),
    (CachedJar 'com.squareup.okhttp3/okhttp' '4.12.0'),
    (CachedJar 'com.squareup.okhttp3/okhttp-tls' '4.12.0'),
    (CachedJar 'com.squareup.okhttp3/mockwebserver' '4.12.0'),
    (CachedJar 'com.squareup.okio/okio-jvm' '3.6.0'),
    (CachedJar 'junit/junit' '4.13.2'), (CachedJar 'org.hamcrest/hamcrest-core' '1.3')) -join ';'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/kotlin'), (Join-Path $PSScriptRoot 'src/test/kotlin') -Recurse -File -Filter '*.kt' | ForEach-Object FullName)
$jar = Join-Path $OutputDir 'wire-tests.jar'
& java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $jar @sources 2>&1 | Tee-Object -FilePath (Join-Path $OutputDir 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "Wire compile failed: $OutputDir" }
& java -cp ($jar + ';' + $classpath) org.junit.runner.JUnitCore @Tests 2>&1 | Tee-Object -FilePath (Join-Path $OutputDir 'junit.log')
$testExit = $LASTEXITCODE
Write-Output "Evidence: $OutputDir (JUnit exit $testExit)"
if ($testExit -ne 0) { throw 'Wire tests failed' }
