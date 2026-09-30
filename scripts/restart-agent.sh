#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
case "$SCRIPT_DIR" in
  */scripts) DEFAULT_INSTALL_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)" ;;
  *) DEFAULT_INSTALL_ROOT="$SCRIPT_DIR" ;;
esac

INSTALL_ROOT="$DEFAULT_INSTALL_ROOT"
PORT="19091"
SERVICE_NAME="sakura-execution-agent"
TIMEOUT_SECONDS="20"

usage() {
  cat <<USAGE
用法：sudo bash scripts/restart-agent.sh [选项]
  --install-root PATH   安装目录，默认 ${DEFAULT_INSTALL_ROOT}
  --port PORT           Agent 回环监听端口，默认 19091
  --service-name NAME   systemd 服务名，默认 sakura-execution-agent
  --timeout SECONDS     停止和启动健康检查超时秒数，默认 20，范围 1-60
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-root) INSTALL_ROOT="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --service-name) SERVICE_NAME="$2"; shift 2 ;;
    --timeout) TIMEOUT_SECONDS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

INSTALL_ROOT="${INSTALL_ROOT%/}"
[[ "$INSTALL_ROOT" = /* && "$INSTALL_ROOT" != "/" ]] || { echo 'install-root 必须是非根目录的绝对路径' >&2; exit 1; }
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1 && PORT <= 65535 )) || { echo '端口必须在 1-65535 之间' >&2; exit 1; }
[[ "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] && (( TIMEOUT_SECONDS >= 1 && TIMEOUT_SECONDS <= 60 )) || { echo 'timeout 必须在 1-60 秒之间' >&2; exit 1; }
[[ "$SERVICE_NAME" =~ ^[A-Za-z0-9_.@-]+$ ]] || { echo 'service-name 格式不合法' >&2; exit 1; }
[[ "$EUID" -eq 0 ]] || { echo '重启 Agent 需要 root 权限，请使用 sudo。' >&2; exit 1; }
command -v curl >/dev/null || { echo '未找到 curl，无法执行健康检查。' >&2; exit 1; }
systemctl cat "${SERVICE_NAME}.service" >/dev/null 2>&1 || { echo "systemd 服务不存在：${SERVICE_NAME}.service" >&2; exit 1; }

"$SCRIPT_DIR/stop-agent.sh" \
  --install-root "$INSTALL_ROOT" \
  --port "$PORT" \
  --service-name "$SERVICE_NAME" \
  --timeout "$TIMEOUT_SECONDS"
systemctl start "${SERVICE_NAME}.service"

deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  if curl --fail --silent --max-time 2 "http://127.0.0.1:${PORT}/health" | grep -q '"status":"ok"'; then
    echo "Sakura Execution Agent 重启成功：InstallRoot=${INSTALL_ROOT}，Port=${PORT}"
    exit 0
  fi
  sleep 0.5
done

echo "Agent 重启后未在 ${TIMEOUT_SECONDS} 秒内通过健康检查：InstallRoot=${INSTALL_ROOT}，Port=${PORT}" >&2
exit 1
