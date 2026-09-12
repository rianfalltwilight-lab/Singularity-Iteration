param([Parameter(Mandatory)][string]$OutputDirectory,[Parameter(Mandatory)][string]$ClasspathFile)
$ErrorActionPreference='Stop'
$target=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $target){throw 'Use a fresh output directory'}
$jdk=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin'}else{Split-Path (Get-Command javac).Source}
$javac=Join-Path $jdk 'javac.exe'
$compilerVersion=(& $javac -version 2>&1 | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch '^javac 21\.'){throw 'JDK 21 required'}
$inputs=Get-Content -LiteralPath $ClasspathFile -Raw -Encoding UTF8 | ConvertFrom-Json
$paths=@($inputs.entries | ForEach-Object {
    if(!(Test-Path -LiteralPath $_.path -PathType Leaf)){throw 'Missing frozen classpath file'}
    if((Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash -ine $_.sha256){throw 'Changed classpath input'}
    [IO.Path]::GetFullPath($_.path)
})
if($paths.Count -eq 0){throw 'Empty public platform classpath'}
$classes=Join-Path $target 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java') -Filter '*.java' -Recurse | Sort-Object FullName | ForEach-Object FullName)
if($sources.Count -eq 0){throw 'Missing independent sources'}
& $javac '-J-Duser.language=en' '-J-Duser.country=US' --release 21 -g -proc:none -encoding UTF-8 -Xlint:all -Werror -cp ($paths -join [IO.Path]::PathSeparator) -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Independent platform compilation failed'}
$version='0.3.0-r13-experimental'
$manifest=Join-Path $target 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest,"Manifest-Version: 1.0`nImplementation-Version: $version`nSCEX-Integration-Status: Experimental platform library; no mod entrypoint`n`n",[Text.UTF8Encoding]::new($false))
$artifact=Join-Path $target "scex-independent-minecraft-$version.jar"
& (Join-Path $jdk 'jar.exe') --create --file $artifact --date '2026-09-13T00:00:00Z' --manifest $manifest -C $classes . -C $PSScriptRoot LICENSE
if($LASTEXITCODE -ne 0){throw 'Independent library packaging failed'}
@{version=$version;java=$compilerVersion;artifact=$artifact;bytes=(Get-Item -LiteralPath $artifact).Length;sha256=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()} | ConvertTo-Json
