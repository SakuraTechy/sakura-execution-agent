[CmdletBinding()]
param(
    [string]$InstallRoot = 'C:\ProgramData\Sakura\execution-agent',

    [string]$AgentJarPath = '',

    [string]$KnownHostsPath = '',

    [ValidateSet(
        'mysql', 'oracle', 'sqlserver', 'postgresql', 'greenplum', 'gaussdb', 'sybase', 'hive',
        'tidb', 'oceanbase', 'teradata', 'mariadb', 'kingbase', 'iris', 'informix', 'db2', 'cache',
        'gbase8a', 'gbase8s', 'tdengine', 'phoenix', 'dm'
    )]
    [string[]]$Profiles = @(),

    [int]$Port = 19091,

    [string]$JavaCommand = 'java',

    [string]$TaskName = 'SakuraExecutionAgent',

    [switch]$RotateToken,

    [switch]$SkipTaskRegistration,

    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($AgentJarPath)) {
    $AgentJarPath = Join-Path $sourceRoot 'target\sakura-execution-agent-0.1.0-SNAPSHOT.jar'
}
if ([string]::IsNullOrWhiteSpace($KnownHostsPath)) {
    $KnownHostsPath = Join-Path $sourceRoot 'conf\known_hosts'
}
$agentJar = (Resolve-Path -LiteralPath $AgentJarPath).Path
$knownHosts = (Resolve-Path -LiteralPath $KnownHostsPath).Path
$installPath = [IO.Path]::GetFullPath($InstallRoot)
$installDriveRoot = [IO.Path]::GetPathRoot($installPath)

if ($installPath.TrimEnd('\') -eq $installDriveRoot.TrimEnd('\')) {
    throw 'InstallRoot 不能是磁盘根目录。'
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw 'Agent 端口必须在 1-65535 之间。'
}
$knownHostEntries = @(Get-Content -LiteralPath $knownHosts | Where-Object {
    -not [string]::IsNullOrWhiteSpace($_) -and -not $_.TrimStart().StartsWith('#')
})
if ($knownHostEntries.Count -eq 0) {
    throw 'known_hosts 没有主机公钥记录。请先完成 SSH 主机指纹带外确认。'
}

$java = Get-Command $JavaCommand -ErrorAction Stop
$savedErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersionText = (& $java.Source -version 2>&1 | Out-String)
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = $savedErrorActionPreference
if ($javaExitCode -ne 0) {
    throw "Java 版本检查失败：$javaVersionText"
}
if ($javaVersionText -notmatch 'version\s+"(?<major>\d+)') {
    throw "无法识别 Java 版本：$javaVersionText"
}
if ([int]$Matches.major -lt 17) {
    throw "Execution Agent 要求 Java 17 或更高版本，当前版本：$($Matches.major)"
}

foreach ($profile in $Profiles) {
    $profilePath = Join-Path $sourceRoot "drivers\$profile"
    if (-not (Test-Path -LiteralPath $profilePath -PathType Container)) {
        throw "驱动 profile 未组装：$profile。请先运行 build-drivers.ps1。"
    }
    if (@(Get-ChildItem -LiteralPath $profilePath -Filter '*.jar' -File).Count -eq 0) {
        throw "驱动 profile 没有 JAR：$profile"
    }
}

$plan = [pscustomobject]@{
    InstallRoot = $installPath
    AgentJar = $agentJar
    KnownHosts = $knownHosts
    Profiles = @($Profiles)
    Java = $java.Source
    Port = $Port
    TaskName = $(if ($SkipTaskRegistration) { '不注册' } else { $TaskName })
    RotateToken = [bool]$RotateToken
}
$plan | Format-List
if ($PlanOnly) {
    Write-Host '计划检查通过，未修改文件、Secret 或计划任务。'
    exit 0
}

if (-not $SkipTaskRegistration) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw '注册启动计划任务需要以管理员身份运行 PowerShell。'
    }
}

