# 工作区目录约定

整理日期：2026-11-05。工作区根目录为 `esp32/`，根目录本身不初始化 Git。前后端使用已有的同一个 Git 仓库，保留远程、分支和提交历史。

```text
esp32/
├── frontend -> backend/xiaozhi-server/repo/main/manager-web-next
├── backend/
│   ├── manager-api -> xiaozhi-server/repo/main/manager-api
│   ├── services -> xiaozhi-server/repo/main/xiaozhi-server
│   ├── dev -> xiaozhi-server/repo/dev
│   └── xiaozhi-server/                  # 原部署集合与实际 Git 仓库
│       └── repo/                       # 前后端共享 Git 根目录
├── firmware/
│   ├── xiaozhi/                        # 固件 Dev 工作树
│   ├── oled-2.5.23 -> ../work/.../source
│   ├── round-2.5.23 -> ../work/.../source
│   └── screen-mirror/                  # 独立演示固件
├── docs -> backend/xiaozhi-server/repo/docs
├── releases -> .local/releases          # 本地固件包、发布指针、版本目录
├── backups -> .local/backups            # Flash / NVS 与数据库备份入口
├── archive/
│   ├── experiments -> ../work
│   ├── frontend-vue2 -> ../backend/xiaozhi-server/repo/main/manager-web
│   ├── legacy-console -> ../backend/xiaozhi-server/console
│   └── legacy-voice-source -> ../backend/xiaozhi-server/source
├── references/                         # 固件上游参考仓库
├── screen-mirror/                      # 电脑端桥接、网页和日志入口
├── examples/                           # 硬件示例
├── tools/                              # ESP-IDF 环境脚本
├── work/                               # 原始实验与交付证据
└── .local/                             # 原始私有文件、运行记录与备份
```

带 `->` 的入口是相对目录链接，访问或编辑时作用于同一份文件。源码、运行目录和大文件保持原位置；运行脚本、Docker 挂载、CI 和已有交付记录继续使用原路径。`archive/` 仅提供历史资料入口，不表示其中所有实验均已结束；已有 `work/` 路径保留。

## 各类文件放置位置

| 内容 | 放置位置 | Git |
| --- | --- | --- |
| 当前 Vue 3 前端 | `frontend/`，实际为仓库 `main/manager-web-next/` | 前后端共享仓库 |
| Java 管理后端 | `backend/manager-api/` | 前后端共享仓库 |
| Python 服务与 worker | `backend/services/` | 前后端共享仓库 |
| 开发运维脚本 | `backend/dev/` | 前后端共享仓库 |
| 接口、设计与运维文档 | `docs/` | 前后端共享仓库 |
| 发布版本目录 | `releases/catalog.toml` | 实际文件在仓库 `releases/catalog.toml` |
| 固件二进制与本地验收证据 | `releases/<版本目录>/` | 本地保留 |
| Flash / NVS、Dev 与旧控制台数据库备份 | `backups/`、`backups/dev-database/`、`backups/legacy-console-database/` | 本地保留 |
| 数据库 / 私有运行配置 | 原 `dev/.runtime/` 与旧部署私有目录 | 本地保留 |
| 临时实验、构建和日志 | `work/`、`.local/`、原日志目录 | 本地保留 |

固件 `firmware/xiaozhi` 属于独立的固件仓库，其 Git 管理由 `references/xiaozhi-esp32/.git` 的 worktree 实现。整理时该工作树版本声明为 2.5.14；实机 2.5.23 对应独立源码快照，具体路径见发布目录。两个带版本号的固件入口固定指向该版本，不自动代表未来的最新版本。

## 检查或重建分类入口

在工作区根目录执行（Python 3.11 或更新版本）：

```sh
python3 backend/dev/workspace_layout.py --check
python3 backend/dev/workspace_layout.py --apply
```

在分类入口尚未创建的新工作区，使用实际脚本路径：

```sh
python3 backend/xiaozhi-server/repo/dev/workspace_layout.py --apply
```

检查不修改文件。创建操作先检查全部目标和路径冲突，只创建缺失链接，已有普通文件、目录或不同链接会使操作停止。脚本不移动源码、不启动服务，也不操作数据库。只克隆 server 仓库的环境无需创建这些工作区入口，仍可按仓库原路径开发。
