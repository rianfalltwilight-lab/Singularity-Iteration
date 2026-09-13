param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$version = '0.13.0-r21-experimental'
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
& $javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp $classes -d $classes @mainSources
if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed' }
& $javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp $classes -d $contracts @contractSources
if ($LASTEXITCODE -ne 0) { throw 'Contract compilation failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.PacketLedgerContract (Join-Path $PSScriptRoot 'fixtures/observed-transfers.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.TreeTopologyContract
if ($LASTEXITCODE -ne 0) { throw 'Tree contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.PacketDistributorContract (Join-Path $PSScriptRoot 'fixtures/observed-branches.tsv') (Join-Path $PSScriptRoot 'fixtures/observed-generator-packets.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Distributor contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.ConductorGraphContract
if ($LASTEXITCODE -ne 0) { throw 'Graph contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.MultiSourceContract (Join-Path $PSScriptRoot 'fixtures/observed-graphs.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Multi-source contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.ConductorRegistryContract
if ($LASTEXITCODE -ne 0) { throw 'Registry contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.UniformPacketEffectsContract (Join-Path $PSScriptRoot 'fixtures/observed-effects.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Effects contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.DeferredEntriesContract (Join-Path $PSScriptRoot 'fixtures/observed-registration-timing.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Deferred publication contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.ReceiverOrderContract (Join-Path $PSScriptRoot 'fixtures/observed-receiver-order.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Receiver order contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.PartialReceiverContract (Join-Path $PSScriptRoot 'fixtures/observed-partial-order.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Partial receiver contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.PriorityDomainContract (Join-Path $PSScriptRoot 'fixtures/observed-source-priority.tsv') (Join-Path $PSScriptRoot 'fixtures/observed-full-receiver-priority.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Priority domain contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.SharedSourceContract (Join-Path $PSScriptRoot 'fixtures/observed-shared-source.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Shared source contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.ContactBudgetContract (Join-Path $PSScriptRoot 'fixtures/observed-two-contact.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Contact budget contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.ComponentRoutesContract
if ($LASTEXITCODE -ne 0) { throw 'Component route contract verification failed' }
& $java -Xms32m -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $contracts) dev.scex.energy.DeliveryTraceContract
if ($LASTEXITCODE -ne 0) { throw 'Delivery trace contract verification failed' }
$manifest = Join-Path $target 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest, "Manifest-Version: 1.0`nImplementation-Version: $version`nSCEX-Integration-Status: Standalone experimental component; not a Minecraft mod`n`n", [Text.UTF8Encoding]::new($false))
$artifact = Join-Path $target "scex-independent-energy-$version.jar"
& $jar --create --file $artifact --date '2026-09-13T00:00:00Z' --manifest $manifest -C $classes . -C $PSScriptRoot LICENSE
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
@{version=$version; java=$compilerVersion; bytes=(Get-Item -LiteralPath $artifact).Length; sha256=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant(); artifact=$artifact} | ConvertTo-Json
