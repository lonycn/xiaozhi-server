# Server 模块

本目录的网页前端与后端统一由 `xiaozhi-server` Git 仓库管理，当前开发分支为 `master`。

| 目录 | 职责 | 入口 |
| --- | --- | --- |
| `manager-web/` | Vue 3、Vite、Pinia、Element Plus 管理网页 | [前端说明](manager-web/README.md) |
| `manager-api/` | Java 管理与业务 API、权限及数据库迁移 | [API 说明](manager-api/README.md) |
| `xiaozhi-server/` | Python 语音服务、ASR / LLM / TTS 适配、网关与 worker | 服务源码及各 worker 说明 |

`manager-web-next` 仅为 `manager-web` 的兼容链接。当前代码树不包含原 Vue 2 前端；旧源码保留在本地归档与历史提交中。

## 前端开发

从仓库根目录执行：

```sh
npm ci --prefix main/manager-web --no-audit --no-fund
npm run dev --prefix main/manager-web
npm run build --prefix main/manager-web
```

页面默认为 `http://127.0.0.1:18894`，通过 `/xiaozhi` 连接 API。公开配置使用 `import.meta.env.VITE_*`，本地覆盖放在前端 `.env.local`；服务密钥保存在后端私有配置中。

前端主要目录：`src/views/` 页面、`src/components/` 组件、`src/apis/` 接口客户端、`src/router/` 路由、`src/stores/` 状态，以及 `tests/` 测试。构建产物输出到 `dist/`。

## Python 服务

`xiaozhi-server/` 下的 `core/` 提供语音与协议处理，`esp32_gateway/` 处理设备接入。AI、媒体、实时和朗读分别由对应 worker 模块承担，按已配置的功能开关启动。

## 开发与运维

统一启动、测试环境、私有配置及依赖说明见 [Dev 说明](../dev/README.md)。接口与功能文档见 [文档索引](../docs/README.md)。设备备份和客户专用回调配置不保存在本目录中。
