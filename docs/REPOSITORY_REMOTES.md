# 本地仓库与 GitHub 远程

Server 的本地主分支与 GitHub 默认分支统一为 `master`。
更新日期：2026-11-05。Server 网页前端已整合回 Server 仓库，替代原 Vue 2，不再需要独立前端远程。

| 用途 | 工作区中的 Git 目录 | 推送目标 |
| --- | --- | --- |
| ESP32 硬件固件 | `firmware/xiaozhi/` | `https://github.com/lonycn/xiaozhi-esp32.git`，`Dev` |
| Server 网页、API、Python 服务与文档 | `backend/xiaozhi-server/repo/` | `https://github.com/lonycn/xiaozhi-server.git`，`master` |

工作区 `frontend/` 指向 Server 的 `main/manager-web/`，使用同一个 `.git`、分支和 `origin`。旧 `main/manager-web-next` 为内部兼容链接。

## 硬件

`firmware/xiaozhi` 是 `references/xiaozhi-esp32` 仓库的 worktree，共用 Git 配置和对象。`origin` 为用户 fork，`upstream` 为官方 `https://github.com/78/xiaozhi-esp32.git`，保留原固定标签获取规则。默认推送到 `origin` 的 `Dev`。

硬件 `Dev` 已归并实机 2.5.23 基线并更新说明，提交为 `c3169fd`。OLED 与圆屏原始快照、固件包和备份继续保留，供对应实机发布追溯。

## Server

Server 沿用已有 `origin`、`Dev` 和提交历史。Vue 3、Java API 与 Python 服务都从此仓库提交，前后端联动可以在同一提交中管理。原独立前端历史已导入，并由整合提交与 Server 历史连接。

Server 的公开代码从 `master` 推送到 `origin`。已有未提交语音功能修改保留在本地，不混入发布提交；真实回调配置与签名凭据继续由 Git 忽略。远程配置备份和整合记录保存在工作区 `.local/` 对应私有目录中。