# 升级时先停止旧计划任务和明确属于该安装目录的 Agent 进程，避免覆盖运行中的 JAR 或误判端口冲突。
& (Join-Path $PSScriptRoot 'stop-agent.ps1') `
    -InstallRoot $installPath `
    -Port $Port `
    -TaskName $TaskName

function New-AgentToken {
    $bytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
        $generator.Dispose()
    }
}

function Unprotect-MachineToken {
    param([string]$Path)
    [byte[]]$encryptedBytes = New-Object byte[] ([int](Get-Item -LiteralPath $Path).Length)
    $stream = [IO.File]::OpenRead($Path)
    try {
        $offset = 0
        while ($offset -lt $encryptedBytes.Length) {
            $read = $stream.Read($encryptedBytes, $offset, $encryptedBytes.Length - $offset)
            if ($read -le 0) {
                throw 'Agent DPAPI Token 文件读取不完整'
            }
            $offset += $read
        }
    } finally {
        $stream.Dispose()
    }
    $entropy = [Text.Encoding]::UTF8.GetBytes('sakura-execution-agent-v1')
    $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect(
        [byte[]]$encryptedBytes,
        [byte[]]$entropy,
        [Security.Cryptography.DataProtectionScope]::LocalMachine
    )
    try {
        return [Text.Encoding]::UTF8.GetString($plainBytes)
    } finally {
        [Array]::Clear($plainBytes, 0, $plainBytes.Length)
    }
}

function Read-AgentEnvToken {
    param([string]$Path)
    $token = $null
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^SAKURA_AGENT_TOKEN=(.*)$') {
            $token = $Matches[1]
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "环境文件缺少 SAKURA_AGENT_TOKEN：$Path"
    }
    return $token
}

function Set-RestrictedFileAcl {
    param([string]$Path, [string[]]$ReadSids, [string[]]$FullControlSids = @())
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sidValue in @('S-1-5-18', 'S-1-5-32-544') + $FullControlSids) {
        $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow
        )
        $acl.AddAccessRule($rule)
    }
    foreach ($sidValue in $ReadSids) {
        $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            [Security.AccessControl.FileSystemRights]::Read,
            [Security.AccessControl.AccessControlType]::Allow
        )
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Grant-DirectoryReadExecute {
    param([string]$Path, [string]$SidValue)
    $acl = Get-Acl -LiteralPath $Path
    $sid = [Security.Principal.SecurityIdentifier]::new($SidValue)
    $inheritanceFlags = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $sid,
        [Security.AccessControl.FileSystemRights]::ReadAndExecute,
        $inheritanceFlags,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Grant-DirectoryModify {
    param([string]$Path, [string]$SidValue)
    $acl = Get-Acl -LiteralPath $Path
    $sid = [Security.Principal.SecurityIdentifier]::new($SidValue)
    $inheritanceFlags = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $sid,
        [Security.AccessControl.FileSystemRights]::Modify,
        $inheritanceFlags,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Copy-WindowsPowerShellScript {
    param([string]$Source, [string]$Destination)
    $content = Get-Content -LiteralPath $Source -Raw
    # Windows PowerShell 5.1 需要 BOM 才能稳定解析包含中文的 UTF-8 脚本。
    $utf8WithBom = [Text.UTF8Encoding]::new($true)
    [IO.File]::WriteAllText($Destination, $content, $utf8WithBom)
}

$workspacePath = Join-Path $installPath 'workspace'
New-Item -ItemType Directory -Force $installPath, (Join-Path $installPath 'conf'), (Join-Path $installPath 'drivers'), (Join-Path $installPath 'logs'), $workspacePath | Out-Null
Grant-DirectoryReadExecute -Path $installPath -SidValue 'S-1-5-19'
Grant-DirectoryModify -Path (Join-Path $installPath 'logs') -SidValue 'S-1-5-19'
# 计划任务默认工作目录是 System32，必须给 Agent 指定可写的专用 workspace。
Grant-DirectoryModify -Path $workspacePath -SidValue 'S-1-5-19'
Copy-Item -LiteralPath $agentJar -Destination (Join-Path $installPath 'sakura-execution-agent.jar') -Force
Copy-Item -LiteralPath $knownHosts -Destination (Join-Path $installPath 'conf\known_hosts') -Force
Copy-WindowsPowerShellScript -Source (Join-Path $PSScriptRoot 'run-installed-agent.ps1') -Destination (Join-Path $installPath 'run-agent.ps1')
Copy-WindowsPowerShellScript -Source (Join-Path $PSScriptRoot 'check-agent.ps1') -Destination (Join-Path $installPath 'check-agent.ps1')
Copy-WindowsPowerShellScript -Source (Join-Path $PSScriptRoot 'stop-agent.ps1') -Destination (Join-Path $installPath 'stop-agent.ps1')

foreach ($profile in $Profiles) {
    $sourceProfile = Join-Path $sourceRoot "drivers\$profile"
    $targetProfile = Join-Path $installPath "drivers\$profile"
    if (Test-Path -LiteralPath $targetProfile) {
        Remove-Item -LiteralPath $targetProfile -Recurse -Force
    }
    Copy-Item -LiteralPath $sourceProfile -Destination $targetProfile -Recurse
}

$currentUserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$envFilePath = Join-Path $installPath 'conf\agent.env'
$legacyMachineTokenPath = Join-Path $installPath 'conf\agent-token.machine'
$legacyOperatorTokenPath = Join-Path $installPath 'conf\agent-token.operator.clixml'
if ((Test-Path -LiteralPath $envFilePath) -and -not $RotateToken) {
    $agentToken = Read-AgentEnvToken -Path $envFilePath
} elseif ((Test-Path -LiteralPath $legacyMachineTokenPath) -and -not $RotateToken) {
    # 兼容旧版本安装，迁移完成后统一只保留 agent.env。
    $agentToken = Unprotect-MachineToken -Path $legacyMachineTokenPath
} elseif ((Test-Path -LiteralPath $legacyOperatorTokenPath) -and -not $RotateToken) {
    $agentToken = (Import-Clixml -LiteralPath $legacyOperatorTokenPath).GetNetworkCredential().Password
} else {
    $agentToken = New-AgentToken
}

# Windows 与 Linux 统一使用 agent.env；ACL 限制安装账号和 LOCAL SERVICE 读取明文 Token。
$envTempPath = Join-Path $installPath ("conf\agent.env.{0}.tmp" -f [guid]::NewGuid().ToString('N'))
$envContent = "SAKURA_AGENT_TOKEN=$agentToken`r`nAUTOMATION_EXECUTION_AGENT_TOKEN=$agentToken`r`n"
[IO.File]::WriteAllText($envTempPath, $envContent, [Text.UTF8Encoding]::new($false))
Set-RestrictedFileAcl -Path $envTempPath -ReadSids @('S-1-5-19') -FullControlSids @($currentUserSid)
Move-Item -LiteralPath $envTempPath -Destination $envFilePath -Force
foreach ($legacyPath in @($legacyMachineTokenPath, $legacyOperatorTokenPath)) {
    Remove-Item -LiteralPath $legacyPath -Force -ErrorAction SilentlyContinue
}

$manifest = foreach ($profile in $Profiles) {
    Get-ChildItem -LiteralPath (Join-Path $installPath "drivers\$profile") -Filter '*.jar' -File |
        Sort-Object Name |
        ForEach-Object {
            [pscustomobject]@{
                profile = $profile
                file = $_.Name
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                bytes = $_.Length
            }
        }
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $installPath 'driver-manifest.json') -Encoding UTF8

if (-not $SkipTaskRegistration) {
    $existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        throw "端口 $Port 已被其他进程监听，未注册启动计划任务。"
    }
    $runnerPath = Join-Path $installPath 'run-agent.ps1'
    $taskArguments = "-NoProfile -ExecutionPolicy Bypass -File `"$runnerPath`" -InstallRoot `"$installPath`" -Port $Port -JavaCommand `"$($java.Source)`""
    $action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument $taskArguments
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $taskPrincipal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\LOCAL SERVICE' -LogonType ServiceAccount
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $taskPrincipal -Settings $settings -Force | Out-Null
    $bootstrapLogPath = Join-Path $installPath 'logs\bootstrap-error.log'
    Remove-Item -LiteralPath $bootstrapLogPath -Force -ErrorAction SilentlyContinue
    Start-ScheduledTask -TaskName $TaskName

    $healthy = $false
    for ($attempt = 1; $attempt -le 20; $attempt++) {
        Start-Sleep -Seconds 1
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
            if ($health.status -eq 'ok') {
                $healthy = $true
                break
            }
        } catch {
            # 启动阶段允许短暂不可用，最终失败时统一给出排查命令。
        }
    }
    if (-not $healthy) {
        $diagnostics = [System.Collections.Generic.List[string]]::new()
        try {
            $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
            $taskInfo = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction Stop
            $resultUnsigned = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int32]$taskInfo.LastTaskResult), 0)
            $resultHex = '0x{0:X8}' -f $resultUnsigned
            $diagnostics.Add("计划任务状态=$($task.State)，最近退出码=$resultHex（$($taskInfo.LastTaskResult)）")
        } catch {
            $diagnostics.Add("无法读取计划任务状态：$($_.Exception.Message)")
        }
        if (Test-Path -LiteralPath $bootstrapLogPath -PathType Leaf) {
            $bootstrapErrors = @(Get-Content -LiteralPath $bootstrapLogPath -Tail 5)
            if ($bootstrapErrors.Count -gt 0) {
                $diagnostics.Add("启动日志=$($bootstrapErrors -join ' | ')")
            }
        } else {
            $diagnostics.Add("未生成启动日志，计划任务可能尚未执行到 Agent 启动脚本")
        }
        throw "Agent 未在 20 秒内通过健康检查。$($diagnostics -join '；')"
    }
}

Write-Host "Agent 安装完成：$installPath"
Write-Host "统一 Token 文件：$envFilePath"
Write-Host "Admin Token：$agentToken"
Write-Host 'Admin 启动前从 agent.env 注入 AUTOMATION_EXECUTION_AGENT_TOKEN，不要重新生成。'

Remove-Variable agentToken -ErrorAction SilentlyContinue
