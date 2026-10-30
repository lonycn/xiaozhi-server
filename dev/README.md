# Dev 开发预览

日常开发与测试统一使用 Git 分支 **`Dev`**；`main` 保留上游基线，稳定后再从 Dev 提交可审核的合并请求。修改提交、数据库迁移与测试记录均跟随 Dev 保存。

本机页面：<http://127.0.0.1:18892/#/conversations>。后台入口：<http://127.0.0.1:18892/#/home>。

## 启停

在仓库根目录执行：

```sh
python3 dev/manage.py up --build
python3 dev/manage.py status
python3 dev/manage.py stop
```

前端为 Vue CLI 开发服务，修改源码自动更新；Java 后端改动后用 `up --build` 重建并重启 Dev API。需要 Docker、JDK 21、Maven 和 Node/npm；缺少前端依赖时构建命令会按现有锁文件安装。

首次开发环境已经通过只读快照复制本机智控台数据，登录账号/密码沿用原智控台。Dev 的登录 Token 单独签发。后续测试写入只落在独立的 Dev 数据库，不同步回原服务。

若在另一台已有本机智控台的电脑首次创建 Dev 环境，可执行一次：

```sh
python3 dev/manage.py up --build --seed-local-console
```

该选项仅允许导入空 Dev 数据库，拒绝覆盖已有表。来源固定为 `xiaozhi-console-mysql-1`；没有这个来源时不使用此选项，按原项目流程初始化用户即可。

## 环境隔离

| 项目 | Dev |
| --- | --- |
| Compose 项目 | `xiaozhi-server-dev` |
| 前端 | `127.0.0.1:18892` |
| Java API | `127.0.0.1:18893`（前端 `/xiaozhi` 代理） |
| MySQL / Redis | 独立容器、独立持久卷、不映射宿主端口 |
| 配置、数据库快照、日志 | `dev/.runtime/`，忽略提交，私有文件权限 600 |
| 功能开关 | Dev 启用统一对话 API 与页面；默认发布配置仍关闭 |

`stop` 保留 Dev 数据。不要运行删除数据卷的命令。开发环境不接管现有 ESP32，不改变设备 OTA、WebSocket、固件及原智控台。

## 当前页面能力

新建/选择/分页读取自己的会话，发送文字，刷新读取持久历史，展示原有位置消息。不同会话分别保留未发送草稿；网络失败时保留内容，重试沿用同一个幂等编号。中文输入法确认候选词不会触发发送。

当前尚未接入 AI 回复、附件上传、人工客服与实时音视频，页面会明确显示这个范围。

## Vue 3 迁移判断

长期目标采用 Vue 3 + Vite + Vue Router 4，并选择 Vue 3 组件库；Element Plus 可作为现有 Element 风格的迁移候选。当前 61 个 Vue 单文件组件依赖 Vue 2.6、Vuex 3、Vue Router 3、Element UI 2，不能只更新一个版本号就完成迁移。

本轮先保留现有栈，交付可运行的 Dev 和统一对话页面。后续独立迁移任务按登录/权限、布局/导航、模型/设备配置、对话与客服模块切分，逐块迁移与回归。新组件避免直接依赖 Vue 内部 API，API client 与展示组件解耦，便于之后移植。

官方依据：

- [Vue 2 维护结束说明](https://v2.vuejs.org/eol/)
- [Vue 3 迁移构建与组件库限制](https://v3-migration.vuejs.org/migration-build.html)
