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
TIMEOUT_SECONDS="10"

usage() {
  cat <<USAGE
用法：sudo bash scripts/stop-agent.sh [选项]
  --install-root PATH   安装目录，默认 ${DEFAULT_INSTALL_ROOT}
  --port PORT           Agent 回环监听端口，默认 19091
  --service-name NAME   systemd 服务名，默认 sakura-execution-agent
  --timeout SECONDS     优雅停止等待秒数，默认 10，范围 1-60
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
[[ "$INSTALL_ROOT" != *[[:space:]]* ]] || { echo 'install-root 不能包含空白字符' >&2; exit 1; }
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1 && PORT <= 65535 )) || { echo '端口必须在 1-65535 之间' >&2; exit 1; }
[[ "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] && (( TIMEOUT_SECONDS >= 1 && TIMEOUT_SECONDS <= 60 )) || { echo 'timeout 必须在 1-60 秒之间' >&2; exit 1; }
[[ "$SERVICE_NAME" =~ ^[A-Za-z0-9_.@-]+$ ]] || { echo 'service-name 格式不合法' >&2; exit 1; }
[[ "$EUID" -eq 0 ]] || { echo '停止 Agent 需要 root 权限，请使用 sudo。' >&2; exit 1; }

JAR_PATH="${INSTALL_ROOT}/sakura-execution-agent.jar"

resolve_process_jar() {
  local process_id="$1"
  local jar_argument="$2"
  local candidate="$jar_argument"
  if [[ "$candidate" != /* ]]; then
    local process_cwd
    process_cwd="$(readlink -f "/proc/${process_id}/cwd" 2>/dev/null || true)"
    [[ -n "$process_cwd" ]] || return 1
    candidate="${process_cwd}/${candidate}"
  fi
  readlink -f -- "$candidate" 2>/dev/null || printf '%s\n' "$candidate"
}

list_agent_processes() {
  local command_file process_id argument previous_argument resolved_candidate resolved_expected
  resolved_expected="$(readlink -f -- "$JAR_PATH" 2>/dev/null || printf '%s\n' "$JAR_PATH")"
  for command_file in /proc/[0-9]*/cmdline; do
    [[ -r "$command_file" ]] || continue
    process_id="${command_file#/proc/}"
    process_id="${process_id%/cmdline}"
    previous_argument=""
    while IFS= read -r -d '' argument; do
      if [[ "$previous_argument" = "-jar" ]]; then
        resolved_candidate="$(resolve_process_jar "$process_id" "$argument" || true)"
        if [[ -n "$resolved_candidate" && "$resolved_candidate" = "$resolved_expected" ]]; then
          printf '%s\n' "$process_id"
        fi
        break
      fi
      previous_argument="$argument"
    done <"$command_file" 2>/dev/null || true
  done
}

wait_for_agent_exit() {
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  local -a current_processes=()
  while true; do
    mapfile -t current_processes < <(list_agent_processes)
    (( ${#current_processes[@]} == 0 )) && return 0
    (( SECONDS >= deadline )) && return 1
    sleep 0.25
  done
}

port_is_listening() {
  if command -v ss >/dev/null 2>&1; then
    ss -H -ltn "sport = :${PORT}" 2>/dev/null | grep -q .
    return
  fi
  local port_hex
  port_hex="$(printf '%04X' "$PORT")"
  awk -v suffix=":${port_hex}" '$4 == "0A" && substr($2, length($2) - 4) == suffix { found = 1 } END { exit !found }' \
    /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

if command -v systemctl >/dev/null 2>&1 && systemctl cat "${SERVICE_NAME}.service" >/dev/null 2>&1; then
  if systemctl is-active --quiet "${SERVICE_NAME}.service"; then
    echo "正在停止 systemd 服务：${SERVICE_NAME}.service"
    systemctl stop "${SERVICE_NAME}.service"
  fi
fi

mapfile -t agent_processes < <(list_agent_processes)
if (( ${#agent_processes[@]} > 0 )); then
  echo "正在向 Agent 进程发送 SIGTERM：${agent_processes[*]}"
  kill -TERM "${agent_processes[@]}" 2>/dev/null || true
fi

if ! wait_for_agent_exit; then
  mapfile -t agent_processes < <(list_agent_processes)
  if (( ${#agent_processes[@]} > 0 )); then
    echo "Agent 未在 ${TIMEOUT_SECONDS} 秒内退出，发送 SIGKILL：${agent_processes[*]}" >&2
    kill -KILL "${agent_processes[@]}" 2>/dev/null || true
  fi
fi

if ! wait_for_agent_exit; then
  mapfile -t remaining_processes < <(list_agent_processes)
  echo "Agent 进程停止失败：${remaining_processes[*]}" >&2
  exit 1
fi

if port_is_listening; then
  echo "Agent 已停止，但端口 ${PORT} 仍被其他进程占用；为避免误停，脚本未处理该进程。" >&2
  exit 1
fi

echo "Sakura Execution Agent 已停止：InstallRoot=${INSTALL_ROOT}，Port=${PORT}"
