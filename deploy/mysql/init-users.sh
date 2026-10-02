#!/usr/bin/env bash
set -euo pipefail

read_secret() {
  local path="$1"
  local -a lines=()

  [[ -f "${path}" ]] || {
    echo "缺少数据库密钥文件：${path}" >&2
    exit 1
  }

  mapfile -t lines < "${path}"
  if [[ "${#lines[@]}" != "1" || -z "${lines[0]}" ]]; then
    echo "数据库密钥文件必须恰好包含一行非空值：${path}" >&2
    exit 1
  fi
  if [[ ! "${lines[0]}" =~ ^[A-Za-z0-9+/]{48}$ ]]; then
    echo "数据库密钥必须是 48 位 Base64 值：${path}" >&2
    exit 1
  fi

  printf '%s' "${lines[0]}"
}

app_password="$(read_secret /run/secrets/mysql-app-password)"
root_password="$(read_secret /run/secrets/mysql-root-password)"

MYSQL_PWD="${root_password}" mysql \
  --protocol=socket \
  --user=root \
  --database=mysql <<SQL
ALTER USER 'teaching_app'@'%'
  IDENTIFIED WITH caching_sha2_password BY '${app_password}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'teaching_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON teaching_platform.* TO 'teaching_app'@'%';
FLUSH PRIVILEGES;
SQL

expected_plugins=$'teaching_app\tcaching_sha2_password'
actual_plugins="$(
  MYSQL_PWD="${root_password}" mysql \
    --protocol=socket \
    --user=root \
    --batch \
    --skip-column-names \
    --execute="
      SELECT user, plugin
      FROM mysql.user
      WHERE host = '%'
        AND user = 'teaching_app'
      ORDER BY user;
    "
)"
if [[ "${actual_plugins}" != "${expected_plugins}" ]]; then
  echo "MySQL 应用账号必须且只能使用 caching_sha2_password" >&2
  exit 1
fi
