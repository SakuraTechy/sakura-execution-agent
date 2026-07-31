#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
INSTALL_ROOT="/opt/sakura-execution-agent"
ENV_ROOT="/etc/sakura-execution-agent"
AGENT_JAR="${SOURCE_ROOT}/target/sakura-execution-agent-0.1.0-SNAPSHOT.jar"
KNOWN_HOSTS="${SOURCE_ROOT}/conf/known_hosts"
PROFILES=""
PORT="19091"
SERVICE_USER="sakura"
SERVICE_GROUP="sakura"
ROTATE_TOKEN="false"
SKIP_SERVICE="false"
PLAN_ONLY="false"

usage() {
  cat <<'USAGE'
用法：bash scripts/install-agent.sh [选项]
  --install-root PATH   安装目录，默认 /opt/sakura-execution-agent
  --agent-jar PATH      Agent JAR
  --known-hosts PATH    已带外确认的 known_hosts
  --profiles CSV        需要部署的 JDBC profile，例如 mysql,postgresql
  --port PORT           回环监听端口，默认 19091
  --service-user USER   低权限运行用户，默认 sakura
  --rotate-token        轮换共享 Token；默认重装时复用旧 Token
  --skip-service        只安装文件，不创建或启动 systemd 服务
  --plan-only           只校验并显示计划，不修改系统
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-root) INSTALL_ROOT="$2"; shift 2 ;;
    --agent-jar) AGENT_JAR="$2"; shift 2 ;;
    --known-hosts) KNOWN_HOSTS="$2"; shift 2 ;;
    --profiles) PROFILES="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --service-user) SERVICE_USER="$2"; SERVICE_GROUP="$2"; shift 2 ;;
    --rotate-token) ROTATE_TOKEN="true"; shift ;;
    --skip-service) SKIP_SERVICE="true"; shift ;;
    --plan-only) PLAN_ONLY="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$INSTALL_ROOT" = /* && "$INSTALL_ROOT" != "/" ]] || { echo 'install-root 必须是非根目录的绝对路径' >&2; exit 1; }
[[ "$INSTALL_ROOT" != *[[:space:]]* ]] || { echo 'install-root 不能包含空白字符' >&2; exit 1; }
[[ -f "$AGENT_JAR" ]] || { echo "Agent JAR 不存在：$AGENT_JAR" >&2; exit 1; }
[[ -s "$KNOWN_HOSTS" ]] || { echo "known_hosts 不存在或为空：$KNOWN_HOSTS" >&2; exit 1; }
grep -Eq '^[[:space:]]*[^#[:space:]]' "$KNOWN_HOSTS" || { echo "known_hosts 没有主机公钥记录：$KNOWN_HOSTS" >&2; exit 1; }
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1 && PORT <= 65535 )) || { echo '端口必须在 1-65535 之间' >&2; exit 1; }

JAVA_BIN="$(command -v java || true)"
[[ -n "$JAVA_BIN" ]] || { echo '未找到 java' >&2; exit 1; }
JAVA_VERSION_TEXT="$($JAVA_BIN -version 2>&1)"
JAVA_MAJOR=""
if [[ "$JAVA_VERSION_TEXT" =~ version[[:space:]]+\"([0-9]+) ]]; then
  JAVA_MAJOR="${BASH_REMATCH[1]}"
fi
[[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -ge 17 ]] || { echo 'Execution Agent 要求 Java 17 或更高版本' >&2; exit 1; }

ALLOWED_PROFILES=' mysql oracle sqlserver postgresql greenplum gaussdb sybase hive tidb oceanbase teradata mariadb kingbase iris informix db2 cache gbase8a gbase8s tdengine phoenix dm '
IFS=',' read -r -a PROFILE_LIST <<<"$PROFILES"
for profile in "${PROFILE_LIST[@]}"; do
  [[ -z "$profile" ]] && continue
  [[ "$ALLOWED_PROFILES" == *" $profile "* ]] || { echo "未知 JDBC profile：$profile" >&2; exit 1; }
  profile_dir="${SOURCE_ROOT}/drivers/${profile}"
  [[ -d "$profile_dir" ]] || { echo "驱动 profile 未组装：$profile" >&2; exit 1; }
  compgen -G "${profile_dir}/*.jar" >/dev/null || { echo "驱动 profile 没有 JAR：$profile" >&2; exit 1; }
done

printf '安装目录：%s\nJAR：%s\nknown_hosts：%s\nprofiles：%s\nJava：%s\n端口：%s\n' \
  "$INSTALL_ROOT" "$AGENT_JAR" "$KNOWN_HOSTS" "${PROFILES:-无外置 JDBC 驱动}" "$JAVA_BIN" "$PORT"
if [[ "$PLAN_ONLY" = "true" ]]; then
  echo '计划检查通过，未修改文件、Secret 或 systemd。'
  exit 0
fi

[[ "$EUID" -eq 0 ]] || { echo '安装需要 root 权限，请使用 sudo。' >&2; exit 1; }
command -v openssl >/dev/null || { echo '未找到 openssl，无法生成安全 Token。' >&2; exit 1; }

# 升级前停止 systemd 服务或明确使用当前安装目录 JAR 的手工进程，避免覆盖运行中的制品。
bash "$SCRIPT_DIR/stop-agent.sh" \
  --install-root "$INSTALL_ROOT" \
  --port "$PORT" \
  --service-name sakura-execution-agent

if ! getent group "$SERVICE_GROUP" >/dev/null; then
  groupadd --system "$SERVICE_GROUP"
fi
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --gid "$SERVICE_GROUP" --home-dir "$INSTALL_ROOT" --shell /usr/sbin/nologin "$SERVICE_USER"
fi

install -d -o root -g "$SERVICE_GROUP" -m 0750 "$INSTALL_ROOT" "$INSTALL_ROOT/drivers" "$INSTALL_ROOT/conf"
install -d -o root -g root -m 0700 "$ENV_ROOT"
install -o root -g "$SERVICE_GROUP" -m 0750 "$AGENT_JAR" "$INSTALL_ROOT/sakura-execution-agent.jar"
install -o root -g "$SERVICE_GROUP" -m 0640 "$KNOWN_HOSTS" "$INSTALL_ROOT/conf/known_hosts"
install -o root -g "$SERVICE_GROUP" -m 0750 "$SCRIPT_DIR/check-agent.sh" "$INSTALL_ROOT/check-agent.sh"
install -o root -g "$SERVICE_GROUP" -m 0750 "$SCRIPT_DIR/stop-agent.sh" "$INSTALL_ROOT/stop-agent.sh"

for profile in "${PROFILE_LIST[@]}"; do
  [[ -z "$profile" ]] && continue
  target_profile="${INSTALL_ROOT}/drivers/${profile}"
  rm -rf -- "$target_profile"
  install -d -o root -g "$SERVICE_GROUP" -m 0750 "$target_profile"
  find "${SOURCE_ROOT}/drivers/${profile}" -maxdepth 1 -type f -name '*.jar' -exec install -o root -g "$SERVICE_GROUP" -m 0640 {} "$target_profile/" \;
done

ENV_FILE="${ENV_ROOT}/agent.env"
if [[ -s "$ENV_FILE" && "$ROTATE_TOKEN" != "true" ]]; then
  agent_token="$(sed -n 's/^SAKURA_AGENT_TOKEN=//p' "$ENV_FILE" | head -n 1)"
else
  agent_token="$(openssl rand -base64 48 | tr -d '\r\n')"
fi
[[ "${#agent_token}" -ge 64 ]] || { unset agent_token; echo 'Agent Token 生成或读取失败' >&2; exit 1; }

env_temp="$(mktemp "${ENV_ROOT}/agent.env.XXXXXX")"
chmod 0600 "$env_temp"
{
  printf 'SAKURA_AGENT_TOKEN=%s\n' "$agent_token"
  printf 'AUTOMATION_EXECUTION_AGENT_TOKEN=%s\n' "$agent_token"
} >"$env_temp"
unset agent_token
chown root:root "$env_temp"
mv -f -- "$env_temp" "$ENV_FILE"

if [[ "$SKIP_SERVICE" != "true" ]]; then
  command -v systemctl >/dev/null || { echo '未找到 systemctl；请使用 --skip-service 后接入现有服务管理工具。' >&2; exit 1; }
  command -v curl >/dev/null || { echo '未找到 curl，无法执行安装后健康检查。' >&2; exit 1; }
  cat > /etc/systemd/system/sakura-execution-agent.service <<UNIT
[Unit]
Description=Sakura Execution Agent
After=network-online.target

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_GROUP}
WorkingDirectory=${INSTALL_ROOT}
EnvironmentFile=${ENV_FILE}
ExecStart=${JAVA_BIN} -Dsakura.agent.bind=127.0.0.1 -Dsakura.agent.port=${PORT} -Dsakura.agent.driver-dir=${INSTALL_ROOT}/drivers -Dsakura.agent.known-hosts=${INSTALL_ROOT}/conf/known_hosts -jar ${INSTALL_ROOT}/sakura-execution-agent.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
  chmod 0644 /etc/systemd/system/sakura-execution-agent.service
  systemctl daemon-reload
  systemctl enable --now sakura-execution-agent

  healthy="false"
  for _ in $(seq 1 20); do
    if curl --fail --silent --max-time 2 "http://127.0.0.1:${PORT}/health" | grep -q '"status":"ok"'; then
      healthy="true"
      break
    fi
    sleep 1
  done
  [[ "$healthy" = "true" ]] || { echo 'Agent 未在 20 秒内通过健康检查，请执行 journalctl -u sakura-execution-agent。' >&2; exit 1; }
fi

echo "Agent 安装完成：$INSTALL_ROOT"
echo "共享环境文件：$ENV_FILE（root:root 0600）"
echo 'Admin 必须读取其中的 AUTOMATION_EXECUTION_AGENT_TOKEN，不要重新生成。'
