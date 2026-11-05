# 本地备份位置

目录整理日期：2026-11-05。工作区 `backups/` 是 `.local/backups/` 的入口，沿用原私有目录权限。这里的说明纳入 Git，真实备份内容保存在本地。

| 备份种类 | 工作区中的位置 |
| --- | --- |
| 原始 Flash、NVS 与设备配置备份 | `backups/`，实际为 `.local/backups/` |
| 某次升级的整片 Flash 与回读证据 | 对应的 `work/<交付目录>/`；详情见该次发布记录 |
| 当前 Dev 数据库备份与私有运行配置 | `backend/xiaozhi-server/repo/dev/.runtime/` 内的原备份和配置目录 |
| 早期控制台数据库备份 | `backend/xiaozhi-server/console/.runtime/backups/` |
| 本次目录整理前的说明文件与变更清单 | `.local/directory-organization-<日期时间>/` |

备份继续保留原文件名、校验值和路径，已有 `*-path.txt` 指针保持有效。设备联网凭据、身份和私有配置可能包含在完整 Flash / NVS 备份中，不提交到 Git。

新增备份按设备或服务、日期时间分别存放，并记录来源、大小、SHA-256、关联版本和恢复验证状态。本次目录整理没有执行数据库导出、恢复、服务重启或设备烧录；已有备份是否通过恢复验证，以各自交付记录为准。
