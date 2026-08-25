# Sakura Execution Agent

`sakura-execution-agent` 是部署在 Playwright Runner 执行节点上的本机基础设施执行器，负责真实执行：

- SSH 服务器命令；
- 22 个 JDBC 数据库 profile；
- MongoDB 原生操作。

Agent 不保存场景、项目环境或长期业务凭据。Admin 负责权限校验、项目环境解析、凭据租约和审计；Runner 负责按步骤调用 Admin；Agent 只接收 Admin 下发的短时任务并在本机访问目标服务器或数据库。

## 1. 当前版本边界

当前版本必须与 Admin、Playwright Runner 部署在同一台主机，并且 Agent 仅监听回环地址：

```text
Admin / Runner
    │  http://127.0.0.1:19091
    ▼
Execution Agent
    ├─ SSH -> 目标服务器
    ├─ JDBC -> 目标数据库
    └─ MongoDB Driver -> MongoDB
```

浏览器、Chrome 扩展和 CueCast 不得直接访问 Agent。当前代码也不支持把 Agent 地址改成远程 IP；远程执行节点需要后续增加节点注册、鉴权和任务调度通道后才能启用。

默认配置：

| 项目 | 默认值 |
| --- | --- |
| 监听地址 | `127.0.0.1` |
| 监听端口 | `19091` |
| Java | 17 或更高版本 |
| Windows 运行账号 | `LOCAL SERVICE` |
| Linux 运行账号 | `sakura` |
| Windows 安装目录 | `D:\King\sakura\sakura-admin\docker\sakura-execution-agent` |
| Linux 安装目录 | `/opt/sakura-execution-agent` |

## 2. 目录结构

源码目录：

```text
sakura-execution-agent/
├─ drivers/
│  ├─ pom.xml                    # 驱动依赖与 profile 组装
│  ├─ mysql/*.jar
│  ├─ oracle/*.jar
│  └─ ...
├─ conf/known_hosts               # 按执行节点维护，不提交真实内容
├─ scripts/
│  ├─ build-drivers.ps1
│  ├─ install-agent.ps1           # Windows 部署/升级
│  ├─ run-installed-agent.ps1     # Windows 计划任务运行模板
│  ├─ update-known-hosts.ps1      # Windows 采集并写入 known_hosts
│  ├─ stop-agent.ps1
│  ├─ check-agent.ps1
│  ├─ build-drivers.sh            # Linux JDBC 驱动组装
│  ├─ install-agent.sh            # Linux 部署/升级
│  ├─ update-known-hosts.sh       # Linux 采集并写入 known_hosts
│  ├─ stop-agent.sh
│  └─ check-agent.sh
├─ src/
├─ pom.xml                        # Agent 本体、MongoDB Driver
└─ target/sakura-execution-agent-0.1.0-SNAPSHOT.jar
```

`install-agent` 和 `run-installed-agent` 不能混为一谈：前者需要管理员/root 权限，负责安装和注册服务；后者只在 Windows 计划任务启动时解密机器 Token 并启动 Java，使用低权限账号运行。

## 3. 构建 Agent 和数据库驱动

### 3.1 构建 Agent

```powershell
Set-Location D:\King\sakura\sakura-execution-agent
mvn -DskipTests package
Get-FileHash .\target\sakura-execution-agent-0.1.0-SNAPSHOT.jar -Algorithm SHA256
```

构建产物是包含 Agent 运行依赖的可执行 JAR。发布时应登记 JAR 的 SHA-256；不要直接把 `target` 目录作为生产安装目录。

Linux 节点执行同样的 Maven 构建：

```bash
cd /data/sakura/sakura-execution-agent
mvn -DskipTests package
sha256sum target/sakura-execution-agent-0.1.0-SNAPSHOT.jar
```

### 3.2 组装 JDBC profile

```powershell
.\scripts\build-drivers.ps1 -Profiles mysql,oracle,postgresql
```

Linux 使用等价脚本：

