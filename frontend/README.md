# 前端

本目录是智能教学平台的 Vue 3 桌面端。页面只使用简体中文，最小布局宽度为
`1200px`，开发服务默认监听 `0.0.0.0:8067`，并将 `/api` 代理到
`127.0.0.1:8080`。

## 工具链

- Node.js 24.11 或更高版本。
- Vue 3、Vue Router 5、Pinia 4、Element Plus 2。
- Vite 8 与 TypeScript 6.0.3。
- 官方 Vue TSC 对 Vue 单文件组件进行完整类型检查。
- Orval 根据后端运行时导出的唯一契约快照 `src/api/api-v1.json` 生成 Axios 客户端。

Vue TSC 覆盖 `<script setup>`、模板表达式、组件属性和事件；Vite 负责生产
构建。Oxlint 以零警告模式扫描源码和测试。

当前锁定 TypeScript 6.0.3 与官方 Vue TSC 3.3.8。TypeScript 7 的包导出结构
目前无法被该版 Vue TSC 正常加载，因此项目不使用双编译器、兼容脚本或失败后
回退；升级时必须先由官方 Vue TSC 明确支持，并通过现有类型检查后再整体升级。

## 目录

- `src/app/`：路由、布局、Element Plus 插件、全局样式和系统页（404、服务不可用）。
- `src/api/`：Orval 生成的接口客户端 `generated/`、Axios 实例与 SSE 读取基础设施；各功能自己的流式接口读取器放在对应功能目录。
- `src/stores/`：会话与平台设置两份全局 Pinia 状态。
- `src/shared/`：跨功能复用的组件（页头、异步状态、Markdown 渲染等）、对话框与格式化工具、通用组合式函数。
- `src/features/<功能>/`：按业务功能分目录，每个目录内是 `views/`、`components/` 与该功能自己的 TS 模块；学生端与教师端页面同属一个功能目录。功能目录与后端模块一一对应：auth、account、portal、admin（账户、公告、平台设置）、courses（课程、目录、资料、试题、成员、生成）、courseware、knowledgegraph、knowledgebase、tutor、analytics、programming、blockcoding。
- `tests/unit/<功能>/`：与功能目录对应的单元测试；`tests/e2e/`：Playwright 端到端测试及其配置；`tests/golden/`：把课件排版、行内语法、主题色导出为后端比对用的金标准数据的脚本。

## 命令

```bash
npm ci
npm run generate:api
npm run check:api-client
npm run typecheck
npm run lint
npm test
npm run build
npm run install:e2e
npm run test:e2e
```

端到端测试的 Chromium 只安装到 `node_modules` 内，不修改 WSL 的系统浏览器或
全局缓存。测试服务独立使用 `8068` 端口，避免复用正在运行的开发服务。
