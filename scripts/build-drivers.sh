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
SOURCE_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
DRIVERS_ROOT="${SOURCE_ROOT}/drivers"
DRIVERS_POM="${DRIVERS_ROOT}/pom.xml"
PROFILES=""
SKIP_CLEAN="false"

ALLOWED_PROFILES='mysql oracle sqlserver postgresql greenplum gaussdb sybase hive tidb oceanbase teradata mariadb kingbase iris informix db2 cache gbase8a gbase8s tdengine phoenix dm'

usage() {
  cat <<'USAGE'
用法：bash scripts/build-drivers.sh --profiles PROFILE[,PROFILE...]
  --profiles CSV       需要组装的 JDBC profile，例如 mysql,postgresql,oracle
  --skip-clean         不执行 drivers/pom.xml clean
  -h, --help           显示帮助

也兼容 -Profiles 和 -SkipClean 参数形式。
USAGE
}

fail() {
  echo "$1" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profiles|-Profiles)
      [[ $# -ge 2 ]] || { echo '--profiles 缺少参数' >&2; usage >&2; exit 2; }
      PROFILES="$2"
      shift 2
      ;;
    --skip-clean|-SkipClean)
      SKIP_CLEAN="true"
      shift
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

[[ -n "$PROFILES" ]] || { echo '--profiles 为必填参数' >&2; usage >&2; exit 2; }
[[ -f "$DRIVERS_POM" ]] || fail "驱动 Maven POM 不存在：$DRIVERS_POM"
command -v mvn >/dev/null 2>&1 || fail '未找到 mvn，请先安装 Maven 并加入 PATH。'
command -v sha256sum >/dev/null 2>&1 || fail '未找到 sha256sum，无法生成驱动清单。'

IFS=',' read -r -a profile_list <<<"$PROFILES"
[[ "${#profile_list[@]}" -gt 0 ]] || fail '至少指定一个 JDBC profile。'

for profile in "${profile_list[@]}"; do
  profile="$(sed 's/^[[:space:]]*//; s/[[:space:]]*$//' <<<"$profile")"
  [[ -n "$profile" ]] || fail 'JDBC profile 不能为空。'
  case " $ALLOWED_PROFILES " in
    *" $profile "*) ;;
    *) fail "未知 JDBC profile：$profile" ;;
  esac
done

printf '驱动目录：%s\nprofiles：%s\n' "$DRIVERS_ROOT" "$PROFILES"

if [[ "$SKIP_CLEAN" != "true" ]]; then
  mvn -f "$DRIVERS_POM" clean || fail '清理驱动组装目录失败。'
fi

for profile in "${profile_list[@]}"; do
  profile="$(sed 's/^[[:space:]]*//; s/[[:space:]]*$//' <<<"$profile")"
  echo "正在组装 JDBC 驱动 profile：$profile"
  mvn -f "$DRIVERS_POM" "-P${profile}" package || fail "驱动 profile 组装失败：$profile。专有驱动请先上传企业 Maven 仓库。"

  profile_directory="${DRIVERS_ROOT}/${profile}"
  [[ -d "$profile_directory" ]] || fail "驱动 profile 目录不存在：$profile"
  jar_found="false"
  while IFS= read -r -d '' _; do
    jar_found="true"
    break
  done < <(find "$profile_directory" -maxdepth 1 -type f -name '*.jar' -print0)
  [[ "$jar_found" == "true" ]] || fail "驱动 profile 没有生成 JAR：$profile"
done

temporary_directory="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

manifest_entries="${temporary_directory}/manifest-entries"
: >"$manifest_entries"

for profile in "${profile_list[@]}"; do
  profile="$(sed 's/^[[:space:]]*//; s/[[:space:]]*$//' <<<"$profile")"
  profile_directory="${DRIVERS_ROOT}/${profile}"
  while IFS= read -r -d '' jar_path; do
    file_name="$(basename -- "$jar_path")"
    [[ "$file_name" =~ ^[A-Za-z0-9._+-]+$ ]] || fail "驱动文件名包含不支持的字符：$file_name"
    sha256="$(sha256sum "$jar_path" | awk '{print $1}')"
    bytes="$(wc -c <"$jar_path")"
    bytes="${bytes//[[:space:]]/}"
    printf '    {"profile":"%s","file":"%s","sha256":"%s","bytes":%s}\n' \
      "$profile" "$file_name" "$sha256" "$bytes" >>"$manifest_entries"
  done < <(find "$profile_directory" -maxdepth 1 -type f -name '*.jar' -print0 | sort -z)
done

manifest_path="${DRIVERS_ROOT}/driver-manifest.json"
manifest_temp="${temporary_directory}/driver-manifest.json"
{
  printf '[\n'
  if [[ -s "$manifest_entries" ]]; then
    sed '$!s/$/,/' "$manifest_entries"
  fi
  printf ']\n'
} >"$manifest_temp"
mv -f -- "$manifest_temp" "$manifest_path"

echo "驱动组装完成：$DRIVERS_ROOT"
echo "驱动清单：$manifest_path"
