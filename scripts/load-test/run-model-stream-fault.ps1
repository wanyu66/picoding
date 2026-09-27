param(
    [string]$Java8Home = "$PSScriptRoot/../../.runtime/java8",
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location -LiteralPath $repoRoot
$env:JAVA_HOME = (Resolve-Path $Java8Home).Path
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
New-Item -ItemType Directory -Force .runtime/model-tmp | Out-Null
$env:TEMP = "$repoRoot/.runtime/model-tmp"
$env:TMP = $env:TEMP
$version = & "$env:JAVA_HOME/bin/java" -version 2>&1 | Out-String
if ($version -notmatch '1\.8\.0') { throw 'Java 8 is required.' }
$testExit = 1
try {
    docker compose -p paicoding-outbox-rank-fault -f scripts/load-test/outbox-rank-compose.yml up -d --wait mysql redis
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated services.' }
    if (!$SkipBuild) {
        & mvn -pl paicoding-web -am '-DskipTests' test-compile *> .runtime/model-compile.log
        if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; see .runtime/model-compile.log' }
        & mvn -pl paicoding-web '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/model-test-classpath.txt' dependency:build-classpath *> .runtime/model-classpath.log
        if ($LASTEXITCODE -ne 0) { throw 'Classpath resolution failed.' }
    }
    # Manifest classpath avoids the Windows command-line length limit. Current reactor classes take precedence.
    $entries = @('paicoding-web/target/test-classes','paicoding-web/target/classes','paicoding-service/target/classes','paicoding-core/target/classes','paicoding-api/target/classes')
    $entries += (Get-Content paicoding-web/target/model-test-classpath.txt -Raw).Trim().Split(';')
    $urls = foreach ($entry in $entries) {
        $full = (Resolve-Path -LiteralPath $entry).Path
        if (Test-Path -LiteralPath $full -PathType Container) { $full += '/' }
        ([System.Uri]$full).AbsoluteUri
    }
    $line = 'Class-Path: ' + ($urls -join ' ')
    $manifest = "Manifest-Version: 1.0`r`n"
    while ($line.Length -gt 70) { $manifest += $line.Substring(0,70) + "`r`n"; $line = ' ' + $line.Substring(70) }
    $manifest += $line + "`r`n`r`n"
    Add-Type -AssemblyName System.IO.Compression
    $stream = [System.IO.File]::Open("$repoRoot/.runtime/model-classpath.jar",[System.IO.FileMode]::Create)
    $zip = [System.IO.Compression.ZipArchive]::new($stream,[System.IO.Compression.ZipArchiveMode]::Create)
    $entryStream = $zip.CreateEntry('META-INF/MANIFEST.MF').Open()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($manifest)
    $entryStream.Write($bytes,0,$bytes.Length)
    $entryStream.Dispose(); $zip.Dispose(); $stream.Dispose()
    & "$env:JAVA_HOME/bin/java" '-Dmodel.stream.fault=true' '-Dfile.encoding=UTF-8' -cp .runtime/model-classpath.jar com.github.paicoding.forum.test.chat.ModelStreamFaultHarness *> .runtime/model-stream-fault.log
    $testExit = $LASTEXITCODE
    Get-Content .runtime/model-stream-fault.log -Tail 3
} finally {
    docker compose -p paicoding-outbox-rank-fault -f scripts/load-test/outbox-rank-compose.yml stop mysql redis
}
Write-Output 'Evidence: paicoding-web/target/model-stream-fault/results.json'
exit $testExit
