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

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

$jarPath = Join-Path $InstallRoot 'sakura-execution-agent.jar'
$knownHostsPath = Join-Path $InstallRoot 'conf\known_hosts'
$driversPath = Join-Path $InstallRoot 'drivers'
$workspacePath = Join-Path $InstallRoot 'workspace'
$agentLogPath = Join-Path $InstallRoot 'logs\agent.log'
Add-CheckResult 'Agent JAR' (Test-Path -LiteralPath $jarPath -PathType Leaf) $jarPath
Add-CheckResult 'known_hosts' ((Test-Path -LiteralPath $knownHostsPath -PathType Leaf) -and (Get-Item $knownHostsPath -ErrorAction SilentlyContinue).Length -gt 0) $knownHostsPath
Add-CheckResult '驱动目录' (Test-Path -LiteralPath $driversPath -PathType Container) $driversPath
Add-CheckResult 'Agent workspace' (Test-Path -LiteralPath $workspacePath -PathType Container) $workspacePath

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
$isAdministrator = Test-IsAdministrator
if ($task) {
    Add-CheckResult '启动计划任务' $true ([string]$task.State)
} elseif ($isAdministrator) {
    Add-CheckResult '启动计划任务' $false "未注册：$TaskName"
} else {
    # LOCAL SERVICE 计划任务可能对普通账号不可见，健康接口仍会独立验证 Agent 是否可用。
    Add-CheckResult '启动计划任务' $true '当前账号不是管理员，无法确认注册状态；请在管理员 PowerShell 中复核'
}

try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 5
    Add-CheckResult '健康接口' ($health.status -eq 'ok') ($health | ConvertTo-Json -Compress)
    $hasCapabilityContract = $null -ne $health.agent_types -and $null -ne $health.features
    Add-CheckResult '健康能力契约' $hasCapabilityContract 'health 必须返回 agent_types/features；旧版 Agent 需要先升级'
} catch {
    Add-CheckResult '健康接口' $false $_.Exception.Message
    Add-CheckResult '健康能力契约' $false '健康接口不可用，无法验证 agent_types/features'
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
