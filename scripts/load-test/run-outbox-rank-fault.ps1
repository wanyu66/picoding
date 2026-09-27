param([string]$Java8Home = "$PSScriptRoot/../../.runtime/java8")
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location -LiteralPath $repoRoot
$env:JAVA_HOME = (Resolve-Path $Java8Home).Path
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
New-Item -ItemType Directory -Force "$repoRoot/.runtime/outbox-tmp" | Out-Null
$env:TEMP = "$repoRoot/.runtime/outbox-tmp"
$env:TMP = $env:TEMP
$version = & "$env:JAVA_HOME/bin/java" -version 2>&1 | Out-String
if ($version -notmatch '1\.8\.0') { throw 'This test requires Java 8.' }
try {
    docker compose -p paicoding-outbox-rank-fault -f scripts/load-test/outbox-rank-compose.yml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Fault-test containers failed to start.' }
    & mvn -pl paicoding-service -am '-Dtest=OutboxRankFaultTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Doutbox.rank.fault=true' test *> .runtime/outbox-rank-fault.log
    $testExit = $LASTEXITCODE
    Get-Content .runtime/outbox-rank-fault.log -Tail 45
} finally {
    docker compose -p paicoding-outbox-rank-fault -f scripts/load-test/outbox-rank-compose.yml stop
}
Write-Output "Evidence: $repoRoot/paicoding-service/target/outbox-rank-fault"
exit $testExit
