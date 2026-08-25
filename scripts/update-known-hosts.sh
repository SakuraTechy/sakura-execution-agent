#!/usr/bin/env bash
if [ -z "${BASH_VERSION:-}" ]; then
  if command -v bash >/dev/null 2>&1; then
    exec bash "$0" "$@"
  fi
  echo '该脚本需要 Bash，请安装 bash 后重试。' >&2
  exit 1
fi

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KNOWN_HOSTS_PATH="${SCRIPT_DIR}/../conf/known_hosts"
HOST_NAME=""
PORT="22"
declare -a EXPECTED_FINGERPRINTS=()

usage() {
  cat <<'USAGE'
用法：bash scripts/update-known-hosts.sh --host-name HOST [选项]
  --host-name HOST                   SSH 目标主机名或 IP 地址
  --port PORT                        SSH 端口，默认 22
  --expected-sha256-fingerprint FP   已带外确认的 SHA256 指纹，可重复传入
  --known-hosts PATH                 输出文件，默认 ../conf/known_hosts
  -h, --help                         显示帮助

未传入 --expected-sha256-fingerprint 时只扫描并显示候选指纹，不修改文件。
也兼容 -HostName、-Port、-ExpectedSha256Fingerprint、-KnownHostsPath 参数形式。
USAGE
}

fail() {
  echo "$1" >&2
  exit 1
}

trim() {
  sed 's/^[[:space:]]*//; s/[[:space:]]*$//' <<<"$1"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host-name|-HostName)
      [[ $# -ge 2 ]] || { echo '--host-name 缺少参数' >&2; usage >&2; exit 2; }
      HOST_NAME="$2"
      shift 2
      ;;
    --port|-Port)
      [[ $# -ge 2 ]] || { echo '--port 缺少参数' >&2; usage >&2; exit 2; }
      PORT="$2"
      shift 2
      ;;
    --expected-sha256-fingerprint|-ExpectedSha256Fingerprint)
      [[ $# -ge 2 ]] || { echo '--expected-sha256-fingerprint 缺少参数' >&2; usage >&2; exit 2; }
      EXPECTED_FINGERPRINTS+=("$2")
      shift 2
      ;;
    --known-hosts|-KnownHostsPath)
      [[ $# -ge 2 ]] || { echo '--known-hosts 缺少参数' >&2; usage >&2; exit 2; }
      KNOWN_HOSTS_PATH="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "$HOST_NAME" ]] || { echo '--host-name 为必填参数' >&2; usage >&2; exit 2; }
[[ "$HOST_NAME" != -* && "$HOST_NAME" != *[[:space:]]* ]] || fail 'host-name 不能以 - 开头或包含空白字符。'
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1 && PORT <= 65535 )) || fail '端口必须在 1-65535 之间。'

for command in ssh-keyscan ssh-keygen; do
  command -v "$command" >/dev/null 2>&1 || fail "未找到 ${command}，请先安装 OpenSSH Client 并加入 PATH。"
done

temporary_directory="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

candidate_output="${temporary_directory}/candidate-lines"
if ! ssh-keyscan -T 5 -p "$PORT" "$HOST_NAME" >"$candidate_output" 2>/dev/null; then
  fail "未能从 ${HOST_NAME}:${PORT} 获取 SSH 主机公钥。"
fi

candidate_records="${temporary_directory}/candidate-records"
: >"$candidate_records"
while IFS= read -r line; do
  [[ -n "$line" && "$line" != \#* ]] || continue

  key_file="${temporary_directory}/key"
  printf '%s\n' "$line" >"$key_file"
  fingerprint_output="$(ssh-keygen -lf "$key_file" -E sha256 2>/dev/null || true)"
  fingerprint="$(sed -n 's/.*\(SHA256:[^[:space:]]*\).*/\1/p' <<<"$fingerprint_output" | head -n 1)"
  [[ -n "$fingerprint" ]] || continue

  key_type="$(awk '{print $2}' <<<"$line")"
  printf '%s\t%s\t%s\n' "$fingerprint" "$key_type" "$line" >>"$candidate_records"
done <"$candidate_output"

[[ -s "$candidate_records" ]] || fail '无法解析 ssh-keyscan 返回的主机公钥。'

echo '候选主机指纹（必须与目标主机本地或 CMDB 记录带外核对）：'
printf '%-24s %s\n' 'KeyType' 'Fingerprint'
while IFS=$'\t' read -r fingerprint key_type _; do
  printf '%-24s %s\n' "$key_type" "$fingerprint"
done <"$candidate_records"

if [[ "${#EXPECTED_FINGERPRINTS[@]}" -eq 0 ]]; then
  echo '当前为只读预览，未修改 known_hosts。核对后通过 --expected-sha256-fingerprint 再次执行。'
  exit 0
fi

declare -a expected=()
for fingerprint in "${EXPECTED_FINGERPRINTS[@]}"; do
  fingerprint="$(trim "$fingerprint")"
  [[ -n "$fingerprint" ]] || continue
  expected+=("$fingerprint")
done
[[ "${#expected[@]}" -gt 0 ]] || fail '至少提供一个非空的期望指纹。'

for fingerprint in "${expected[@]}"; do
  if ! awk -F '\t' -v expected="$fingerprint" '$1 == expected { found = 1 } END { exit !found }' "$candidate_records"; then
    fail '至少一个期望指纹未出现在扫描结果中，known_hosts 未修改。'
  fi
done

known_hosts_directory="$(dirname -- "$KNOWN_HOSTS_PATH")"
mkdir -p -- "$known_hosts_directory"
touch -- "$KNOWN_HOSTS_PATH"

while IFS=$'\t' read -r fingerprint _ key_line; do
  matched="false"
  for expected_fingerprint in "${expected[@]}"; do
    if [[ "$fingerprint" == "$expected_fingerprint" ]]; then
      matched="true"
      break
    fi
  done
  [[ "$matched" == "true" ]] || continue

  if ! grep -Fqx -- "$key_line" "$KNOWN_HOSTS_PATH"; then
    printf '%s\n' "$key_line" >>"$KNOWN_HOSTS_PATH"
  fi
done <"$candidate_records"

echo "已写入并去重：$(cd -- "$(dirname -- "$KNOWN_HOSTS_PATH")" && pwd)/$(basename -- "$KNOWN_HOSTS_PATH")"
