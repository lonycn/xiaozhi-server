# 工作区目录约定

2026-11-05：Server 的前后端统一使用 `xiaozhi-server` Git 仓库；Vue 3 位于标准 `main/manager-web/`，替代 Vue 2。ESP32 固件继续独立管理。

```text
esp32/
├── frontend -> backend/xiaozhi-server/repo/main/manager-web
├── backend/
│   ├── manager-api -> xiaozhi-server/repo/main/manager-api
│   ├── services -> xiaozhi-server/repo/main/xiaozhi-server
│   ├── dev -> xiaozhi-server/repo/dev
│   └── xiaozhi-server/repo/             # Server 统一 Git 仓库
│       ├── .git/
│       ├── main/manager-web/           # Vue 3 实际源码
│       ├── main/manager-web-next -> manager-web
│       ├── main/manager-api/
│       ├── main/xiaozhi-server/
│       ├── assets/fonts/
│       └── dev/
├── firmware/                           # 独立硬件 Git 工作树及版本入口
├── docs -> backend/xiaozhi-server/repo/docs
├── releases -> .local/releases
├── backups -> .local/backups
├── archive/
│   ├── server-web-vue2-20261105/        # 旧 Vue 2 源码
│   ├── frontend-vue2 -> server-web-vue2-20261105
│   └── ...                            # 原实验与旧部署入口
├── references/
├── screen-mirror/
├── examples/
├── tools/
├── work/
└── .local/
```

带 `->` 的条目是相对目录链接。源码、数据库、私有配置、备份与既有交付证据按原路径保留；仅网页源码整合进 Server 标准目录，Vue 2 归档。`frontend/` 中不再存在独立 Git。

| 内容 | 工作区入口 | Git |
| --- | --- | --- |
| Vue 3 网页 | `frontend/` / Server `main/manager-web/` | Server 仓库 |
| Java API、Python 与 worker | `backend/manager-api/`、`backend/services/` | Server 仓库 |
| 运维与接口文档 | `backend/dev/`、`docs/` | Server 仓库 |
| 旧 Vue 2 | `archive/frontend-vue2/` | 本地归档及既有 Git 历史 |
| ESP32 固件 | `firmware/xiaozhi/` | 独立硬件仓库 |
| 固件版本索引 | `releases/catalog.toml` | Server 仓库内的索引 |
| 固件包、Flash / NVS、数据库备份 | `releases/`、`backups/` | 本地保存 |

硬件 `Dev` 已归并实机 2.5.23 基线并更新说明，提交为 `c3169fd`。OLED 与圆屏原始快照、固件包和备份继续保留，供对应实机发布追溯。

## 分类入口检查

在完整工作区根目录执行（Python 3.11 或更新版本）：

```sh
python3 backend/dev/workspace_layout.py --check
python3 backend/dev/workspace_layout.py --apply
```

脚本只检查或创建分类链接，要求 Server 的 Vue 3 实际目录存在且没有嵌套 `.git`。不会移动源码、重启服务或操作数据库。只克隆 Server 时直接按其 README 开发，无需创建工作区分类入口或本地历史归档。
