[CmdletBinding()]
param(
    [string]$InstallRoot = 'C:\ProgramData\Sakura\execution-agent',

    [int]$Port = 19091,

    [string]$TaskName = 'SakuraExecutionAgent'
)

$ErrorActionPreference = 'Stop'
$checks = [System.Collections.Generic.List[object]]::new()

function Add-CheckResult {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $checks.Add([pscustomobject]@{ Name = $Name; Passed = $Passed; Detail = $Detail })
}

$jarPath = Join-Path $InstallRoot 'sakura-execution-agent.jar'
$knownHostsPath = Join-Path $InstallRoot 'conf\known_hosts'
$driversPath = Join-Path $InstallRoot 'drivers'
$agentLogPath = Join-Path $InstallRoot 'logs\agent.log'
Add-CheckResult 'Agent JAR' (Test-Path -LiteralPath $jarPath -PathType Leaf) $jarPath
Add-CheckResult 'known_hosts' ((Test-Path -LiteralPath $knownHostsPath -PathType Leaf) -and (Get-Item $knownHostsPath -ErrorAction SilentlyContinue).Length -gt 0) $knownHostsPath
Add-CheckResult '驱动目录' (Test-Path -LiteralPath $driversPath -PathType Container) $driversPath

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Add-CheckResult '启动计划任务' ($null -ne $task) ($(if ($task) { [string]$task.State } else { '未注册' }))

try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 5
    Add-CheckResult '健康接口' ($health.status -eq 'ok') ($health | ConvertTo-Json -Compress)
} catch {
    Add-CheckResult '健康接口' $false $_.Exception.Message
}
Add-CheckResult 'Agent 诊断日志' (Test-Path -LiteralPath $agentLogPath -PathType Leaf) $agentLogPath

try {
    Invoke-WebRequest `
        -Uri "http://127.0.0.1:$Port/v1/tasks/not-exist" `
        -Headers @{ Authorization = 'Bearer invalid-token' } `
        -UseBasicParsing `
        -TimeoutSec 5 | Out-Null
    Add-CheckResult '错误 Token 拒绝' $false '错误 Token 未被拒绝'
} catch {
    $statusCode = [int]$_.Exception.Response.StatusCode
    Add-CheckResult '错误 Token 拒绝' ($statusCode -eq 401) "HTTP $statusCode"
}

$checks | Format-Table -AutoSize
if (@($checks | Where-Object { -not $_.Passed }).Count -gt 0) {
    throw 'Agent 验收未通过，请根据上表排查。'
}

Write-Host 'Agent 本机部署验收通过。'
