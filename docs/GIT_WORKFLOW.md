# Server 与硬件 Git 管理

2026-11-05 按用户明确要求，将 Server 的 Vue 3 网页整合回 Server 仓库，作为标准前端替代 Vue 2。当前是两个业务代码仓库：Server（网页前端 + 服务后端）与 ESP32 硬件固件。

| 仓库 | 工作区目录 | 内容 | origin |
| --- | --- | --- | --- |
| Server | `backend/xiaozhi-server/repo/` | `main/manager-web/` Vue 3、Java API、Python 服务、运维和文档 | `https://github.com/lonycn/xiaozhi-server.git` |
| 硬件固件 | `firmware/xiaozhi/` | ESP-IDF C/C++、屏幕、音频与板型 | `https://github.com/lonycn/xiaozhi-esp32.git` |

Server 的日常主分支为 `master`，GitHub 默认分支同步为 `master`；硬件仓库仍使用其 `Dev` 分支。默认推送远程为 `origin`。Server 的工作区分类入口 `frontend/`、`backend/manager-api/` 和 `backend/services/` 识别到同一个 Git 根目录。

## 查看和提交

从工作区根目录执行：

```sh
git -C backend/xiaozhi-server/repo status --short
git -C frontend status --short
git -C firmware/xiaozhi status --short
```

前两条是同一 Server 仓库。前端与后端的 API 联动可以在同一次明确范围的提交中保存。在对应仓库内只暂存本次文件，检查后提交：

```sh
git add -- <本次文件>
git diff --cached
git commit -m "说明本次变更"
```

既有未提交工作保留，不混入发布提交。Server 的日常推送目标为 `origin/master`。原 `main` 的有效更新已合入主线；旧分支保留历史用途，后续功能通过合并请求进入 `master`。

## 历史和兼容路径

前端此前独立仓库的历史已导入 Server，分支 `backup/standalone-server-frontend-20261105` 保留整合前状态。整合提交连接 Server 与该前端历史，原作者、时间和提交说明均保留。整合前 Server 状态由 `backup/pre-server-frontend-integration-20261105` 保留。

`main/manager-web/` 是实际 Vue 3 目录，`main/manager-web-next` 仅为仓库内部兼容链接。旧 Vue 2 已归档到工作区 `archive/server-web-vue2-20261105/`，不参与当前源码和镜像构建。独立前端的原 Git 元数据保存在本机私有整合记录中。

## 版本控制边界

提交源码、测试、迁移、依赖清单和锁文件、示例配置、文档、公开的发布索引。真实 `.env`、密钥、数据库和转储、Flash / NVS、运行配置、日志、依赖、构建产物与临时实验留在忽略目录。

本机保留原稀疏检出和现有 worktree，网页与镜像配置在本仓库内即可完整检出。新增范围先查看 `git sparse-checkout list`，再按需要增加规则，不覆盖既有本地设置。

## 构建

前端依赖在 `main/manager-web` 安装，CI 和 `make test-web` 使用同一目录。`Dockerfile-web` 构建 Vue 3 与 Java API 的整合镜像；`main/manager-web/Dockerfile` 仅构建静态页；`Dockerfile-api` 和 `Dockerfile-server` 仍可分别构建 API 与 Python 服务。

Dev 默认启动 Vue 3；`--no-frontend` 可只启动 API 和 worker。后端绘图字体在 `assets/fonts/`，保持原有文件与私有运行配置。
