# 小智管理前端

本目录从原 server 仓库拆为独立 Git 仓库，日常分支为 `Dev`。Vue 3 前端位于仓库根目录，原 Vue 2 前端保存在 `legacy/`。

```text
frontend/
├── .git/                  # 独立提交历史
├── src/                   # Vue 3 页面、路由与 API 客户端
├── public/                # 静态资源
├── scripts/               # 构建和检查脚本
├── tests/                 # 前端测试
├── legacy/                # 保留的 Vue 2 源码
├── deploy/                # 静态部署 Nginx 模板
├── docs/                  # 拆分来源与历史记录
├── Dockerfile             # 独立静态镜像
└── package-lock.json       # 当前前端依赖锁文件
```

## 开发

使用 `.nvmrc` 中的 Node 版本，在本仓库根目录执行：

```sh
npm ci --no-audit --no-fund
npm run dev
```

默认页面为 `http://127.0.0.1:18894`，`/xiaozhi` 代理到 `http://127.0.0.1:18893`。连接其他后端时，在本地 `.env.local` 设置 `VITE_API_PROXY_TARGET`。`VITE_*` 会进入浏览器，服务端密钥保存在后端。

后端位于工作区 `backend/xiaozhi-server/repo/`，使用自己的 Git 仓库。本机 `python3 backend/dev/manage.py up` 仍可联合启动现有两份工作副本；单独启动后端使用 `up --no-frontend`。独立克隆前端后，直接用 npm 命令连接已有 API 即可。

## 验证和构建

```sh
npm test
npm run test:snapshot
npm run check:i18n
npm run build
docker build -t xiaozhi-frontend .
```

静态镜像通过 `API_PROXY_TARGET` 配置 API 目标，默认是容器网络中的 `http://api:8002`。前端 CI 执行本仓库检查，无需检出后端。

## Git 与来源

从此目录执行 `git status`、`git add` 和 `git commit`，只管理前端。拆分前可用的 23 条本地相关提交已保留，原提交与过滤后提交的对应关系见 [历史映射](docs/MONOREPO_COMMIT_MAP.tsv)。原 server 仓库是浅克隆，本次未补取更早的上游历史。

前端远程尚未配置，未推送到任何新远程仓库。后端继续使用原 server 远程。原前端说明保存在 [拆分前说明](docs/PRE_SPLIT_README.md)，其中旧目录以历史记录理解。
