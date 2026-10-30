# P3 Realtime AI Dev 操作

本功能默认关闭；Java 控制面与 Python bridge 契约见 [REALTIME_AI_CONTRACT.md](./REALTIME_AI_CONTRACT.md)，worker 的协议与限制见 `main/xiaozhi-server/realtime_worker/README.md`。以下命令针对本 checkout 的 Dev，使用已构建的 API JAR 和独立 Python 环境；不会导入、重建或清空数据库，也不修改原设备、旧控制台或共享 LiveKit 配置。执行命令会重建 Dev API、启动或停止本 checkout 的 worker；仅在确认目标环境后运行。

启用前需准备含 `RealtimeController` 与 `RealtimeMaintenance` 的 API JAR，以及 `ROOT.parent/.realtime-venv/bin/python`（可用 `XIAOZHI_REALTIME_WORKER_PYTHON` 指向已准备的独立环境）和 worker requirements。`dev/.runtime/ai-worker.json` 须为 0600 且已有可用的百炼 API key 和 workspace 端点，或支持的公共地域端点加 workspace ID。此处仅显式复用其私有凭据配置，不读取 `.env`、查询或改写数据库；模型授权和网络可用性仍以实际调用为准。

```sh
python3 dev/manage.py realtime-enable
python3 dev/manage.py realtime-status
python3 dev/manage.py realtime-disable
```

可选 `realtime-enable --realtime-public-url wss://<客户端可访问域名>/realtime` 配置未来经过 TLS 的反向代理；开发默认 `ws://127.0.0.1:18899/realtime`，非 loopback 明文 WS 被拒绝。worker 仍只绑定 `127.0.0.1:18899/realtime`；Origin 默认仅允许 `http://127.0.0.1:18894` 和 `http://localhost:18894`，缺失 Origin 拒绝。远端代理需单独设计可信同源接入并验证 Origin，改 public URL 不会开放 worker 或自动配置 TLS。

启用时检查目标 JAR、Python 依赖和私有来源配置；从 workspace 派生 `wss://<workspace>.<region>.maas.aliyuncs.com/api-ws/v1/realtime`，URL 不含模型查询参数，worker 调用时自行指定 `qwen3.8-omni-flash-realtime`。独立随机 worker key 和 server ID 保存在 0600 私有设置中，重复启用保持稳定；worker 0600 私有配置携带 provider key，Java 配置只含独立 worker key 和无凭据 WebSocket URL。客户端仅以首个 WebSocket 消息提交短期一次性 ticket；禁止在 URL 中携带 ticket、长期 Bearer 或 provider key。

`up` 在标志已启用时启动本 checkout worker，`stop` 只通过 PID 启动时间、命令和本 checkout 配置路径核验后停止它。`realtime-disable` 先发送 SIGTERM 并等待 worker 关闭，随后关 API 开关并重建 API；已有 session 和转写行保留，残留租约由 Java maintenance 异步失效，不声称关闭命令完成时所有租约已即时撤销。worker 不恢复已消费的旧会话；中断后须显式新建会话。

当前交互是手动“开始录音 / 结束本段”：不是自动 VAD 或 WebRTC；摄像头仅在用户同意时发送受限 JPEG 采样帧，不是连续视频。提供方已完成一次真实音频问答，真实用量 547 tokens；这不是本 Dev 管理命令或完整浏览器链路的验收。真实浏览器麦克风、摄像头和权限、设备声音、TLS 代理、弱网与现场行为仍须单独验证；声音或图片不能单独证明设备故障或安全状态。

## 2026-10-30 恢复与浏览器注意事项

原Dev18894/API18893及原MySQL/Redis、S3已经恢复，当前恢复与技术媒体证据见 [恢复记录](./DEV_RECOVERY_VALIDATION_20261030.md)。麦克风权限prompt保持不限时并持续原有授权重验；权限返回后的Worklet/AudioContext/video启动各有10秒期限，取消/失败释放资源。结束先停止本地媒体，等待HTTP明确终态再关WebSocket，网络失败不显示为成功。真实浏览器39秒录音与技术画面问答、持久历史及文字草稿保持已通过；正常结束已到ended、8条技术track全停且17字草稿保留；结束后正常UI恢复B2文字实际回答“文字恢复正常”通过；已恢复原生输入，物理输入/输出仍须独立验收。

若仅在Ego agent页面出现AudioWorklet加载永不返回，同页无import最小probe可区别工具暂停与产品加载失败；已知 [ego-lite #468](https://github.com/citrolabs/ego-lite/issues/468) 涉及auto-attach filter。该诊断不改变产品鉴权、权限、ready规则或媒体输入，不把工具调整写成真实麦克风/摄像头验收。
