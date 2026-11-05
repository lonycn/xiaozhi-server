# 工作区目录约定

2026-11-05 已按用户选择拆为两个独立 Git 仓库。前端是实际目录，其他分类入口继续通过相对链接访问原有源码和本地数据。

```text
esp32/
├── frontend/                           # 前端独立 Git 仓库，Dev
│   ├── .git/
│   ├── src/                            # Vue 3
│   └── legacy/                         # 保留的 Vue 2
├── backend/
│   ├── manager-api -> xiaozhi-server/repo/main/manager-api
│   ├── services -> xiaozhi-server/repo/main/xiaozhi-server
│   ├── dev -> xiaozhi-server/repo/dev
│   └── xiaozhi-server/
│       └── repo/                       # 后端独立 Git 仓库，Dev
│           ├── .git/
│           ├── assets/fonts/           # 后端绘图字体
│           ├── main/manager-api/
│           ├── main/xiaozhi-server/
│           ├── main/manager-web-next -> ../../../../frontend
│           └── main/manager-web -> ../../../../frontend/legacy
├── firmware/
│   ├── xiaozhi/                        # 独立固件 Dev 工作树
│   ├── oled-2.5.23 -> ../work/.../source
│   └── round-2.5.23 -> ../work/.../source
├── docs -> backend/xiaozhi-server/repo/docs
├── releases -> .local/releases
├── backups -> .local/backups
├── archive/                            # 实验、旧部署和旧前端入口
├── references/                         # 固件上游参考仓库
├── screen-mirror/                      # 电脑端桥接与网页
├── examples/
├── tools/
├── work/                               # 原交付证据，路径保留
└── .local/                             # 私有配置、备份与本地发布包
```

带 `->` 的条目为目录链接。后端旧前端路径仅用于运行脚本和历史记录兼容；实际前端文件均在 `frontend/`，由前端自己的 `.git` 管理。后端及运行私有目录保留原位置，数据库和设备没有迁移。

| 内容 | 工作区入口 | Git |
| --- | --- | --- |
| Vue 3 前端 | `frontend/` | 前端仓库 |
| Vue 2 保留源码 | `frontend/legacy/`、`archive/frontend-vue2/` | 前端仓库 |
| Java API | `backend/manager-api/` | 后端仓库 |
| Python 服务与 worker | `backend/services/` | 后端仓库 |
| 开发运维 | `backend/dev/` | 后端仓库 |
| 接口、设计和运维文档 | `docs/` | 后端仓库 |
| 前端说明与提交来源 | `frontend/docs/` | 前端仓库 |
| 固件版本目录 | `releases/catalog.toml` | 后端仓库的 `releases/catalog.toml` |
| 固件二进制 | `releases/<版本目录>/` | 本地保存 |
| 设备与数据库备份 | `backups/`、`backups/dev-database/`、`backups/legacy-console-database/` | 本地保存 |
| 私有运行配置 | 原 `dev/.runtime/` 与旧部署私有目录 | 本地保存 |

`firmware/xiaozhi` 是 `references/xiaozhi-esp32/.git` 管理的 worktree。整理时其版本声明为 2.5.14；实机 2.5.23 对应的两个快照通过带版本号的固件入口访问，不自动代表未来的最新版本。

## 检查或重建分类入口

在本工作区根目录执行（Python 3.11 或更新版本）：

```sh
python3 backend/dev/workspace_layout.py --check
python3 backend/dev/workspace_layout.py --apply
```

脚本要求 `frontend/` 已有独立 `.git`，只检查或创建分类链接，不负责创建或拆分 Git 仓库。创建前检查全部路径和目标，遇到普通文件、目录或不同链接时停止。源码、服务、数据库、发布包和备份不会被脚本移动。

只检出单个仓库时直接使用该仓库的构建入口，无需重建完整工作区链接。
