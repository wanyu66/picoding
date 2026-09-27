param([Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
# Read existing local settings without placing credentials in arguments or output.
$configText = Get-Content -Raw paicoding-web/src/main/resources-env/dev/application-dal.yml
$dbUser = [regex]::Match($configText, '(?m)^\s+username:\s*(.+?)\s*$').Groups[1].Value.Trim().Trim('"', "'")
$dbPassword = [regex]::Match($configText, '(?m)^\s+password:\s*(.*?)\s*$').Groups[1].Value.Trim().Trim('"', "'")
if ($dbPassword -match '^\$\{([^:}]+)(?::(.*))?\}$') {
    $variableName = $Matches[1]
    $defaultPassword = $Matches[2]
    $configuredPassword = [Environment]::GetEnvironmentVariable($variableName)
    if ($null -eq $configuredPassword -and (Test-Path -LiteralPath .env)) {
        $envMatch = [regex]::Match((Get-Content -Raw .env), '(?m)^' + [regex]::Escape($variableName) + '=(.*)$')
        if ($envMatch.Success) { $configuredPassword = $envMatch.Groups[1].Value.Trim().Trim('"', "'") }
    }
    $dbPassword = if ($null -ne $configuredPassword) { $configuredPassword } else { $defaultPassword }
}
if ([string]::IsNullOrEmpty($dbPassword)) { throw 'Database password is not available in this terminal. Supply the environment variable referenced by application-dal.yml.' }
$oldPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $dbPassword
    $sql = "SELECT NOW() captured_at; SELECT document_id,cnt FROM read_count WHERE document_type=1 ORDER BY document_id; SELECT state,COUNT(*) events FROM article_read_outbox GROUP BY state; SELECT event_id,article_id,state,trace_id FROM article_read_outbox ORDER BY create_time,event_id LIMIT 20;"
    $result = & mysql --host=127.0.0.1 --port=3306 --user=$dbUser --database=pai_coding --batch --execute=$sql
    if ($LASTEXITCODE -ne 0) { throw 'Read-count snapshot failed' }
    $result | Set-Content -Encoding utf8 $OutputPath
    $result
} finally {
    $env:MYSQL_PWD = $oldPassword
}
