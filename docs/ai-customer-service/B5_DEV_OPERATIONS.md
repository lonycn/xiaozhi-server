# B5 Dev 运行配置

B5 真人媒体默认关闭，依赖会话及人工支持。实施边界见 [B5 实施契约](./B5_IMPLEMENTATION_CONTRACT.md)。以下操作针对本工作目录的独立 Dev，不改共享 LiveKit 配置，也不初始化、导入或清空数据库和卷。使用前确认已构建含 B5 的 API：开关使用既有 `recreate_api` 和 staged JAR，不自动编译代码，也不验证 JAR 与源码一致。

首次使用既有私有 LiveKit 配置，并指定客户端实际可访问的地址：

```sh
python3 dev/manage.py rtc-enable --rtc-env /Users/lengwang/code/aimeet/.env --rtc-public-url wss://<客户端可访问的地址>
python3 dev/manage.py rtc-status
```

仅从指定文件读取 `LIVEKIT_INTERNAL_URL`、`LIVEKIT_URL`、`LIVEKIT_API_KEY` 和 `LIVEKIT_API_SECRET`，不打印密钥。缺项、格式不安全、重复字段或换行均拒绝。配置快照保存在本 checkout 的 `dev/.runtime/rtc-settings.json`，权限 600；生成的 `application-dev.properties` 同样为私有 600。首次生成独立随机房名前缀，后续重启或重复启用复用；现存前缀的内部服务地址或密钥发生变化时拒绝重定向，以免旧房失去归属或清理能力。客户端公开地址可通过参数调整；http/https 转为 ws/wss，容器内连接的 localhost/127.0.0.1/::1 转为 `host.docker.internal`。配置是来源文件的私有快照，源文件随后变更不会自动生效。

```sh
python3 dev/manage.py rtc-enable
python3 dev/manage.py rtc-status
python3 dev/manage.py rtc-disable
```

重复启用不带 `--rtc-env` 使用已保存的私有快照。`rtc-disable` 关闭公开媒体能力并重建 Dev API，保留私有 LiveKit 配置供后台持久清理；清理异步进行，关闭命令成功不代表所有参与者已断开或所有房间已清理。停用前检查待部署 JAR 是否包含 `MediaDrainController`，缺少时拒绝关闭并保留原开关；类标记只能防止明显旧版，不能证明完整 drain 逻辑已通过测试或真实清理。须先确认 drain 已集成、测试通过并完成构建。按当前 Java 契约可由授权用户访问 `GET /api/v1/support/media-drain?conversation_id=...` 查看本人会话的清理状态；凭据缺失或外部服务不可用时保留 pending 并重试，不能宣称已清理。`rtc-status` 仅显示保存的开关，非现场连通性或清理检查。

配置单元测试使用合成值，覆盖默认关闭、权限、地址和前缀保留、来源重定向拒绝及缺项失败；这些测试没有连接 LiveKit。Java 定向测试结果与真实部署另行记录；真实双浏览器双向音视频、同意和转接、断线恢复、网络切换及现场设备验收尚待执行，不能以配置、单元测试或建立房间代替。
