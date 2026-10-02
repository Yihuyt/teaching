#!/bin/sh
set -eu

source_directory=/run/source-secrets
target_directory=/run/secrets

if [ ! -d "${target_directory}" ] \
  || [ -L "${target_directory}" ] \
  || ! mountpoint -q "${target_directory}"
then
  echo "容器 secret 目标必须是独立的 tmpfs 目录" >&2
  exit 1
fi
chown teaching:teaching "${target_directory}"
chmod 0700 "${target_directory}"

for name in \
  mysql-app-password \
  redis-password \
  root-initial-password \
  aliyun-oss-credentials \
  ai-config-key \
  elastic-password
do
  source_file="${source_directory}/${name}"
  target_file="${target_directory}/${name}"
  if [ ! -f "${source_file}" ] || [ -L "${source_file}" ]; then
    echo "缺少有效的容器 secret：${name}" >&2
    exit 1
  fi
  cp "${source_file}" "${target_file}"
  chown teaching:teaching "${target_file}"
  chmod 0400 "${target_file}"
done

chown -R teaching:teaching /var/log/teaching

exec su-exec teaching:teaching \
  java -XX:MaxRAMPercentage=75.0 -jar /app/teaching-backend.jar