```bash
bash scripts/build-drivers.sh \
  --profiles mysql,oracle,postgresql
```

需要保留现有 profile 输出、不执行清理时，增加 `--skip-clean`。

当前支持的 22 个 JDBC profile：

```text
mysql, oracle, sqlserver, postgresql, greenplum, gaussdb,
sybase, hive, tidb, oceanbase, teradata, mariadb, kingbase,
iris, informix, db2, cache, gbase8a, gbase8s, tdengine,
phoenix, dm
```

MongoDB 不需要 `mongodb` profile。MongoDB Java Driver 已由根目录 `pom.xml` 打包进 Agent，使用 `database_native` 执行。

驱动要求：

- JAR 必须与数据库版本和 JDK 17 兼容；
- 专有驱动必须先进入企业 Maven 仓库或经过审批的本地依赖目录；
- 发布前核对 `drivers/driver-manifest.json` 中的 SHA-256；
- 场景步骤不能自行指定驱动路径、驱动类或 profile，profile 由 Admin 数据库配置映射决定。

### 3.3 Docker 镜像部署

`Dockerfile` 使用多阶段构建 Agent JAR，并将 Agent 以低权限用户运行。`sakura-admin/docker/docker-compose.yml` 通过
`network_mode: service:sakura-execution-agent` 让 Admin 与 Agent 共享网络命名空间，因此 Admin 仍可安全访问
`http://127.0.0.1:19091`，Agent 不需要监听 `0.0.0.0` 或暴露 `19091` 到宿主机。
Docker 镜像内 Agent 运行目录统一为 `/app/sakura-execution-agent`；宿主机的 `execution-agent/conf`、`logs`、`workspace` 和 `data`
分别挂载到该目录下的对应子目录。Linux systemd 安装仍使用 `/opt/sakura-execution-agent`，两者互不影响。

部署前准备经过带外核对的 `known_hosts`：

```bash
cd /path/to/sakura-admin/docker
# Compose 会自动读取当前目录的 .env；首次部署请先按实际环境修改其中的端口、密码和调度 Token。
mkdir -p execution-agent/conf execution-agent/logs execution-agent/workspace execution-agent/data
cp /path/to/known_hosts execution-agent/conf/known_hosts
sudo bash /path/to/sakura-execution-agent/scripts/install-agent.sh \
  --install-root /opt/sakura-execution-agent \
  --known-hosts /path/to/known_hosts \
  --skip-service
bash start-docker.sh
docker compose ps
docker compose exec sakura-execution-agent curl --fail http://127.0.0.1:19091/health
```

`install-agent.sh` 负责生成或复用 `/etc/sakura-execution-agent/agent.env`，Compose 同时将其中的
`SAKURA_AGENT_TOKEN` 和 `AUTOMATION_EXECUTION_AGENT_TOKEN` 注入 Agent 与 Admin。不要再用 `openssl` 另行生成 Token，
也不要把真实 `agent.env` 或 `known_hosts` 提交到仓库。若主机上已有 systemd Agent，切换容器前先停止它，避免占用本机资源：

```bash
sudo systemctl disable --now sakura-execution-agent
```

`start-docker.sh` 默认通过宿主机路由自动获取 IPv4 并生成 `PROJECT_URL=http://<服务器IP>:5183`。
多网卡服务器可显式指定：

```bash
SERVER_IP=172.19.5.223 bash start-docker.sh
```

`docker/.env` 中的 `NGINX_HOST_PORT` 是用户访问端口，默认是 `5183`；如果该端口被占用，修改为可用端口后重新执行 `bash start-docker.sh`。
除非切换网络或端口拓扑，否则重复启动不需要先执行 `docker compose down`；首次切换拓扑时可执行
`docker compose down --remove-orphans`，不要使用 `-v`，以免删除数据库卷。

Agent 的 `19091` 仅在共享网络命名空间内可见，用户通过 Nginx 暴露的端口访问 Admin。

## 4. Windows 部署

以下命令在管理员 PowerShell 执行。

