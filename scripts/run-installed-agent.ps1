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
$tokenPath = Join-Path $installPath 'conf\agent-token.machine'
$jarPath = Join-Path $installPath 'sakura-execution-agent.jar'
$driversPath = Join-Path $installPath 'drivers'
$knownHostsPath = Join-Path $installPath 'conf\known_hosts'
$logDirectory = Join-Path $installPath 'logs'
$logPath = Join-Path $logDirectory 'agent.log'
$workspacePath = Join-Path $installPath 'workspace'

foreach ($requiredPath in @($tokenPath, $jarPath, $driversPath, $knownHostsPath, $workspacePath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Agent 启动文件不存在：$requiredPath"
    }
}
if (-not (Test-Path -LiteralPath $logDirectory)) {
    New-Item -ItemType Directory -Force $logDirectory | Out-Null
}

# Decrypt the machine-scoped DPAPI token. File ACL limits which accounts can read it.
[byte[]]$protectedTokenBytes = New-Object byte[] ([int](Get-Item -LiteralPath $tokenPath).Length)
$tokenStream = [IO.File]::OpenRead($tokenPath)
try {
    $offset = 0
    while ($offset -lt $protectedTokenBytes.Length) {
        $read = $tokenStream.Read($protectedTokenBytes, $offset, $protectedTokenBytes.Length - $offset)
        if ($read -le 0) {
            throw 'Agent DPAPI Token 文件读取不完整'
        }
        $offset += $read
    }
} finally {
    $tokenStream.Dispose()
}
[byte[]]$entropy = [Text.Encoding]::UTF8.GetBytes('sakura-execution-agent-v1')
if ($null -eq $protectedTokenBytes -or $protectedTokenBytes.Length -eq 0) {
    throw 'Agent DPAPI token file is empty.'
}
[byte[]]$tokenBytes = [Security.Cryptography.ProtectedData]::Unprotect([byte[]]$protectedTokenBytes, $entropy, [Security.Cryptography.DataProtectionScope]::LocalMachine)
$env:SAKURA_AGENT_TOKEN = [Text.Encoding]::UTF8.GetString($tokenBytes)
[Array]::Clear($tokenBytes, 0, $tokenBytes.Length)

try {
    & $JavaCommand `
        '-Dsakura.agent.bind=127.0.0.1' `
        "-Dsakura.agent.port=$Port" `
        "-Dsakura.agent.driver-dir=$driversPath" `
        "-Dsakura.agent.known-hosts=$knownHostsPath" `
        "-Dsakura.agent.log-file=$logPath" `
        "-Dsakura.agent.workspace=$workspacePath" `
        -jar $jarPath 2>> $bootstrapLogPath
    $javaExitCode = $LASTEXITCODE
    if ($javaExitCode -ne 0) {
        throw "Agent Java 进程异常退出，退出码=$javaExitCode"
    }
    exit 0
} finally {
    Remove-Item Env:SAKURA_AGENT_TOKEN -ErrorAction SilentlyContinue
}
