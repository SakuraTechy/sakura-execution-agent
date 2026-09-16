[CmdletBinding()]
param(
    [string]$ShellPath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe",
    [string]$JavaCommand = 'java',
    [string]$KnownHostsPath = ''
)

$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$jar = (Resolve-Path (Join-Path $sourceRoot 'target\sakura-execution-agent-0.1.0-SNAPSHOT.jar')).Path
$java = (Get-Command $JavaCommand -ErrorAction Stop).Source
if ([string]::IsNullOrWhiteSpace($KnownHostsPath)) {
    $KnownHostsPath = Join-Path $sourceRoot 'conf\known_hosts'
}
$knownHosts = (Resolve-Path -LiteralPath $KnownHostsPath).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testRoot = Join-Path $tempRoot ('sakura config test ' + [guid]::NewGuid().ToString('N'))
$testTaskName = 'SakuraConfigTest-' + [guid]::NewGuid().ToString('N')
$runner = $null
$utf8 = [Text.UTF8Encoding]::new($false)

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Invoke-Plan {
    param([string]$InstallRoot, [string[]]$ExtraArguments = @())
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = (& $ShellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $sourceRoot 'scripts\install-agent.ps1') `
            -InstallRoot $InstallRoot -AgentJarPath $jar -KnownHostsPath $knownHosts -JavaCommand $java `
            -TaskName $testTaskName -Port $port -SkipTaskRegistration -PlanOnly @ExtraArguments 2>&1 |
            ForEach-Object { $_.ToString() } | Out-String)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

