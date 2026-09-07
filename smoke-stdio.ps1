# Smoke test for the STDIO transport.
#
# Speaks the JSON-RPC an MCP client speaks: initialize, initialized, tools/list, then real
# tools/call requests. This is what proves an mcp.json install works; booting the Spring
# context in a unit test does not exercise the transport at all.
#
# Requests are written to a live process and each response is read back before the next one
# is sent. Piping a file into stdin does not work: stdin reaching EOF shuts the transport
# down before it can flush replies.
#
# Not part of the Maven build. Run it after `mvnw -DskipTests package` when the transport or
# tool wiring changes.

$ErrorActionPreference = 'Stop'

$javaHome = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$classpath = (Get-Content "$PSScriptRoot\cp.txt" -Raw).Trim()
$fullClasspath = "$PSScriptRoot\target\classes;$PSScriptRoot\target\test-classes;$classpath"
$logFile = Join-Path $env:TEMP 'sqlguard-smoke.log'

# H2 standing in for PostgreSQL, seeded by Spring's SQL init from the test fixtures.
$jdbcUrl = 'jdbc:h2:mem:smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'

$javaArgs = @(
    '-cp', $fullClasspath
    # No profile is set on purpose: the default path is the STDIO one, which is exactly what
    # an mcp.json install runs. logback-spring.xml keeps stdout free of log output.
    '-Dspring.main.web-application-type=none'
    '-Dspring.ai.mcp.server.stdio=true'
    "-Dspring.datasource.url=$jdbcUrl"
    '-Dspring.datasource.username=sa'
    '-Dspring.datasource.password='
    '-Dspring.datasource.driver-class-name=org.h2.Driver'
    '-Dspring.datasource.hikari.read-only=false'
    '-Dspring.sql.init.mode=always'
    '-Dspring.sql.init.schema-locations=classpath:schema.sql'
    '-Dspring.sql.init.data-locations=classpath:data.sql'
    '-Dsqlguard.max-rows=3'
    "-DSQLGUARD_LOG_FILE=$logFile"
    'io.github.jagdish1998.sqlguard.SqlGuardApplication'
)

# Windows PowerShell 5.1 has no ProcessStartInfo.ArgumentList, so the command line is built
# by hand. Anything containing a space or a semicolon has to be quoted: the classpath has
# both, and so does the H2 JDBC URL.
function Quote-Arg([string] $value) {
    if ($value -match '[\s;]') { return '"' + $value.Replace('"', '\"') + '"' }
    return $value
}

$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = Join-Path $javaHome 'bin\java.exe'
$startInfo.Arguments = (($javaArgs | ForEach-Object { Quote-Arg $_ }) -join ' ')
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.UseShellExecute = $false
$startInfo.WorkingDirectory = $PSScriptRoot

Write-Host 'Starting sql-guard-mcp over STDIO...' -ForegroundColor Cyan
$process = [System.Diagnostics.Process]::Start($startInfo)

$responses = New-Object System.Collections.Generic.List[string]

function Send-Request([string] $json, [bool] $expectResponse, [int] $timeoutSeconds = 60) {
    $process.StandardInput.WriteLine($json)
    $process.StandardInput.Flush()
    if (-not $expectResponse) {
        return
    }
    $readTask = $process.StandardOutput.ReadLineAsync()
    if (-not $readTask.Wait([TimeSpan]::FromSeconds($timeoutSeconds))) {
        throw "Timed out waiting $timeoutSeconds s for a response to: $json"
    }
    $line = $readTask.Result
    if ($null -ne $line) {
        $responses.Add($line)
    }
}

try {
    # The first call also covers Spring Boot start-up time, so it gets a longer budget.
    Send-Request '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"}}}' $true 120
    Send-Request '{"jsonrpc":"2.0","method":"notifications/initialized"}' $false
    Send-Request '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' $true
    Send-Request '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_tables","arguments":{}}}' $true
    Send-Request '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"run_query","arguments":{"sql":"SELECT id, status FROM orders ORDER BY id"}}}' $true
    Send-Request '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"run_query","arguments":{"sql":"SELECT name, email FROM customers ORDER BY id"}}}' $true
    Send-Request '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"run_query","arguments":{"sql":"DELETE FROM orders"}}}' $true
    Send-Request '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"run_query","arguments":{"sql":"WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone"}}}' $true
}
finally {
    try { $process.StandardInput.Close() } catch { }
    if (-not $process.WaitForExit(15000)) { $process.Kill($true) }
}

$all = [string]::Join("`n", $responses)
$outputFile = Join-Path $env:TEMP 'sqlguard-smoke-out.jsonl'
$all | Set-Content -Path $outputFile -Encoding utf8

$failures = New-Object System.Collections.Generic.List[string]

function Check([string] $label, [bool] $condition) {
    if ($condition) {
        Write-Host "  PASS  $label" -ForegroundColor Green
    }
    else {
        Write-Host "  FAIL  $label" -ForegroundColor Red
        $failures.Add($label)
    }
}

Write-Host "`nChecks:" -ForegroundColor Cyan
Check 'completed the initialize handshake' ($all -match '"serverInfo"')
Check 'advertises the sql-guard server name' ($all -match 'sql-guard')

foreach ($tool in @('list_tables', 'describe_table', 'explain_query', 'run_query', 'recent_activity')) {
    Check "tools/list advertises $tool" ($all -match "`"$tool`"")
}

Check 'tools are marked read-only for clients' ($all -match '"readOnlyHint":true')
Check 'list_tables found the seeded tables' ($all -match 'orders')
Check 'run_query returned real rows' ($all -match 'SHIPPED')
Check 'row cap of 3 was applied' ($all -match 'appliedLimit')
Check 'truncation was reported' ($all -match 'truncated')
Check 'email values were masked' ($all -match 'a\*\*\*@example.com')
Check 'plain DELETE was refused' ($all -match 'NOT_READ_ONLY')
Check 'DELETE hidden in a CTE was refused' ($all -match 'DATA_MODIFYING_CTE')
Check 'refusals came back as error results' ($all -match '"isError":true')

Write-Host ''
Write-Host "Responses: $($responses.Count). Raw output: $outputFile" -ForegroundColor DarkGray
Write-Host "Server log: $logFile" -ForegroundColor DarkGray

if ($failures.Count -gt 0) {
    Write-Host "`n$($failures.Count) check(s) failed." -ForegroundColor Red
    exit 1
}

Write-Host "`nAll STDIO smoke checks passed." -ForegroundColor Green
exit 0