### 4.1 计划检查

```powershell
Set-Location D:\King\sakura\sakura-execution-agent

.\scripts\install-agent.ps1 `
  -InstallRoot 'D:\King\sakura\sakura-admin\docker\sakura-execution-agent' `
  -Profiles mysql,oracle,postgresql `
  -KnownHostsPath .\conf\known_hosts `
  -PlanOnly
```

`-PlanOnly` 只检查 JAR、Java、profile、端口和 `known_hosts`，不会复制文件、生成 Token 或注册计划任务。

### 4.2 正式安装或升级

```powershell
Set-Location D:\King\sakura\sakura-execution-agent

.\scripts\install-agent.ps1 `
  -InstallRoot 'D:\King\sakura\sakura-admin\docker\sakura-execution-agent' `
  -Profiles mysql,oracle,postgresql `
  -KnownHostsPath '.\conf\known_hosts' `
  -Port 19091
```

正式安装会：

1. 停止已有 `SakuraExecutionAgent` 计划任务；
2. 停止命令行明确指向该安装目录 JAR 的旧 Agent；
3. 复制 JAR、驱动、`known_hosts` 和运行/检查/停止脚本；
4. 首次安装生成 Token，重复安装默认复用 Token；
5. 生成统一的 `conf\agent.env`，并限制文件 ACL；
6. 注册由 `LOCAL SERVICE` 运行的开机计划任务；
7. 启动 Agent 并检查 `/health`。

除非要在维护窗口同步更新 Admin，否则不要使用 `-RotateToken`。轮换 Token 后必须同步修改 Admin 配置并重启 Admin。

### 4.3 Token 来源

Windows 和 Linux 安装脚本统一生成包含两个同值变量的 `agent.env`：

```text
D:\King\sakura\sakura-admin\docker\sakura-execution-agent\conf\agent.env
```

Windows 文件由安装账号创建并通过 ACL 限制安装账号和 `LOCAL SERVICE` 读取；Linux 文件由 root 创建并使用 `0600` 权限。启动 Admin 前从文件读取同一个 Token：

```powershell
$agentRoot = 'D:\King\sakura\sakura-admin\docker\sakura-execution-agent'
$agentEnv = Join-Path $agentRoot 'conf\agent.env'
$agentToken = (Get-Content $agentEnv | Where-Object { $_ -like 'AUTOMATION_EXECUTION_AGENT_TOKEN=*' } | Select-Object -First 1) -replace '^AUTOMATION_EXECUTION_AGENT_TOKEN=', ''
$env:AUTOMATION_EXECUTION_AGENT_TOKEN = $agentToken
```

IDEA 启动 Admin 时，可以把解密后的值临时填入本地未提交的 `application.yml`，或放入 IDEA Run Configuration 的环境变量。不要把真实 Token 提交到 Git、日志、截图或工单。

```yaml
automation:
  execution-agent:
    base-url: http://127.0.0.1:19091
    token: '<实际 Agent Token，仅本地临时使用>'
```

### 4.4 停止、启动和验收

```powershell
# 停止
& 'D:\King\sakura\sakura-admin\docker\sakura-execution-agent\stop-agent.ps1'

# 启动
Start-ScheduledTask -TaskName 'SakuraExecutionAgent'

# 验收
& 'D:\King\sakura\sakura-admin\docker\sakura-execution-agent\check-agent.ps1'
```

停止脚本只匹配当前安装目录的 Agent，不会按端口误杀其他 Java 进程。若 `19091` 仍被其他程序占用，脚本会报告 PID 并退出。

## 5. Linux 部署

以下命令在 Linux 节点执行，正式安装需要 root。

### 5.1 计划检查

```bash
bash scripts/install-agent.sh \
  --install-root '/data/sakura/sakura-admin/docker/sakura-execution-agent' \
  --profiles mysql,oracle,postgresql \
  --known-hosts ./conf/known_hosts \
  --plan-only
```

### 5.2 正式安装或升级

