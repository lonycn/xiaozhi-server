# 本地仓库与 GitHub 远程

核对及配置日期：2026-11-05。

| 用途 | 工作区中的 Git 目录 | 推送目标 |
| --- | --- | --- |
| ESP32 硬件固件 | `firmware/xiaozhi/` | `https://github.com/lonycn/xiaozhi-esp32.git`，`Dev` 分支 |
| Server 后端、运维与接口文档 | `backend/xiaozhi-server/repo/` | `https://github.com/lonycn/xiaozhi-server.git`，`Dev` 分支 |
| Server 管理前端 | `frontend/` | 独立 Git 已建立，远程待选择 |

`xiaozhi-server` 原项目包含管理前端和服务后端。本地已按用户选择拆为两个独立 Git 仓库；前端的远程需要单独分配，或使用 server 远程中的专用前端分支，与后端 `Dev` 区分。当前尚未为独立前端配置远程。

## 硬件仓库

`firmware/xiaozhi` 是 `references/xiaozhi-esp32` 仓库的 Git worktree，当前分支为 `Dev`。两者共用 Git 配置和对象；参考目录保持原固定提交，固件开发在工作树中进行。

- `origin`：用户 fork `https://github.com/lonycn/xiaozhi-esp32.git`。
- `upstream`：官方 `https://github.com/78/xiaozhi-esp32.git`，保留原固定 `v2.5.2` 标签的获取规则。
- 默认推送远程：`origin`；日常推送分支：`Dev`。

配置时工作树的版本声明仍为 2.5.14，存在尚未提交的固件修改。两块实机的 2.5.23 源码分别由 `firmware/oled-2.5.23/`、`firmware/round-2.5.23/` 指向 `work/` 内的发布快照。它们尚未归并到这个 Git 工作树，当前开发树不能直接视为实机最新发布源码。

## Server 仓库

后端保留原 `origin` 与 `Dev` 分支跟踪，默认推送也明确设为 `origin`。前端实际源码在独立 `frontend/` 中，后端的两个旧前端路径仅为兼容链接。

本次只配置远程和默认推送方式，执行了 Git 推送预演，没有提交原有修改或实际推送。硬件与后端的原工作文件均保留。配置前备份在工作区 `.local/git-remote-setup-<日期时间>/`。
