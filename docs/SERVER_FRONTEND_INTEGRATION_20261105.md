# Server Vue 3 前端整合记录

2026-11-05 按用户明确要求，管理网页整合进 `xiaozhi-server`，替代原 Vue 2。

- 整合前 Server 提交：`1f0f41e8cbf05be01b7d388fd383a3b639e1afcd`。
- 整合前独立前端提交：`f1ba04cf294ec2d8eb687226a8816c4b007b420c`。
- Vue 3 标准目录：`main/manager-web/`。
- `main/manager-web-next` 与工作区 `frontend/` 为兼容入口。
- Vue 2 源码归档：工作区 `archive/server-web-vue2-20261105/`。

原前端 Git 历史通过本地 fetch 导入，分支 `backup/standalone-server-frontend-20261105` 保存全部已存在的前端历史。本次整合提交以原 Server 与原前端提交为两个父提交，保留两边历史。整合前 Server 另有备份分支 `backup/pre-server-frontend-integration-20261105`。

移动时核对原前端全部 1,206 个受版本控制文件的 Git blob，Vue 3 与归档的 Vue 2 原文件均保持一致。原独立前端 `.git` 保存于本机 `.local/server-frontend-integration-20261105-222447/standalone-frontend.git`，源码目录中不保留嵌套 Git。

Dev、前端 CI、Makefile 和镜像构建统一使用 `main/manager-web`。`legacy-*` 命令兼容到当前 Vue 3；Vue 2 不再参与默认构建和启动。整合网页/API 镜像恢复原 `web` 标签接口，前端使用 Vue 3；Nginx 转发支持 WebSocket 和 SSE。

原 17 项未提交工作保留。对同时存在原改动的 Dev 脚本和 README，分别转换原 HEAD 和工作文件，索引只纳入整合适配，已有语音功能修改继续留在工作区。未操作数据库、烧录设备或实际推送。测试和校验日志保存在同一私有整合记录目录。
