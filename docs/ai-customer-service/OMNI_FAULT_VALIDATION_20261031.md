# 非实时 Omni 故障与限制：隔离运行证据

日期：2026-10-31。基线 `c41b258`。在本机独立进程中运行实际 `media_worker`、`ai_worker` 的 `execute`、HTTP transport、FFmpeg 和 OpenAI Python SDK；本地 loopback HTTP 服务模拟 manager 与模型端点，未调用百炼、主 Dev API、数据库或 S3，也未修改服务配置。输入是明确生成的技术媒体，不是机械声音或现场证据。

- **无音轨视频**：1 秒 64×48 技术 MP4，经本地 HTTP 下载、真实 ffprobe/ffmpeg 检查和派生、HTTP 上传及完成回调；metadata `has_audio=false`、采样帧时间 500 ms，只上传 `frame_0` 与 `poster`，没有 `audio` 派生。此结果验证媒体 worker 路径，不宣称 Java 编排、真实 UI 或实际模型对无音轨视频的回答。
- **Omni 音频内联大小限制**：235 秒单声道 16 kHz PCM WAV 共 7,520,044 字节，通过真实 HTTP 下载超过 7,499,982 字节上限，AI worker 回调 `fail` / `OMNI_AUDIO_LIMIT`；模型端点收到零请求，无完成或输出回调。这是内联限制，不是上传上限或分段能力验证。
- **模型拒绝且不回退**：1 秒规范化技术 WAV 通过下载与准备后，真实 SDK 向本地模拟 Omni 端点发送一次请求，端点注入 HTTP 403；AI worker 回调 `fail` / `PROVIDER_AUTH`，未产生完成或输出回调，备用文本端点零请求。模拟 403 不是百炼实际故障、权限、限流或计费证据。

可重复命令（本机已准备的虚拟环境及 ffmpeg）：

```sh
PYTHONPATH=main/xiaozhi-server /Users/lengwang/code/esp32/backend/xiaozhi-server/.venv/bin/python /Users/lengwang/code/esp32/work/omni-fault-20261031/validate.py
PYTHONPATH=main/xiaozhi-server:main/xiaozhi-server/tests /Users/lengwang/code/esp32/backend/xiaozhi-server/.venv/bin/python -m unittest test_ai_worker test_ai_worker_media test_ai_worker_omni -q
```

第二条 39 项通过。脚本、技术媒体及不含真实凭据或音频内容的结果保存在 `/Users/lengwang/code/esp32/work/omni-fault-20261031/`，其中 `proof.json` 记录回调、模型请求及媒体摘要。该脚本每次重建自己的同名技术样本，不访问业务媒体。

仍需在获授权的真实业务链路验证错误呈现、用户重试及不同故障恢复；实际百炼故障与受控本地注入分别判断。真实机械声音、现场数据和专家对照尚未验收。