```bash
bash scripts/install-agent.sh \
  --install-root '/data/sakura/sakura-admin/docker/sakura-execution-agent' \
  --profiles mysql,oracle,postgresql \
  --known-hosts ./conf/known_hosts \
  --env-root '/data/sakura/sakura-admin/docker/sakura-execution-agent/conf/agent.env' \
  --port 19091
```

`--install-root` 和 `--env-root` 支持相对路径，按执行命令所在目录解析；`--env-root` 可以传环境文件目录，也可以直接传以 `.env` 结尾的文件路径。systemd unit 中会使用规范化后的绝对路径。

脚本会创建低权限 `sakura` 账号、复制 JAR/驱动、生成或复用 Token、创建 `/etc/sakura-execution-agent/agent.env`、注册 systemd 服务并检查 `/health`。升级时会先停止旧服务或当前安装目录下的手工 Agent，再覆盖 JAR。

脚本会自动检查 `PATH`、`JAVA_HOME`、`/usr/lib/jvm`、`/usr/local`、`/opt`、SDKMAN 用户目录和 `/etc/alternatives` 中的 Java 17+。如果 JDK 安装在非标准目录，再通过 `--java-command` 传入绝对路径。

### 5.3 Token 文件

```text
/etc/sakura-execution-agent/agent.env
```

该文件由 root 创建，权限必须为 `0600`，包含：

```text
SAKURA_AGENT_TOKEN=<同一个随机共享令牌>
AUTOMATION_EXECUTION_AGENT_TOKEN=<同一个随机共享令牌>
```

Admin 和 Agent 必须使用同一个 Token。查看文件时只确认变量名和权限，不要把值输出到终端或日志：

```bash
sudo stat -c '%U:%G %a %n' /etc/sakura-execution-agent/agent.env
sudo awk -F= '{print $1"=<redacted>"}' /etc/sakura-execution-agent/agent.env
```

### 5.4 停止、启动和验收

```bash
INSTALL_ROOT='/data/sakura/sakura-admin/docker/sakura-execution-agent'

# 停止
sudo "$INSTALL_ROOT/stop-agent.sh"

# 启动
sudo systemctl start sakura-execution-agent

# 状态和日志
sudo systemctl status sakura-execution-agent
sudo journalctl -u sakura-execution-agent -n 100 --no-pager

# 验收
sudo "$INSTALL_ROOT/check-agent.sh"
```

## 6. known_hosts 配置

SSH 执行强制启用 `StrictHostKeyChecking=yes`。`conf/known_hosts` 必须按执行节点单独维护，不能直接复制开发人员的 `~/.ssh/known_hosts`，也不能提交真实主机公钥到仓库。

Windows 先扫描候选指纹：

```powershell
.\scripts\update-known-hosts.ps1 -HostName 10.0.0.20 -Port 22
```

Linux 先扫描候选指纹：

```bash
bash scripts/update-known-hosts.sh \
  --host-name 10.0.0.20 \
  --port 22
```

脚本使用 Bash 实现，使用 `sh ./scripts/update-known-hosts.sh` 调用时会自动转交 Bash，也兼容 `-HostName`、`-Port` 等 PowerShell 风格参数。

与目标服务器本地公钥或 CMDB 带外核对后，再传入已确认指纹完成写入：

```powershell
.\scripts\update-known-hosts.ps1 `
  -HostName 10.0.0.20 `
  -Port 22 `
  -ExpectedSha256Fingerprint 'SHA256:<已确认指纹>'
```

如果需要同时信任多个已确认的主机密钥，可以传数组：

```powershell
.\scripts\update-known-hosts.ps1 `
  -HostName 10.0.0.20 `
  -Port 22 `
  -ExpectedSha256Fingerprint @(
    'SHA256:<已确认RSA指纹>',
    'SHA256:<已确认ECDSA指纹>',
    'SHA256:<已确认ED25519指纹>'
  )
```

Linux 使用同等流程：

