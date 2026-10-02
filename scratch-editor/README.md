# 积木编辑器

本目录是官方 [scratch-gui](https://github.com/scratchfoundation/scratch-gui) `v5.3.0` 的改制版，
为平台「积木创作台」提供编辑器页面，由 `teaching-platform-scratch-editor` 容器以静态资源方式托管，
前端通过 `/scratch/` 路径嵌入它。

## 相对官方版本的改动

1. `src/lib/teaching-bridge/`：与平台前端的 postMessage 通信桥（协议见 `protocol.js` 头注释；
   宿主侧同构定义在 `frontend/src/features/blockcoding/scratchBridge.ts`，两侧同步改）。
   编辑器主动发出的事件除 ready / dirty 外，还有把积木拖出工作区的 `blocks/drag`（进出各一次）
   与拖出后松手的 `blocks/dragged-out`（带那段积木的顶层 XML 与指针位置；积木由 scratch-blocks
   自己弹回原位，宿主只拿一份副本）。宿主用 `host/overlay` 报告浮在编辑器上的面板区域，
   积木拖到面板上一律算拖出工作区、不算删除区。
2. `src/playground/teaching.jsx`：平台专用入口（挂桥；保留 HashParserHOC，默认工程装载靠它触发）。
   产物页面 `teaching.html`。
3. `webpack.config.js`：`teaching` entry；`publicPath` 默认 `/scratch/`（`EDITOR_PUBLIC_PATH=auto` 可覆盖）；
   `NestedPublicPathPlugin` 修正 scratch-storage 预构建包里硬编码的根路径并拷贝其 fetch-worker chunk。
4. `src/generated/` 与 `static/microbit/`：官方 prepublish 脚本的下载产物，已入库，安装依赖时不再联网下载。
5. 镜像由 `deploy/docker/scratch-editor/` 下的 Dockerfile 构建：镜像内完成 `npm ci && npm run build`，nginx 托管 `build/`，监听 8080，`/health` 探活。

## 本机构建与验证

```
npm ci && npm run build        # 产物在 build/，页面 teaching.html
npm run test:unit
```

## CSP 要求

编辑器页面（`/scratch/` 路径）最小可行 CSP：

```
default-src 'self'; script-src 'self' 'unsafe-eval'; worker-src 'self' blob:;
img-src 'self' data: blob:; media-src 'self' data: blob:; font-src 'self' data:;
style-src 'self' 'unsafe-inline'; connect-src 'self' data: blob:; frame-ancestors 'self'
```

`'unsafe-eval'` 不可省：scratch-parser 用 ajv 校验工程文件，ajv 运行时生成校验函数。缺了它校验必失败，
scratch-vm 会跌进 sb1 旧格式回退并报出误导性的 `Non-ascii character in FixedAsciiString`。
该 CSP 只覆盖 `/scratch/` 路径，平台主站维持原严格 CSP。
