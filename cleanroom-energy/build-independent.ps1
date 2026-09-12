param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$target = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $target) { throw 'Use a fresh output directory for a reproducible build' }
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path (Get-Command javac).Source }
$javac = Join-Path $jdk 'javac.exe'
$java = Join-Path $jdk 'java.exe'
$jar = Join-Path $jdk 'jar.exe'
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch '^javac 21\.') { throw 'JDK 21 is required' }
$classes = Join-Path $target 'classes'
$contracts = Join-Path $target 'contracts'
New-Item -ItemType Directory -Path $classes, $contracts | Out-Null
$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java') -Filter '*.java' -Recurse | Sort-Object FullName | ForEach-Object FullName)
$contractSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/contract/java') -Filter '*.java' -Recurse | Sort-Object FullName | ForEach-Object FullName)
if ($mainSources.Count -eq 0 -or $contractSources.Count -eq 0) { throw 'Missing real sources or contracts' }
& $javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d $classes @mainSources
if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed' }
& $javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp $classes -d $contracts @contractSources
if ($LASTEXITCODE -ne 0) { throw 'Contract compilation failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.PacketLedgerContract (Join-Path $PSScriptRoot 'fixtures/observed-transfers.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Contract verification failed' }
$manifest = Join-Path $target 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest, "Manifest-Version: 1.0`nImplementation-Version: 0.1.0-r6-experimental`nSCEX-Integration-Status: Standalone experimental component; not a Minecraft mod`n`n", [Text.UTF8Encoding]::new($false))
$artifact = Join-Path $target 'scex-independent-energy-0.1.0-r6-experimental.jar'
& $jar --create --file $artifact --date '2026-09-12T00:00:00Z' --manifest $manifest -C $classes . -C $PSScriptRoot LICENSE
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
@{version='0.1.0-r6-experimental'; java=$compilerVersion; bytes=(Get-Item -LiteralPath $artifact).Length; sha256=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant(); artifact=$artifact} | ConvertTo-Json
