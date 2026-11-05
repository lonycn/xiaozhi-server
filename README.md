# Xiaozhi Server

本仓库统一管理 Vue 3 管理网页、Java API，以及 Python 语音和 AI 服务。日常开发使用 `master` 分支，远程为 [lonycn/xiaozhi-server](https://github.com/lonycn/xiaozhi-server)。

当前前端是 Vue 3 + Vite + Pinia + Element Plus，标准目录为 `main/manager-web/`。Vue 2 已移出当前代码树，仅保留在本地归档与 Git 历史中，不参与安装、构建或部署。

## 目录

```text
main/
├── manager-web/          # Vue 3 管理网页
├── manager-web-next     # 指向 manager-web 的兼容链接
├── manager-api/          # Java API 与数据库迁移
└── xiaozhi-server/       # Python 语音服务、网关及 worker
dev/                     # 开发环境、统一启停与测试
docs/                    # 接口、运维和开发文档
assets/fonts/            # 后端绘图字体
releases/                # 公开发布索引，不含设备备份或私有配置
```

ESP32 固件在独立的 [xiaozhi-esp32](https://github.com/lonycn/xiaozhi-esp32) 仓库维护。

## 本地开发

开发环境需要 Node.js（版本见前端 `.nvmrc`）、JDK 21、Maven、Python 3.11 和 Docker Compose。已有本地私有配置的 Dev 环境，在仓库根目录执行：

```sh
npm ci --prefix main/manager-web --no-audit --no-fund
python3 dev/manage.py up --build
python3 dev/manage.py status
```

- 管理网页：`http://127.0.0.1:18894`
- Manager API：`http://127.0.0.1:18893/xiaozhi`

前端单独开发和只启动后端：

```sh
npm run dev --prefix main/manager-web
python3 dev/manage.py up --no-frontend
```

停止环境使用 `python3 dev/manage.py stop`，保留已有数据库和持久卷。首次配置、worker 依赖与功能开关见 [Dev 说明](dev/README.md)。

## 验证与构建

```sh
npm test --prefix main/manager-web
npm run test:snapshot --prefix main/manager-web
npm run check:i18n --prefix main/manager-web
npm run build --prefix main/manager-web
python3 -m unittest discover -s dev/tests -p 'test_*.py'
```

| 镜像 | 构建入口 |
| --- | --- |
| Vue 3 网页 + Java API | `docker build -f Dockerfile-web -t xiaozhi-web .` |
| 仅静态网页 | `docker build -t xiaozhi-web-static main/manager-web` |
| 仅 Java API | `Dockerfile-api` |
| Python 服务 | `Dockerfile-server` |

接口和环境相关测试按各模块说明执行，使用隔离测试库。源码、构建通过与现场验收分别记录。

## 配置与隐私

真实回调地址、对接方标识、密钥和账号仅放在 Git 忽略的本地配置中。仓库中的示例使用通用占位值，不附带客户专用回调配置。浏览器可见的 `VITE_*` 配置不保存服务端密钥。

数据库、设备 Flash / NVS、运行日志和私有验收证据保留在代码目录之外或忽略目录，不随源码提交。

## 文档与来源

- [文档索引](docs/README.md)
- [前端说明](main/manager-web/README.md)
- [模块目录](main/README.md)
- [Git 管理](docs/GIT_WORKFLOW.md)
- [仓库与远程](docs/REPOSITORY_REMOTES.md)
- [发布索引](releases/README.md)

项目基于 [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server) 开发，版权和许可见 [LICENSE](LICENSE)。
