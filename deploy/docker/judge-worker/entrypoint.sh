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

for name in redis-password aliyun-oss-credentials; do
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

shutdown() {
  kill -TERM "${worker_pid:-}" "${sandbox_pid:-}" 2>/dev/null || true
}

trap shutdown INT TERM EXIT

# 沙箱并行度与 Java worker 的评测并发度必须一致,同一个环境变量驱动
/opt/go-judge/go-judge \
  -http-addr 127.0.0.1:5050 \
  -parallelism "${TEACHING_JUDGE_CONCURRENCY:-1}" \
  -release &
sandbox_pid=$!

chown -R 10001:10001 /var/log/teaching

setpriv \
  --reuid=10001 \
  --regid=10001 \
  --clear-groups \
  java -XX:MaxRAMPercentage=75 -jar /opt/teaching/judge-worker.jar &
worker_pid=$!

while kill -0 "$sandbox_pid" 2>/dev/null && kill -0 "$worker_pid" 2>/dev/null; do
  sleep 1
done

if ! kill -0 "$sandbox_pid" 2>/dev/null; then
  set +e
  wait "$sandbox_pid"
  status=$?
  set -e
else
  set +e
  wait "$worker_pid"
  status=$?
  set -e
fi

shutdown
wait "$sandbox_pid" 2>/dev/null || true
wait "$worker_pid" 2>/dev/null || true
trap - INT TERM EXIT
exit "$status"
