# P3 Python执行面决策

2026-10-30。遵循同目录 `REALTIME_AI_CONTRACT.md`，不修改旧ESP32协议、人工LiveKit、数据库或Java业务权限。

本轮交付完整manual技术MVP：独立loopback WebSocket worker、单用短ticket、Java lease/epoch、真实provider双向音频、真实摄像头JPEG采样、已有context初始化、持久文本注入及精确item ACK、真实ASR/回复/usage回传、中断与资源释放。`semantic_vad`/自动启停和WebRTC不在本轮，不能把manual UI描述成自动通话。浏览器输入不包含任意provider/session/tool事件。

官方 [Realtime指南](https://help.aliyun.com/zh/model-studio/realtime) 和 [客户端事件](https://help.aliyun.com/zh/model-studio/client-events) 已于本轮核对。采用现代 `audio.input.format` PCM16 mono16k、`audio.output.format` PCM24k/longanlingxin；明确启用 `input_audio_transcription`，等待实际updated后再处理输入。图像只用JPEG，保守限制原字节190000、Base64≤256000、≤921600像素、1fps，且之前必须有同turn音频。任务契约已与Java/Vue同步这些限制，不沿用原先PNG/300KiB建议。

Java不会知道尚未commit的本地音频，所以heartbeat必须带严格boolean `input_busy`。其作用仅减少派发权限：worker音频缓冲、等待commit/response、正在回复或等待injection ACK时均为true。每条媒体与每段输出仍重新验证当前lease，不把busy当授权缓存。已经派发的注入不重放；断线后重新创建明确的session/ticket。

音频先收到真实committed，Java接受 `input.committed` 为用户消息固定顺序后才response.create。ASR completed可先到或迟到，均由Java填相同provider item关联消息；worker不编造转写。未完成ASR的占位状态由Java终结/清理，不用空白文字伪装成功。

中断存在三个实际竞态，已联合Java冻结：持久中断命令一到便停止音频并清播放；真实provider done的completed/canceled均可确认本地取消，保留Java已接受partial；若delta的HTTP409仅为精确 `RESPONSE_INTERRUPTED_OR_TERMINAL`，保持sequence并恢复取中断，其它409均撤权；若完成事件回执显示message已canceled，绝不输出未被接受的最终全文。保留最多100轮真实done的标识，供后到命令ACK，不对已经结束的response再次发cancel。任何terminal session回执立即停止；只有已接受的错误/ended清理通知可以返回浏览器，无音频/文本继续外泄。取消usage若完整且来自真实done同样保留。

提供方API key只来自私有0600配置，独立于Java realtime worker key；不读取旧设备或现有Dev秘密配置。模块默认disabled且仅bind loopback，Origin exact allowlist，无URL ticket/浏览器Bearer旁路。配置例子含占位符，必须另放私有文件。本轮未启动真实worker服务、未调用外部provider。
