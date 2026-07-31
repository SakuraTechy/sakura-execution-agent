[CmdletBinding()]
param(
    [string]$InstallRoot = 'C:\ProgramData\Sakura\execution-agent',

    [int]$Port = 19091,

    [string]$TaskName = 'SakuraExecutionAgent',

    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 10
)

$ErrorActionPreference = 'Stop'
$installPath = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
$jarPath = Join-Path $installPath 'sakura-execution-agent.jar'
$runnerPath = Join-Path $installPath 'run-agent.ps1'

function Get-AgentProcesses {
    $result = [System.Collections.Generic.List[object]]::new()
    $processes = Get-CimInstance Win32_Process -ErrorAction Stop
    foreach ($processInfo in $processes) {
        $commandLine = [string]$processInfo.CommandLine
        if ([string]::IsNullOrWhiteSpace($commandLine)) {
            continue
        }
        $matchesJar = $commandLine.IndexOf($jarPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
        $matchesRunner = $commandLine.IndexOf($runnerPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
        if ($matchesJar -or $matchesRunner) {
            $result.Add([pscustomobject]@{
                ProcessId = [int]$processInfo.ProcessId
                Name = [string]$processInfo.Name
                Match = $(if ($matchesJar) { 'agent-jar' } else { 'run-script' })
            })
        }
    }
    return @($result | Sort-Object @{ Expression = { $_.Match -ne 'agent-jar' } }, ProcessId)
}

function Get-PortListeners {
    return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

$scheduledTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($scheduledTask -and $scheduledTask.State -ne 'Ready' -and $scheduledTask.State -ne 'Disabled') {
    Write-Host "正在停止计划任务：$TaskName"
    Stop-ScheduledTask -TaskName $TaskName
}

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $agentProcesses = @(Get-AgentProcesses)
    if ($agentProcesses.Count -eq 0) {
        break
    }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)

$agentProcesses = @(Get-AgentProcesses)
foreach ($agentProcess in $agentProcesses) {
    Write-Host "正在停止 Agent 进程：PID=$($agentProcess.ProcessId)，类型=$($agentProcess.Match)"
    # Java 子进程退出后，父 PowerShell 可能同步结束；最终状态由下方重新扫描统一确认。
    Stop-Process -Id $agentProcess.ProcessId -Force -ErrorAction SilentlyContinue
}

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $remainingProcesses = @(Get-AgentProcesses)
    if ($remainingProcesses.Count -eq 0) {
        break
    }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)

$remainingProcesses = @(Get-AgentProcesses)
if ($remainingProcesses.Count -gt 0) {
    throw "Agent 进程未在 $TimeoutSeconds 秒内停止：$($remainingProcesses.ProcessId -join ',')"
}

$listeners = @(Get-PortListeners)
if ($listeners.Count -gt 0) {
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    throw "Agent 已停止，但端口 $Port 仍被其他进程占用，PID=$($owners -join ',')；为避免误停，脚本未处理这些进程。"
}

Write-Host "Sakura Execution Agent 已停止：InstallRoot=$installPath，Port=$Port"
