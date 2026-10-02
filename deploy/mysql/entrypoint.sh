#!/usr/bin/env bash
set -euo pipefail

source_directory=/run/source-secrets
target_directory=/run/secrets

is_tmpfs_mount() {
  awk -v target="${target_directory}" '
    $5 == target {
      for (i = 1; i <= NF; i++) {
        if ($i == "-" && $(i + 1) == "tmpfs") {
          found = 1
        }
      }
    }
    END { exit(found ? 0 : 1) }
  ' /proc/self/mountinfo
}

if [[ ! -d "${target_directory}" || -L "${target_directory}" ]] \
  || ! is_tmpfs_mount
then
  echo "MySQL secret 目标必须是独立的 tmpfs 目录" >&2
  exit 1
fi

chown mysql:mysql "${target_directory}"
chmod 0700 "${target_directory}"

for name in mysql-root-password mysql-app-password; do
  source_file="${source_directory}/${name}"
  target_file="${target_directory}/${name}"
  if [[ ! -f "${source_file}" || -L "${source_file}" ]]; then
    echo "缺少有效的 MySQL secret：${name}" >&2
    exit 1
  fi
  cp "${source_file}" "${target_file}"
  chown mysql:mysql "${target_file}"
  chmod 0400 "${target_file}"
done

exec /usr/local/bin/docker-entrypoint.sh "$@"
