#!/bin/bash
set -euo pipefail

# root 阶段:读取仅 root 可见的密钥文件转为环境变量(密钥不落任何可读文件),
# 随后降权到镜像默认身份(uid 1000, gid 0)执行官方入口。
# 背景:compose 的 secret 挂载保留宿主机 600 权限,而 ES 镜像全程以 uid 1000
# 运行,无法直接读取;与 mysql/redis 的自带入口转存模式同源。
ELASTIC_PASSWORD="$(cat /run/source-secrets/elastic-password)"
export ELASTIC_PASSWORD

# chroot 会把工作目录重置为 /,而官方入口用相对路径探测 bin/elasticsearch-users,
# 必须先回到 ES 主目录再执行
exec chroot --userspec=1000:0 / /bin/bash -c \
    'cd /usr/share/elasticsearch && exec /usr/local/bin/docker-entrypoint.sh eswrapper'
