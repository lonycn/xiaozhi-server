# 前后端独立 Git 管理

2026-11-05 按用户选择拆成两个独立仓库，两边均使用 `Dev` 分支。

| 仓库 | 工作区位置 | 管理内容 | 远程 |
| --- | --- | --- | --- |
| 前端 | `frontend/` | Vue 3、`legacy/` 下的 Vue 2、前端测试、静态镜像和前端 CI | 尚未配置 |
| 后端 | `backend/xiaozhi-server/repo/` | Java API、Python 服务与 worker、运维、接口文档和固件版本目录 | 原 `https://github.com/lonycn/xiaozhi-server.git` |

固件继续使用独立的固件仓库。工作区根目录不初始化 Git。

## 查看和提交

在工作区根目录分别查看：

```sh
git -C frontend status --short
git -C backend/xiaozhi-server/repo status --short
```

进入对应仓库，按本次变更选择文件并检查暂存区后提交：

```sh
cd frontend
git add -- <本次前端文件>
git diff --cached
git commit -m "说明前端变更"
```

后端同理，在 `backend/xiaozhi-server/repo/` 操作。前后端接口联动时分别提交，并在发布记录中记录两边 SHA。推送和创建前端远程按当次任务要求执行；本次拆分未推送。

## 历史和已有改动

前端通过按路径导出、导入历史独立建库，保留当时本机可用的 23 条相关提交及作者、时间、提交说明。路径过滤会改变提交 SHA，前端 `docs/MONOREPO_COMMIT_MAP.tsv` 保存原 SHA 对应关系。原 server 是浅克隆，本次没有补取更早上游历史。

后端保留原提交历史与远程，在新提交中移出前端所有权。拆分前状态由后端分支 `backup/pre-frontend-backend-split-20261105` 保留。原未提交修改和私有运行文件完整保留；拆分提交仅包含目录与构建适配。

后端的 `main/manager-web-next` 和 `main/manager-web` 是两个兼容目录链接，分别指向独立前端及其 `legacy/`。这些链接不把前端源码纳入后端 Git；从链接进入前端后，Git 识别前端仓库。后续前端变更只在前端提交。

## 版本控制边界

提交源码、测试、迁移、依赖与锁文件、示例配置、文档和不含私有信息的版本目录。真实 `.env`、密钥、数据库和转储、Flash / NVS、运行配置、日志、依赖、构建产物与临时实验留在忽略目录。

后端本机保留原有非 cone 稀疏检出及工作树，新增 `assets/fonts/` 纳入本地检出范围。新增其他目录前先查看 `git sparse-checkout list`，通过 `git sparse-checkout add --stdin` 增加范围，不覆盖既有规则。

## 独立构建

前端在自己的根目录执行 `npm ci`、`npm test`、`npm run build`，其 `Dockerfile` 只构建静态页面。后端 Java 镜像使用 `Dockerfile-api`，Python 镜像继续使用 `Dockerfile-server`。

本机联合启停仍可用 `python3 backend/dev/manage.py up`；仅启动后端使用 `up --no-frontend`。后端构建只执行 Maven，前端依赖在前端仓库安装。后端绘图字体独立保存在 `assets/fonts/`，无需读取前端仓库。
