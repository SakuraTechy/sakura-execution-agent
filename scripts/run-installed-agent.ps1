[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InstallRoot,

    [int]$Port = 19091,

    [string]$JavaCommand = 'java'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
$bootstrapLogPath = Join-Path $InstallRoot 'logs\bootstrap-error.log'

trap {
    $failureMessage = if ($_.Exception.Message) { $_.Exception.Message } else { [string]$_ }
    $diagnostic = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff') Agent 启动失败：$failureMessage"
    try {
        $bootstrapLogDirectory = Split-Path -Parent $bootstrapLogPath
        if (-not (Test-Path -LiteralPath $bootstrapLogDirectory)) {
            New-Item -ItemType Directory -Force $bootstrapLogDirectory | Out-Null
        }
        Add-Content -LiteralPath $bootstrapLogPath -Value $diagnostic -Encoding UTF8
    } catch {
        # 启动日志写入失败时仍将原始中文诊断返回给计划任务。
    }
    Write-Error $diagnostic
    exit 1
}

$installPath = (Resolve-Path -LiteralPath $InstallRoot).Path
$envFilePath = Join-Path $installPath 'conf\agent.env'
$configPath = Join-Path $installPath 'conf\agent-config.yml'
$jarPath = Join-Path $installPath 'sakura-execution-agent.jar'
$logDirectory = Join-Path $installPath 'logs'
$workspacePath = Join-Path $installPath 'workspace'
# 保留 Windows 已有账本位置，避免升级后切换到 YAML 默认 data 路径而丢失任务恢复状态。
$ledgerPath = Join-Path $workspacePath 'task-ledger.json'

# drivers、known_hosts 和 workspace 由 YAML 决定，不能用默认路径的存在性阻止自定义配置启动。
foreach ($requiredPath in @($envFilePath, $configPath, $jarPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Agent 启动文件不存在：$requiredPath"
    }
}
if (-not (Test-Path -LiteralPath $logDirectory)) {
    New-Item -ItemType Directory -Force $logDirectory | Out-Null
}

function Read-AgentEnvToken {
    param([string]$Path)
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^SAKURA_AGENT_TOKEN=(.*)$') {
            if ([string]::IsNullOrWhiteSpace($Matches[1])) {
                throw "环境文件中的 SAKURA_AGENT_TOKEN 为空：$Path"
            }
            return $Matches[1]
        }
    }
    throw "环境文件缺少 SAKURA_AGENT_TOKEN：$Path"
}

# agent.env 同时供 Agent 和 Admin 使用；文件 ACL 限制了可读取的账号。
$env:SAKURA_AGENT_TOKEN = Read-AgentEnvToken -Path $envFilePath

# 只有成功压入目录后才进入 finally，避免切换失败时弹出调用方的目录栈。
Push-Location -LiteralPath $installPath
try {
    # 手工调用和计划任务都以安装目录解析 YAML 相对路径，不能依赖调用者的当前目录。
    & $JavaCommand `
        "-Dsakura.agent.config=$configPath" `
        '-Dsakura.agent.bind=127.0.0.1' `
        "-Dsakura.agent.port=$Port" `
        "-Dsakura.agent.ledger-file=$ledgerPath" `
        -jar $jarPath 2>> $bootstrapLogPath
    $javaExitCode = $LASTEXITCODE
    if ($javaExitCode -ne 0) {
        throw "Agent Java 进程异常退出，退出码=$javaExitCode"
    }
    exit 0
} finally {
    Pop-Location
    Remove-Item Env:SAKURA_AGENT_TOKEN -ErrorAction SilentlyContinue
}
