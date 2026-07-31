[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'mysql', 'oracle', 'sqlserver', 'postgresql', 'greenplum', 'gaussdb', 'sybase', 'hive',
        'tidb', 'oceanbase', 'teradata', 'mariadb', 'kingbase', 'iris', 'informix', 'db2', 'cache',
        'gbase8a', 'gbase8s', 'tdengine', 'phoenix', 'dm'
    )]
    [string[]]$Profiles,

    [switch]$SkipClean
)

$ErrorActionPreference = 'Stop'
$agentRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$driversRoot = Join-Path $agentRoot 'drivers'
$driversPom = Join-Path $driversRoot 'pom.xml'
$outputRoot = $driversRoot

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw '未找到 mvn，请先安装 Maven 并加入 PATH。'
}

if (-not $SkipClean) {
    & mvn -f $driversPom clean
    if ($LASTEXITCODE -ne 0) {
        throw '清理驱动组装目录失败。'
    }
}

foreach ($profile in $Profiles) {
    Write-Host "正在组装 JDBC 驱动 profile：$profile"
    & mvn -f $driversPom "-P$profile" package
    if ($LASTEXITCODE -ne 0) {
        throw "驱动 profile 组装失败：$profile。专有驱动请先上传企业 Maven 仓库。"
    }

    $profileDirectory = Join-Path $outputRoot $profile
    $jars = @(Get-ChildItem -LiteralPath $profileDirectory -Filter '*.jar' -File -ErrorAction SilentlyContinue)
    if ($jars.Count -eq 0) {
        throw "驱动 profile 没有生成 JAR：$profile"
    }
}

$manifest = foreach ($profile in $Profiles) {
    $profileDirectory = Join-Path $outputRoot $profile
    Get-ChildItem -LiteralPath $profileDirectory -Filter '*.jar' -File |
        Sort-Object FullName |
        ForEach-Object {
            [pscustomobject]@{
                profile = $profile
                file = $_.Name
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                bytes = $_.Length
            }
        }
}

$manifestPath = Join-Path $driversRoot 'driver-manifest.json'
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

Write-Host "驱动组装完成：$outputRoot"
Write-Host "驱动清单：$manifestPath"