```bash
sudo bash scripts/update-known-hosts.sh \
  --host-name 10.0.0.20 \
  --port 22 \
  --known-hosts /data/sakura/sakura-admin/docker/sakura-execution-agent/conf/known_hosts \
  --expected-sha256-fingerprint 'SHA256:<已确认指纹>'
```

如果同时信任多个已确认的主机密钥，重复传入 `--expected-sha256-fingerprint`。直接更新已安装目录后，确保文件为 root 可写、Agent 账号可读：

```bash
sudo chown root:sakura /data/sakura/sakura-admin/docker/sakura-execution-agent/conf/known_hosts
sudo chmod 0640 /data/sakura/sakura-admin/docker/sakura-execution-agent/conf/known_hosts
```

### 6.1 Shell 执行规则

服务器步骤不会再把命令直接交给 SSH 账号的默认 Shell，而是显式使用步骤选择的解释器：

| 服务器类型 | Shell 选项 | 实际执行方式 |
| --- | --- | --- |
| Linux | `bash` | `bash -lc '<命令>'` |
| Linux | `sh` | `sh -lc '<命令>'` |
| Linux | `PowerShell` | 先检查 `command -v pwsh`，再用 `pwsh -NoLogo -NoProfile -NonInteractive -Command '<命令>'` |
| Windows | `PowerShell` | `powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand <编码命令>` |

Linux 未安装 PowerShell Core 时，任务以错误码 `SSH_PWSH_NOT_INSTALLED` 失败。Agent 日志依次记录 `SSH_PWSH_CHECK_STARTED`，以及 `SSH_PWSH_AVAILABLE`、`SSH_PWSH_MISSING` 或 `SSH_PWSH_CHECK_FAILED`。日志不记录命令原文。

### 6.2 CentOS 7 安装 PowerShell 替换为阿里云镜像（国内速度快，推荐）
```bash
# 1. 备份并创建新的 Base repo
mkdir -p /etc/yum.repos.d/backup
mv /etc/yum.repos.d/*.repo /etc/yum.repos.d/backup/
# 2. 下载阿里云 CentOS 7 仓库文件
curl -o /etc/yum.repos.d/CentOS-Base.repo https://mirrors.aliyun.com/repo/Centos-7.re
# 3. 清空缓存并生成新的缓存
yum clean all && yum makecache
# 4. 重新安装 PowerShell（Microsoft 的 repo 需要重新添加）
curl https://packages.microsoft.com/config/rhel/7/prod.repo | tee /etc/yum.repos.d/microsoft.repo
# 5. 安装 PowerShell
yum install -y powershell
# 6. 安装完成后验证
pwsh --version
```

## 7. Agent 配置和接口

### 7.1 Java 系统属性

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `sakura.agent.bind` | `127.0.0.1` | 监听地址，生产禁止改为公网地址 |
| `sakura.agent.port` | `19091` | 本机 HTTP 端口 |
| `sakura.agent.driver-dir` | `drivers` | JDBC profile 根目录 |
| `sakura.agent.known-hosts` | `known_hosts` | SSH 主机公钥文件 |
| `sakura.agent.log-file` | `logs/agent.log` | 结构化诊断日志 |

### 7.2 HTTP 接口

```text
GET    /health
POST   /v1/tasks
GET    /v1/tasks/{taskId}
DELETE /v1/tasks/{taskId}
```

除 `/health` 外必须传：

```http
Authorization: Bearer <SAKURA_AGENT_TOKEN>
```

任务类型：

```text
server_command   SSH 服务器命令
database_sql     JDBC SQL（query/update/call）
database_native  MongoDB 原生操作
```

Agent 只接受 Admin 已经解析和授权的任务。浏览器或扩展不应直接调用这些接口。

## 8. 日志和故障排查

日志位置：

| 系统 | Agent 日志 | 服务日志 |
| --- | --- | --- |
| Windows | `D:\King\sakura\sakura-admin\docker\sakura-execution-agent\logs\agent.log` | 计划任务/启动窗口输出 |
| Linux systemd | 由 journald 统一收集标准输出 | `journalctl -u sakura-execution-agent` |

