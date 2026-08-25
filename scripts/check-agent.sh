#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
case "$SCRIPT_DIR" in
  */scripts) DEFAULT_INSTALL_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)" ;;
  *) DEFAULT_INSTALL_ROOT="$SCRIPT_DIR" ;;
esac

INSTALL_ROOT="${1:-$DEFAULT_INSTALL_ROOT}"
PORT="${2:-19091}"
failed="false"

command -v curl >/dev/null || { echo '[FAIL] 未找到 curl'; exit 1; }

check_file() {
  local name="$1"
  local path="$2"
  if [[ -s "$path" ]]; then
    printf '[PASS] %s：%s\n' "$name" "$path"
  else
    printf '[FAIL] %s：%s\n' "$name" "$path"
    failed="true"
  fi
}

check_file 'Agent JAR' "$INSTALL_ROOT/sakura-execution-agent.jar"
check_file 'known_hosts' "$INSTALL_ROOT/conf/known_hosts"
[[ -d "$INSTALL_ROOT/drivers" ]] && echo '[PASS] 驱动目录' || { echo '[FAIL] 驱动目录'; failed="true"; }

if command -v systemctl >/dev/null && systemctl is-active --quiet sakura-execution-agent; then
  echo '[PASS] systemd 服务运行中'
else
  echo '[FAIL] systemd 服务未运行'
  failed="true"
fi

if curl --fail --silent --max-time 5 "http://127.0.0.1:${PORT}/health" | grep -q '"status":"ok"'; then
  echo '[PASS] 健康接口'
else
  echo '[FAIL] 健康接口'
  failed="true"
fi

invalid_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 \
  -H 'Authorization: Bearer invalid-token' "http://127.0.0.1:${PORT}/v1/tasks/not-exist")"
if [[ "$invalid_status" = "401" ]]; then
  echo '[PASS] 错误 Token 被拒绝'
else
  echo "[FAIL] 错误 Token 返回 HTTP ${invalid_status}"
  failed="true"
fi

[[ "$failed" = "false" ]] || exit 1
echo 'Agent 本机部署验收通过。'
