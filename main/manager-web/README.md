# 小智 Server 管理前端（Vue 3）

Vue 3 是本项目唯一的网页前端，源码位于 `main/manager-web/`。前端、Java API、Python 服务、测试与运维共用 Server 的 Git 仓库和 `master` 分支。

工作区 `frontend/` 是本目录的分类链接；`main/manager-web-next` 也是指向本目录的兼容链接。当前前端没有独立的 `.git` 或远程。

## 开发

使用 `.nvmrc` 指定的 Node 版本。从 Server 仓库根目录执行：

```sh
npm ci --prefix main/manager-web --no-audit --no-fund
npm run dev --prefix main/manager-web
```

页面默认 `http://127.0.0.1:18894`，`/xiaozhi` 默认代理到 `http://127.0.0.1:18893`。本地 `.env.local` 可设置 `VITE_API_PROXY_TARGET`。`VITE_*` 会进入浏览器，服务密钥保存在服务端私有配置。

统一开发启停见 `dev/README.md`：

```sh
python3 dev/manage.py up
python3 dev/manage.py stop
```

`up --no-frontend` 只启动 API 和 worker。`candidate-*` 与 `legacy-*` 为当前 Vue 3 的兼容管理命令；不会再启动归档的 Vue 2。

## 验证

从本目录执行：

```sh
npm test
npm run test:snapshot
npm run check:i18n
npm run build
```

Server 的 `.github/workflows/vue3.yml` 在本目录安装锁定依赖并运行以上检查，`make test-web` 也使用本目录。

## 镜像

从 Server 仓库根目录执行：

```sh
# Vue 3 静态页面 + Java API，沿用原 web 镜像接口
docker build -f Dockerfile-web -t xiaozhi-web .

# 仅静态页面，API_PROXY_TARGET 指定后端
docker build -t xiaozhi-web-static main/manager-web
```

静态镜像默认代理到容器网络的 `http://api:8002`。整合镜像通过 8002 提供网页与 API，支持 WebSocket 升级和对话 SSE 转发。

## 版本与历史

提交位置是 Server 仓库，远程为 `https://github.com/lonycn/xiaozhi-server.git`。前端历史已导入 Server，并通过整合提交保留。旧 Vue 2 源码已移出 Server 代码树，仅在本地归档与 Git 历史中保留。

[历史提交映射](docs/MONOREPO_COMMIT_MAP.tsv) 保留为来源记录。旧版前端说明随本地归档保存，当前目录和启动方式以本说明为准。
