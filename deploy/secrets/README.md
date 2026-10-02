# 本地部署密钥

本目录只保留这份说明，其他文件全部被 Git 忽略。启动前要准备这些文件：

- `mysql-root-password`、`mysql-app-password`、`redis-password`、`elastic-password`：对应服务的密码，48 位 Base64。
- `root-initial-password`：root 账户的初始密码，首次登录后必须修改。
- `ai-config-key`：用户级 AI 密钥的落库加密密钥，32 字节 Base64。
- `aliyun-oss-credentials`：阿里云 OSS 的 AccessKey，后端与判题进程共用。

除 `aliyun-oss-credentials` 外每个文件恰好一行，权限都是 `0600`。文件名即配置项名：Docker 里挂进 `/run/secrets/`，本机开发时后端和判题进程直接读本目录。

`aliyun-oss-credentials` 必须按以下固定顺序严格包含两行：

```text
ALIYUN_OSS_ACCESS_KEY_ID=...
ALIYUN_OSS_ACCESS_KEY_SECRET=...
```

任何密钥都不得提交、截图、写入日志或放进前端构建产物。