try {
    # 仅在随机临时目录和空闲回环端口测试，不安装服务、不操作现有计划任务，也不连接 SSH 主机。
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()

    $newRoot = Join-Path $testRoot 'not installed'
    $result = Invoke-Plan -InstallRoot $newRoot
    Assert-That ($result.ExitCode -eq 0) $result.Output
    Assert-That ($result.Output.Contains('sshSkipHostKeyCheck=false')) '默认 YAML 未生效'
    Assert-That (-not $result.Output.Contains('NativeCommandError')) 'Java 正常 stderr 被格式化成了错误'
    Assert-That (-not (Test-Path -LiteralPath $newRoot)) 'PlanOnly 创建了安装目录'

    $installed = Join-Path $testRoot 'installed agent'
    New-Item -ItemType Directory -Path (Join-Path $installed 'conf') | Out-Null
    $configPath = Join-Path $installed 'conf\agent-config.yml'
    $envPath = Join-Path $installed 'conf\agent.env'
    [IO.File]::WriteAllText($configPath, "ssh:`n  skip-host-key-check: true`n", $utf8)
    # 非法 Token 文件可以证明计划预检没有依赖读取或重新生成 Token。
    [IO.File]::WriteAllText($envPath, 'PLAN_MUST_NOT_READ_OR_REPLACE_THIS_FILE', $utf8)
    $configHash = (Get-FileHash -LiteralPath $configPath).Hash
    $envHash = (Get-FileHash -LiteralPath $envPath).Hash
    $result = Invoke-Plan -InstallRoot $installed
    Assert-That ($result.ExitCode -eq 0) $result.Output
    Assert-That ($result.Output.Contains('sshSkipHostKeyCheck=true')) '重装未保留现场 YAML'
    Assert-That ((Get-FileHash -LiteralPath $configPath).Hash -eq $configHash) 'PlanOnly 修改了现场 YAML'
    Assert-That ((Get-FileHash -LiteralPath $envPath).Hash -eq $envHash) 'PlanOnly 修改了 Token 文件'

    $result = Invoke-Plan -InstallRoot $installed -ExtraArguments @('-AgentConfigPath', (Join-Path $sourceRoot 'conf\agent-config.yml'))
    Assert-That ($result.ExitCode -eq 0) $result.Output
    Assert-That ($result.Output.Contains('sshSkipHostKeyCheck=false')) '显式 YAML 替换计划未生效'
    Assert-That ((Get-FileHash -LiteralPath $configPath).Hash -eq $configHash) '显式替换计划提前修改了 YAML'

    [IO.File]::WriteAllText($configPath, "ssh:`n  skip-host-key-chek: false`n", $utf8)
    $result = Invoke-Plan -InstallRoot $installed
    Assert-That ($result.ExitCode -ne 0) '拼错的安全开关被安装预检接受'
    Assert-That ((Get-FileHash -LiteralPath $envPath).Hash -eq $envHash) '失败的预检修改了 Token'
    Write-Host 'PASS: PlanOnly 首装、保留配置、显式替换、非法配置和无写入检查'

    $yaml = @"
ssh:
  skip-host-key-check: false
  known-hosts: keys/trusted_hosts
jdbc:
  driver-dir: custom-drivers
local-action:
  workspace: custom-workspace
logging:
  file: custom-logs/agent.log
"@
    [IO.File]::WriteAllText($configPath, $yaml, $utf8)
    [IO.File]::WriteAllText($envPath, ('SAKURA_AGENT_TOKEN=' + [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')), $utf8)
    $fixtureJar = Join-Path $installed 'sakura-execution-agent.jar'
    Copy-Item -LiteralPath $jar -Destination $fixtureJar
    New-Item -ItemType Directory -Path (Join-Path $installed 'keys') | Out-Null
    Copy-Item -LiteralPath $knownHosts -Destination (Join-Path $installed 'keys\trusted_hosts')
    $stdoutPath = Join-Path $testRoot 'runner.stdout.log'
    $stderrPath = Join-Path $testRoot 'runner.stderr.log'
    $runnerArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        ('"' + (Join-Path $sourceRoot 'scripts\run-installed-agent.ps1') + '"'),
        '-InstallRoot', ('"' + $installed + '"'), '-Port', $port, '-JavaCommand', ('"' + $java + '"'))
    $runner = Start-Process -FilePath $ShellPath -ArgumentList $runnerArguments -WorkingDirectory $sourceRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $logPath = Join-Path $installed 'custom-logs\agent.log'
    $healthy = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($runner.HasExited) {
            throw "测试 Agent 提前退出：$(Get-Content -LiteralPath $stderrPath -Raw)"
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
            if ($health.status -eq 'ok' -and (Test-Path -LiteralPath $logPath)) {
                $log = Get-Content -LiteralPath $logPath -Raw
                if ($log.Contains('event=AGENT_STARTED')) { $healthy = $true; break }
            }
        } catch {
            # HTTP 开始监听可能早于启动日志落盘，两者都就绪后再断言。
        }
        Start-Sleep -Milliseconds 250
    }
    Assert-That $healthy '测试 Agent 未通过健康检查'
    Assert-That ($log.Contains('sshSkipHostKeyCheck=false')) '启动日志未体现 YAML 强校验配置'
    Assert-That ($log.Contains('configLoaded=true')) '启动时未加载安装目录 YAML'
    Assert-That ($log.Contains('configFile=' + $configPath)) '加载了调用目录的配置'
    Assert-That ($log.Contains('knownHosts=' + (Join-Path $installed 'keys\trusted_hosts'))) 'known_hosts 相对路径解析错误'
    Assert-That ($log.Contains('driverDirectory=' + (Join-Path $installed 'custom-drivers'))) '驱动相对路径解析错误'
    Assert-That (Test-Path -LiteralPath (Join-Path $installed 'custom-workspace') -PathType Container) '自定义 workspace 未创建'
    Assert-That (-not (Test-Path -LiteralPath (Join-Path $installed 'conf\known_hosts'))) '测试未覆盖默认 known_hosts 缺失的场景'
    Write-Host 'PASS: 真实 JAR 启动、含空格安装路径、异目录调用、自定义路径和 SSH 配置加载'
} finally {
    if ($null -ne $runner) {
        # 仅停止本测试启动且命令行指向临时 JAR 的 Java 子进程，绝不按端口或全局进程名停止 Agent。
        Get-CimInstance Win32_Process -Filter "ParentProcessId = $($runner.Id)" | Where-Object {
            $_.Name -eq 'java.exe' -and $_.CommandLine -and $_.CommandLine.Contains($fixtureJar)
        } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        if (-not $runner.WaitForExit(5000)) { $runner.Kill() }
        $runner.Dispose()
    }
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if (-not $resolvedTestRoot.StartsWith($tempRoot + '\sakura config test ', [StringComparison]::OrdinalIgnoreCase)) {
        throw '拒绝清理临时测试目录之外的路径'
    }
    if (Test-Path -LiteralPath $resolvedTestRoot) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
