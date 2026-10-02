#!/bin/sh
set -eu

password_file=/run/secrets/redis-password
runtime_directory=/run/redis
config_file=${runtime_directory}/redis.conf

if [ ! -d "${runtime_directory}" ] \
  || [ -L "${runtime_directory}" ] \
  || ! mountpoint -q "${runtime_directory}"
then
  echo "Redis 运行配置目录必须是独立的 tmpfs" >&2
  exit 1
fi
if [ ! -f "${password_file}" ] || [ -L "${password_file}" ]; then
  echo "缺少有效的 Redis secret" >&2
  exit 1
fi
if [ "$(wc -c < "${password_file}")" -ne 48 ]; then
  echo "Redis secret 必须是 48 位 Base64 值" >&2
  exit 1
fi

password="$(cat "${password_file}")"
case "${password}" in
  *[!A-Za-z0-9+/]*)
    echo "Redis secret 必须是 48 位 Base64 值" >&2
    exit 1
    ;;
esac

chown redis:redis "${runtime_directory}"
chmod 0700 "${runtime_directory}"
{
  printf '%s\n' "appendonly yes"
  printf '%s\n' "appendfsync everysec"
  printf 'requirepass %s\n' "${password}"
} > "${config_file}"
unset password
chown redis:redis "${config_file}"
chmod 0400 "${config_file}"

exec /usr/local/bin/docker-entrypoint.sh redis-server "${config_file}"