查看最近日志：

```powershell
Get-Content 'D:\King\sakura\sakura-admin\docker\sakura-execution-agent\logs\agent.log' -Tail 200
```

```bash
sudo journalctl -u sakura-execution-agent -n 200 --no-pager
```

Linux 手工启动时如果配置了 `-Dsakura.agent.log-file=/可写目录/agent.log`，Agent 还会写入指定文件；systemd 默认以 journald 为准。

日志会记录任务 ID、操作类型、驱动 profile、端点主机/端口/库名、驱动版本、超时、SQLState 和错误码。密码、Token、连接串密码、SQL 原文和参数值不会写入日志。

常见问题：

| 现象 | 检查方向 |
| --- | --- |
| `/health` 不通 | Agent 是否启动、端口是否被占用、Java 是否为 17+ |
| HTTP 401 | Admin 与 Agent Token 是否来自同一次安装；轮换后是否同时重启 Admin |
| SSH 主机校验失败 | `known_hosts` 是否来自带外核对，目标主机指纹是否变化 |
| Linux PowerShell 执行失败 | 查看 `SSH_PWSH_*` 日志；未安装时安装 PowerShell Core，或把步骤 Shell 改为 `bash/sh` |
| JDBC 驱动未找到 | `drivers/{profile}` 是否存在 JAR，profile 是否与 Admin 数据库类型映射一致 |
| JDBC `42S22/1054` | 连接已建立，通常是 SQL 引用了不存在的列；检查日志中的 `UNKNOWN_COLUMN` |
| 端口仍被占用 | 使用停止脚本输出的 PID 判断是否为其他程序，不能直接按端口杀进程 |
| 修改步骤后仍执行旧 SQL | 重启 Admin/UI 后重新打开步骤保存；确认 `playwright_step` 和 `sql` 均已更新 |

## 9. 安全要求

- Agent 仅监听 `127.0.0.1`，禁止暴露到公网或办公网。
- Token 使用随机值；禁止使用数据库密码、SSH 密码或 Admin 登录密码代替。
- Windows Token 文件由 ACL 保护，Linux Token 文件权限为 `0600`；不要把 `agent.env` 提交到 Git。
- `known_hosts` 必须带外确认，禁止关闭主机指纹校验。
- JDBC 和 MongoDB 凭据由 Admin 运行时解析，不写入场景步骤或公共任务响应。
- 只给数据库账号授予验收所需的最小查询/DML 权限。
- 生产升级前保留旧 JAR、驱动清单和日志备份；不要删除整个安装目录回滚。

## 10. 发布验收清单

### 静态验收

```powershell
mvn -DskipTests package
Get-FileHash .\target\sakura-execution-agent-0.1.0-SNAPSHOT.jar -Algorithm SHA256
```

### 节点验收

- [ ] JDK 17+；
- [ ] Agent 只监听 `127.0.0.1:19091`；
- [ ] `/health` 返回 `status=ok`；
- [ ] 错误 Token 返回 HTTP 401；
- [ ] 所需 JDBC profile 均有 JAR；
- [ ] `known_hosts` 非空且经过带外确认；
- [ ] Agent 运行账号不是 root/Administrator；
- [ ] 日志可以按 taskId 定位一次执行；
- [ ] Admin 使用与 Agent 相同的 Token。

### 真实执行验收

至少执行一次：

1. 成功的 SSH `server_command`；
2. 成功的 JDBC `database_sql` 查询；
3. 一次 JDBC 更新并核对 `affectedRows`；
4. 一次错误 SQL，确认任务进入 `failed` 且不死循环；
5. 一次取消任务，确认 Agent 释放连接或 SSH 通道；
6. MongoDB `database_native`（如果项目启用 MongoDB）。

没有实际厂商数据库环境时，只能声明 Agent 构建和链路验收通过，不能声称全部数据库已完成真实兼容认证。
