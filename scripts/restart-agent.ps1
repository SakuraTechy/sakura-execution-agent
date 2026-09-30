[CmdletBinding()]
param(
    [string]$InstallRoot = 'C:\ProgramData\Sakura\execution-agent',

    [int]$Port = 19091,

    [string]$TaskName = 'SakuraExecutionAgent',

    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = 'Stop'
$installPath = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$stopScript = Join-Path $scriptDirectory 'stop-agent.ps1'

if (-not (Test-Path -LiteralPath $stopScript -PathType Leaf)) {
    throw "停止脚本不存在：$stopScript"
}
if (-not (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
    throw "Agent 计划任务不存在：$TaskName；请先执行安装脚本。"
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw 'Agent 端口必须在 1-65535 之间。'
}

& $stopScript -InstallRoot $installPath -Port $Port -TaskName $TaskName -TimeoutSeconds $TimeoutSeconds
Start-ScheduledTask -TaskName $TaskName

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
        if ($health.status -eq 'ok') {
            Write-Host "Sakura Execution Agent 重启成功：InstallRoot=$installPath，Port=$Port"
            exit 0
        }
    } catch {
        # 启动阶段端口尚未监听，继续轮询直到超时。
    }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)

throw "Agent 重启后未在 $TimeoutSeconds 秒内通过健康检查：InstallRoot=$installPath，Port=$Port"
