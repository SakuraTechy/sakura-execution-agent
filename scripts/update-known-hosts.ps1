[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$HostName,

    [ValidateRange(1, 65535)]
    [int]$Port = 22,

    [string[]]$ExpectedSha256Fingerprint = @(),

    [string]$KnownHostsPath = (Join-Path $PSScriptRoot '..\conf\known_hosts')
)

$ErrorActionPreference = 'Stop'

foreach ($command in @('ssh-keyscan', 'ssh-keygen')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "未找到 $command，请先安装 OpenSSH Client 并加入 PATH。"
    }
}

$candidateLines = @(& ssh-keyscan -T 5 -p $Port $HostName 2>$null) |
    Where-Object { $_ -and -not $_.StartsWith('#') }
if ($LASTEXITCODE -ne 0 -or $candidateLines.Count -eq 0) {
    throw "未能从 $HostName`:$Port 获取 SSH 主机公钥。"
}

$candidates = foreach ($line in $candidateLines) {
    $temporaryFile = [System.IO.Path]::GetTempFileName()
    try {
        Set-Content -LiteralPath $temporaryFile -Value $line -Encoding ascii
        $fingerprintOutput = (& ssh-keygen -lf $temporaryFile -E sha256 2>$null | Select-Object -First 1)
        if ($LASTEXITCODE -ne 0 -or -not $fingerprintOutput) {
            continue
        }
        $fingerprint = [regex]::Match($fingerprintOutput, 'SHA256:[^\s]+').Value
        if (-not $fingerprint) {
            continue
        }
        [pscustomobject]@{
            Fingerprint = $fingerprint
            KeyType = ($line -split '\s+')[1]
            Line = $line
        }
    } finally {
        Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
    }
}

if (@($candidates).Count -eq 0) {
    throw '无法解析 ssh-keyscan 返回的主机公钥。'
}

Write-Host "候选主机指纹（必须与目标主机本地或 CMDB 记录带外核对）："
$candidates | Select-Object KeyType, Fingerprint | Format-Table -AutoSize

if ($ExpectedSha256Fingerprint.Count -eq 0) {
    Write-Host '当前为只读预览，未修改 known_hosts。核对后通过 -ExpectedSha256Fingerprint 再次执行。'
    return
}

$expected = @($ExpectedSha256Fingerprint | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$verified = @($candidates | Where-Object { $expected -contains $_.Fingerprint })
if ($verified.Count -ne $expected.Count) {
    throw '至少一个期望指纹未出现在扫描结果中，known_hosts 未修改。'
}

$resolvedKnownHostsPath = [System.IO.Path]::GetFullPath($KnownHostsPath)
$knownHostsDirectory = Split-Path $resolvedKnownHostsPath -Parent
New-Item -ItemType Directory -Force -Path $knownHostsDirectory | Out-Null
if (-not (Test-Path -LiteralPath $resolvedKnownHostsPath)) {
    New-Item -ItemType File -Path $resolvedKnownHostsPath | Out-Null
}

$existingLines = @(Get-Content -LiteralPath $resolvedKnownHostsPath -ErrorAction SilentlyContinue)
foreach ($candidate in $verified) {
    if ($existingLines -notcontains $candidate.Line) {
        Add-Content -LiteralPath $resolvedKnownHostsPath -Value $candidate.Line -Encoding ascii
        $existingLines += $candidate.Line
    }
}

Write-Host "已写入并去重：$resolvedKnownHostsPath"
