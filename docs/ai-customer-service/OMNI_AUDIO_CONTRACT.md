# 非实时原始声音理解契约

日期：2026-10-30。基线 `9cfb7af`，范围为原规划 §2、§4、§14 中上传录音、有声视频的 Omni Flash 分析；不包含 Omni Realtime。

## 启用与路由

`ai.execution.omni-model` 默认空串。只有显式配置 `qwen3.8-omni-flash` 时，当前输入中 `audio` 或已检查 `video.has_audio=true` 才取 `audio` 规范化派生物，并在 claim 时优先选择 Omni。视频同时保留最多六张已检查采样帧及其 `time_ms`。不等待独立 ASR 任务，不用转写文字替代原始声音；Omni 音频能力不依赖配置 ASR。

关闭时保留 B3 的音频转写、视频抽帧与视觉模型路径；普通文字、图片、文档和无声音视频维持原路由。历史仅传持久文本及附件引用说明，不再次传历史媒体。Omni 模型不可用时明确失败，可按既有 run 规则重试，保留原件，不切换模型并声称分析过声音。

Python worker 新配置为 `omni_model`、`omni_base_url`、`omni_api_key`、可选 `omni_workspace_id`。启用须明确设置合法百炼 HTTPS endpoint 和 key；独立 SDK client，不隐式从 text client 读取 key。Dev `python3 dev/manage.py ai-enable --omni-model qwen3.8-omni-flash` 显式把当前已选百炼 LLM 凭据复用至私有 `dev/.runtime/ai-worker.json`（0600），并导出对应 Java property。省略参数保留已设置选择；`--omni-model ''` 关闭。此命令属于父代理实际 Dev 联调阶段，本分支未执行。

## 媒体权限及 wire

继续使用 `GET /internal/ai-worker/runs/{run_id}/attachments/{attachment_id}/content?variant=audio`，专用 worker key 及 `X-AI-Lease`。每次读取验证当前 run lease、状态、owner 账号、conversation control epoch 与 AI_ACTIVE；必须是该 run 当前输入重建 plan 中精确的附件/variant，且该 run 已选 Omni。拒绝原件、历史/其他会话附件、任意 URL、过期 lease、旧 epoch、人工接管后的请求。内容流在授权事务之后打开，延续 B3 的受控内部能力边界，不返回存储 key。

worker 只流式下载受限字节；拒绝重定向、非 identity 压缩及错误 MIME。`run_id`/`attachment_id` 必须字符串；只对受限 `time_ms` 十进制字符串转整数，保留大整数 ID。WAV 再检查 PCM、单声道、16kHz、16bit、完整帧、音频≤600秒/视频音轨≤180秒。SDK wire 为 `input_audio: {data: "data:audio/wav;base64,...", format: "wav"}`，同时可有 text/image_url；不向提供方发送内部附件引用、lease、存储 key。

## 有限输入与失败

[官方 Omni 文档](https://help.aliyun.com/zh/model-studio/qwen-omni)（2026-10-30 查阅）列明 Qwen3.8 音频最长3小时、音频 Base64 编码字符串必须小于10MB，支持当前使用的 Data URI 与混合媒体；[3.8 型号页](https://help.aliyun.com/zh/model-studio/qwen3-8-omni-flash)关联相同能力说明。本模块仍保持 B3 上传限额，不宣称所有600秒规范化 WAV 都可直接分析。

以保守十进制10,000,000字节作为内联上限，规范化 WAV 限7,499,982字节（包含 Data URI 前缀后的 wire 字符串也严格小于10MB），约234秒完整16k单声道PCM。180秒视频音轨可全部发送；更长录音若超该字节数则 `OMNI_AUDIO_LIMIT` 明确失败，不截短、不分段拼凑结论、不改走转写。用户可另发较短片段；原上传/检查/原件保留。MIME、WAV或输入授权错误沿用 `MEDIA_INPUT_UNAVAILABLE`；模型权限/限流/超时沿用明确 `PROVIDER_*`。

## 输出及验收

Omni 只要求文本输出，`reasoning_effort=none`；仅消费 `delta.content`，不保存、发送或展示 `reasoning_content`，不生成冒名转写。使用提供方原始 usage 总量；有可信 `prompt_tokens_details.audio_tokens` 时额外保存 `usage.input_audio_tokens` 到既有 run usage_json，总量账本不改表。无 usage 时留空，不估算 token。

提示要求区分可听证据和推测，环境/机械声不能独自确认故障或安全运行状态，需要测量与专家确认。技术 WAV/静音/普通 TTS 只验证传输与协议，不构成现场机械声判断验收。真实 Dev 上传→规范化→Omni→流回复/用量、真实机械声和客户场景由后续单列验收；非实时能力不证明 Realtime 模型已授权。
