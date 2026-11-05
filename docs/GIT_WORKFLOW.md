# 前后端 Git 管理

前端、Java 后端、Python 服务、worker、运维脚本、文档与发布目录继续在同一个仓库维护。

- 工作区中的仓库位置：`backend/xiaozhi-server/repo/`
- 远程：`https://github.com/lonycn/xiaozhi-server.git`
- 日常分支：`Dev`
- `main`：保留上游基线；稳定变更通过可审核的合并请求集成。
- 固件使用自己的独立仓库，不混入 server 仓库。

`frontend/`、`backend/manager-api/` 和 `backend/services/` 是同一仓库下的分类入口。`git -C frontend status` 可以识别实际仓库，显示的文件路径仍以该仓库根目录为基准。前后端之间的 API、功能和版本改动可以在同一次明确范围的提交中记录。

## 查看和提交

以下命令在工作区根目录执行：

```sh
git -C backend/xiaozhi-server/repo status --short
git -C backend/xiaozhi-server/repo diff --stat
```

提交时切到仓库实际根目录，按本次变更选择文件，并在提交前检查暂存区：

```sh
cd backend/xiaozhi-server/repo
git add -- <本次修改的文件或目录>
git diff --cached --stat
git diff --cached
git commit -m "说明本次变更"
```

工作区可能存在其他正在进行的修改。不要用不区分范围的暂存或还原操作把这些内容混入本次提交。推送和合并按当次任务要求执行。

本机仓库采用非 cone 模式的稀疏检出，已保留原范围，加入 `/docs/*.md`、`/releases/`、`/.github/`，并补齐现用的 `/main/xiaozhi-server/esp32_gateway/` 与 `/main/xiaozhi-server/answer_speech_worker/`。新增其他源码目录时，先用 `git sparse-checkout list` 查看范围，再通过 `git sparse-checkout add --stdin` 增加需要的路径；不要直接关闭稀疏检出或覆盖已有规则。

## 版本控制边界

提交源码、测试、依赖清单与锁文件、迁移、示例配置、说明文档和不含私有信息的发布目录。依赖锁文件必须跟随对应前端修改提交。

真实 `.env`、密钥、数据库文件与转储、Flash / NVS 镜像、运行配置、日志、依赖目录、构建产物和临时实验留在忽略目录。`dev/.runtime/` 继续由 `dev/.gitignore` 忽略。发布二进制与备份通过工作区 `releases/`、`backups/` 访问，Git 仅保存发布目录和文件校验信息。

## 发布记录

前后端发布时记录实际提交 SHA、构建时间和验证结果；如果存在未提交源码，明确记录，不把某个提交视为全部部署源码。固件发布额外记录板型、版本、源码快照和二进制 SHA-256，当前目录见仓库 `releases/catalog.toml`。源码快照的历史 `source_sha` 是原发布记录中的指纹，不能直接当作 Git 提交。
